import java.io.*;
import java.net.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import android.graphics.*;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;

// 全局资源池
ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);

public void unLoadPlugin() {
    if (executor != null && !executor.isShutdown()) {
        executor.shutdownNow();
    }
}

public void onMsg(Object msgData) {
    String rawMsg = msgData.msg;
    if (rawMsg == null) return;
    
    // 关键修复：暴力清除所有 [...] 括号里的标签代码，只取纯文本进行指令判断
    // 这样无论你是回复(带有[reply=])还是带图(带有[pic=])，都能完美剥离出“文字图 喵”
    String pureText = rawMsg.replaceAll("\\[[^\\]]+\\]", "").trim();
    
    if (pureText.startsWith("文字图")) {
        processTextArtCommand(msgData, rawMsg, pureText);
    }
}

void processTextArtCommand(Object msgData, String rawMsg, String pureText) {
    String peerUin = msgData.peerUin;
    int msgType = msgData.type;
    long msgId = msgData.msgId;

    // --- 1. 核心：获取图片 ---
    String picPathOrUrl = null;

    // 优先 1：检查是否在消息中带了图片 (同行发图)
    // 修复：剥离 QFun 附加的 hash=xxx, size=xxx 参数，只取纯净 URL
    Matcher mPic = Pattern.compile("\\[pic=(.*?)\\]").matcher(rawMsg);
    if (mPic.find()) {
        String content = mPic.group(1);
        Matcher mUrl = Pattern.compile("(https?://[^\\s,\\]]+)").matcher(content);
        if (mUrl.find()) {
            picPathOrUrl = mUrl.group(1); // 提取到纯净 Http 链接
        } else {
            picPathOrUrl = content.split(",")[0].trim(); // 兜底：可能是本地路径
        }
    }

    // 优先 2：使用环境中可能自带的 getImageUrl 函数
    if (picPathOrUrl == null || picPathOrUrl.trim().isEmpty()) {
        try { picPathOrUrl = getImageUrl(msgData); } catch (Throwable e) {}
    }

    // 优先 3：如果是“回复”操作，深入底层反射提取被回复的图片
    if (picPathOrUrl == null || picPathOrUrl.trim().isEmpty()) {
        picPathOrUrl = extractReplyImage(msgData);
    }

    // 检测结果
    if (picPathOrUrl == null || picPathOrUrl.trim().isEmpty() || picPathOrUrl.equals("null")) {
        sendReplyMsg(peerUin, msgId, "未检测到图片！如果这是一条回复，框架底层可能未提供图片数据。\n\n【调试信息】收到的原代码:\n" + rawMsg, msgType);
        return;
    }

    // --- 2. 解析文字与网格参数 ---
    String argsStr = pureText.replaceFirst("文字图", "").trim();
    if (argsStr.isEmpty()) {
        sendReplyMsg(peerUin, msgId, "【文字图用法】\n请“回复”或带图发送：文字图 喵 120 120", msgType);
        return;
    }

    String[] parts = argsStr.split("\\s+");
    String text = parts[0]; 
    int cols = 120, rows = 120;
    
    // 容错处理：用户可能忘了加空格，例如输入了 "喵100" 也能识别
    if (parts.length == 1 && text.length() > 1) {
        Matcher noSpace = Pattern.compile("^(.*?)(\\d+)$").matcher(text);
        if (noSpace.find()) {
            text = noSpace.group(1);
            cols = Integer.parseInt(noSpace.group(2));
            rows = cols;
        }
    } else {
        try {
            if (parts.length >= 2) cols = Math.max(1, Integer.parseInt(parts[1]));
            if (parts.length >= 3) rows = Math.max(1, Integer.parseInt(parts[2]));
        } catch (Exception ignored) {}
    }

    // --- 3. 后台渲染与引用回复 ---
    final String finalPic = picPathOrUrl;
    final String finalText = text;
    final int finalCols = cols;
    final int finalRows = rows;

    executor.submit(() -> {
        String[] errOut = new String[1]; // 用于捕获具体的下载报错
        String localImgPath = null;
        String outImgPath = null;
        try {
            if (finalPic.startsWith("http://") || finalPic.startsWith("https://")) {
                localImgPath = downloadImage(finalPic, errOut);
                if (localImgPath == null) {
                    // 如果下载失败，将把具体的报错抛到群里，秒懂原因！
                    sendReplyMsg(peerUin, msgId, "下载失败！\n链接: " + finalPic + "\n原因: " + errOut[0], msgType);
                    return;
                }
            } else {
                localImgPath = finalPic; 
            }

            outImgPath = generateTextArt(finalText, localImgPath, finalCols, finalRows, 0);
            if (outImgPath != null && outImgPath.startsWith("ERROR:")) {
                sendReplyMsg(peerUin, msgId, "生成失败: " + outImgPath, msgType);
                return;
            }

            sendReplyMsg(peerUin, msgId, "[pic=" + outImgPath + "]", msgType);

        } catch (Exception e) {
            sendReplyMsg(peerUin, msgId, "执行异常: " + e.getMessage(), msgType);
        } finally {
            final String fLocalImg = localImgPath;
            final String fOutImg = outImgPath;
            final boolean isDownloaded = finalPic.startsWith("http");
            executor.schedule(() -> {
                try {
                    if (fOutImg != null) new File(fOutImg).delete();
                    if (isDownloaded && fLocalImg != null) new File(fLocalImg).delete();
                } catch (Exception ignored) {}
            }, 2, TimeUnit.MINUTES);
        }
    });
}

// ================= 底层回复解析 =================
String extractReplyImage(Object msgData) {
    try {
        Object record = msgData.data; 
        if (record == null) return null;
        Class<?> clazz = record.getClass();
        if (clazz.getSimpleName().equals("MessageForReplyText")) {
            Field mSourceMsgInfoField = clazz.getDeclaredField("mSourceMsgInfo");
            mSourceMsgInfoField.setAccessible(true);
            Object mSourceMsgInfo = mSourceMsgInfoField.get(record);
            if (mSourceMsgInfo != null) {
                Field mSourceMsgField = mSourceMsgInfo.getClass().getDeclaredField("mSourceMsg");
                mSourceMsgField.setAccessible(true);
                Object mSourceMsg = mSourceMsgField.get(mSourceMsgInfo);
                return extractPicUrlFromRecord(mSourceMsg);
            }
        }
    } catch (Throwable e) {}
    return null;
}

String extractPicUrlFromRecord(Object record) {
    if (record == null) return null;
    try {
        Class<?> clazz = record.getClass();
        String name = clazz.getSimpleName();
        if (name.equals("MessageForPic")) {
            try {
                Field pathField = clazz.getDeclaredField("path");
                pathField.setAccessible(true);
                String path = (String) pathField.get(record);
                if (path != null && new File(path).exists()) return path;
            } catch (Throwable t) {}
            try {
                Field md5Field = clazz.getDeclaredField("md5");
                md5Field.setAccessible(true);
                String md5 = (String) md5Field.get(record);
                if (md5 != null && md5.length() > 0) {
                    return "https://gchat.qpic.cn/gchatpic_new/0/0-0-" + md5.toUpperCase() + "/0";
                }
            } catch (Throwable t) {}
        } else if (name.equals("MessageForMixedMsg")) {
            Field msgElemListField = clazz.getDeclaredField("msgElemList");
            msgElemListField.setAccessible(true);
            List<?> list = (List<?>) msgElemListField.get(record);
            if (list != null) {
                for (Object elem : list) {
                    String pic = extractPicUrlFromRecord(elem);
                    if (pic != null) return pic;
                }
            }
        }
    } catch (Throwable e) {}
    return null;
}

// ================= 最强图片下载器 (破除证书与明文限制) =================
String downloadImage(String urlStr, String[] errOut) {
    InputStream is = null;
    FileOutputStream fos = null;
    try {
        urlStr = urlStr.replace("&amp;", "&");
        // 安卓9+ 默认禁止明文HTTP，强行转 HTTPS 解决大量下载报错
        if (urlStr.startsWith("http://")) {
            urlStr = urlStr.replaceFirst("http://", "https://");
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        // 动态信任所有证书，绕过部分因系统证书不全导致的 SSLHandshake 异常
        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] arg0, String arg1) {}
                    public void checkServerTrusted(X509Certificate[] arg0, String arg1) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            httpsConn.setSSLSocketFactory(sc.getSocketFactory());
            httpsConn.setHostnameVerifier(new HostnameVerifier() {
                public boolean verify(String hostname, SSLSession session) { return true; }
            });
        }

        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);
        conn.connect();

        int status = conn.getResponseCode();
        if (status == 301 || status == 302 || status == 307 || status == 308) {
            String redirectUrl = conn.getHeaderField("Location");
            if (redirectUrl != null) return downloadImage(redirectUrl, errOut);
        }

        if (status >= 400) {
            errOut[0] = "服务端返回拒绝状态码: " + status;
            return null;
        }

        File dir = new File(pluginPath + "/dump/downloads/");
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, "dl_" + System.currentTimeMillis() + ".png");

        is = conn.getInputStream();
        fos = new FileOutputStream(outFile);
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            fos.write(buffer, 0, len);
        }
        
        return outFile.getAbsolutePath();
    } catch (Exception e) {
        errOut[0] = "Java底层异常: " + e.toString();
        return null;
    } finally {
        try { if (fos != null) fos.close(); } catch (Exception ignored) {}
        try { if (is != null) is.close(); } catch (Exception ignored) {}
    }
}

// ================= 核心文字图渲染 =================
String generateTextArt(String text, String imagePath, int gridCols, int gridRows, int maxWidth) {
    Bitmap original = null;
    Bitmap scaled = null;
    Bitmap result = null;
    FileOutputStream os = null;

    try {
        if (text == null || text.trim().isEmpty()) return "ERROR: 文字不能为空";
        text = text.trim();

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        opts.inMutable = false;
        original = BitmapFactory.decodeFile(imagePath, opts);
        if (original == null) return "ERROR: 图片解码失败，可能图片已损坏";

        int origW = original.getWidth();
        int origH = original.getHeight();

        float scale = 1f;
        if (maxWidth > 0 && origW > maxWidth) scale = (float) maxWidth / (float) origW;

        int sampleW = Math.max(1, (int) (origW * scale));
        int sampleH = Math.max(1, (int) (origH * scale));
        int sampleCellW = Math.max(1, sampleW / gridCols);
        int sampleCellH = Math.max(1, sampleH / gridRows);
        int scaledW = sampleCellW * gridCols;
        int scaledH = sampleCellH * gridRows;

        scaled = Bitmap.createScaledBitmap(original, scaledW, scaledH, true);
        sampleW = scaled.getWidth();
        sampleH = scaled.getHeight();

        int[] pixels = new int[sampleW * sampleH];
        scaled.getPixels(pixels, 0, sampleW, 0, 0, sampleW, sampleH);
        original.recycle();
        scaled.recycle();

        int stride = sampleW + 1;
        int integralSize = stride * (sampleH + 1);
        int[] integralR = new int[integralSize];
        int[] integralG = new int[integralSize];
        int[] integralB = new int[integralSize];

        for (int y = 1; y <= sampleH; y++) {
            int rowR = 0, rowG = 0, rowB = 0;
            int rowIndex = (y - 1) * sampleW;
            for (int x = 1; x <= sampleW; x++) {
                int c = pixels[rowIndex + (x - 1)];
                rowR += (c >> 16) & 255;
                rowG += (c >> 8) & 255;
                rowB += c & 255;
                int idx = y * stride + x;
                int prev = (y - 1) * stride + x;
                integralR[idx] = integralR[prev] + rowR;
                integralG[idx] = integralG[prev] + rowG;
                integralB[idx] = integralB[prev] + rowB;
            }
        }

        int cellScale = 8;
        int cellW = Math.max(28, sampleCellW * cellScale);
        int cellH = Math.max(28, sampleCellH * cellScale);
        int finalW = cellW * gridCols;
        int finalH = cellH * gridRows;

        result = Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.drawColor(Color.WHITE);

        Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setTextAlign(Paint.Align.CENTER);
        basePaint.setTypeface(Typeface.SERIF);
        float textSize = Math.min(cellW, cellH) * 0.84f;
        basePaint.setTextSize(textSize);

        char[] chars = text.toCharArray();
        int charLen = chars.length;
        
        int threadCount = Math.max(2, Runtime.getRuntime().availableProcessors());
        if (gridRows < threadCount) threadCount = gridRows;
        
        ExecutorService renderPool = Executors.newFixedThreadPool(threadCount);
        List<Future<Bitmap>> futures = new ArrayList<>();
        int rowsPerThread = (gridRows + threadCount - 1) / threadCount;
        AtomicInteger charCounter = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int startRow = t * rowsPerThread;
            final int endRow = Math.min(gridRows, startRow + rowsPerThread);
            if (startRow >= endRow) continue;

            futures.add(renderPool.submit(() -> {
                Bitmap part = Bitmap.createBitmap(finalW, (endRow - startRow) * cellH, Bitmap.Config.ARGB_8888);
                Canvas partCanvas = new Canvas(part);
                partCanvas.drawColor(Color.WHITE);
                Paint paint = new Paint(basePaint);
                Paint.FontMetrics partFm = paint.getFontMetrics();

                for (int row = startRow; row < endRow; row++) {
                    int sy1 = row * sampleCellH;
                    int sy2 = sy1 + sampleCellH;
                    for (int col = 0; col < gridCols; col++) {
                        int sx1 = col * sampleCellW;
                        int sx2 = sx1 + sampleCellW;
                        int p1 = sy1 * stride + sx1;
                        int p2 = sy1 * stride + sx2;
                        int p3 = sy2 * stride + sx1;
                        int p4 = sy2 * stride + sx2;
                        int area = Math.max(1, (sx2 - sx1) * (sy2 - sy1));
                        
                        int r = (integralR[p4] - integralR[p2] - integralR[p3] + integralR[p1]) / area;
                        int g = (integralG[p4] - integralG[p2] - integralG[p3] + integralG[p1]) / area;
                        int b = (integralB[p4] - integralB[p2] - integralB[p3] + integralB[p1]) / area;
                        
                        r = Math.max(0, Math.min(255, (int) (r * 1.04f + 2f)));
                        g = Math.max(0, Math.min(255, (int) (g * 1.02f + 2f)));
                        b = Math.max(0, Math.min(255, (int) (b * 1.01f + 2f)));
                        paint.setColor(Color.rgb(r, g, b));
                        
                        int charIndex = Math.abs(charCounter.getAndIncrement()) % charLen;
                        float dx = col * cellW + cellW * 0.5f;
                        float dy = (row - startRow) * cellH + cellH * 0.5f - ((partFm.ascent + partFm.descent) * 0.5f);
                        partCanvas.drawText(String.valueOf(chars[charIndex]), dx, dy, paint);
                    }
                }
                return part;
            }));
        }

        renderPool.shutdown(); 
        int drawY = 0;
        for (Future<Bitmap> future : futures) {
            Bitmap part = future.get(); 
            canvas.drawBitmap(part, 0f, drawY, null);
            drawY += part.getHeight();
            part.recycle();
        }

        File dir = new File(pluginPath + "/dump/textart/");
        if (!dir.exists()) dir.mkdirs();
        File outFile = new File(dir, "textart_" + System.currentTimeMillis() + ".png");
        
        os = new FileOutputStream(outFile);
        result.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.flush();
        return outFile.getAbsolutePath();

    } catch (Exception e) {
        return "ERROR:" + e.toString();
    } finally {
        try { if (os != null) os.close(); } catch (Exception ignored) {}
        try { if (result != null && !result.isRecycled()) result.recycle(); } catch (Exception ignored) {}
    }
}