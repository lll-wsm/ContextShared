package com.lll.contextshared.server;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lll.contextshared.model.DeviceInfo;
import com.lll.contextshared.model.FileItem;
import com.lll.contextshared.model.WsMessage;
import com.lll.contextshared.util.SafDocuments;
import com.lll.contextshared.util.StorageHelper;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoWSD;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 单端口服务器：同时承载 Web 静态资源、REST API 与 WebSocket(/ws)。
 *
 * <p>历史实现把 HTTP(8080) 与 WebSocket(8081) 拆成两个 NanoHTTPD 实例，但 Web 客户端
 * 连接的是 {@code location.host} 的 {@code /ws}（即 8080），导致握手永远 404、页面每 2 秒
 * 无限重连。这里统一到同一个端口，与设计文档（"/ws 同端口"）保持一致。
 */
public class AppHttpServer extends NanoWSD {

    public interface OnMessageReceivedListener {
        void onClipboardReceived(String text);
    }

    /** 静态资源缓存条目：APK 内的资源体积很小，读一次即可，避免每次请求都走 AssetManager。 */
    private static final class AssetEntry {
        final byte[] data;
        final String etag;

        AssetEntry(byte[] data, String etag) {
            this.data = data;
            this.etag = etag;
        }
    }

    private static final String CACHE_CONTROL_REVALIDATE = "no-cache";
    private static final String CACHE_CONTROL_IMAGE = "public, max-age=86400";
    private static final String TAG = "ContextShared";

    private final Context context;
    private final SessionManager sessionManager;
    private final Gson gson = new Gson();
    private final Set<ContextWebSocket> connectedClients =
            Collections.newSetFromMap(new ConcurrentHashMap<ContextWebSocket, Boolean>());
    private final Map<String, AssetEntry> assetCache = new ConcurrentHashMap<>();
    private volatile OnMessageReceivedListener messageListener;

    public AppHttpServer(Context context, int port, SessionManager sessionManager) {
        super(port);
        this.context = context != null ? context.getApplicationContext() : null;
        this.sessionManager = sessionManager;
        // 探测可浏览的存储卷（内部存储 + 存在的外置存储）
        StorageHelper.refreshVolumes(this.context);
        // SAF 授权状态（Android/data、Android/obb）
        SafDocuments.init(this.context);
        // 关键：默认实现的构造函数会访问 java.io.tmpdir（CE 加密存储），
        // 而它恰好是在"每个新建 TCP 连接"时被调用的。
        setTempFileManagerFactory(new LazyTempFileManagerFactory(this.context));
    }

    /**
     * 接管每个新连接的处理器创建过程。
     *
     * <p><b>这是本项目最大的性能陷阱</b>：NanoHTTPD 2.3.1 的 {@code HTTPSession} 四参构造函数会
     * 对客户端 IP 调用 {@code InetAddress.getHostName()}（反向 DNS）：
     *
     * <pre>
     * this.remoteHostname = ... ? "localhost" : inetAddress.getHostName().toString();
     * </pre>
     *
     * <p>它只是为了填一个本项目从未使用的字段，但在局域网里这个解析经常要几十秒才返回
     * （实机线程栈停在 {@code libcore.io.Linux.getnameinfo}）。后果是：
     * <ul>
     *   <li>每个<b>新建</b> TCP 连接的第一个请求都会卡住 30~40 秒（连 404 也一样）；</li>
     *   <li>而 keep-alive 复用的连接只要十几毫秒（不重建 session）；</li>
     *   <li>因此表现为"页面要等几十秒才整体出现、PIN 框也迟迟不弹"。</li>
     * </ul>
     *
     * <p>这里改用不带 InetAddress 的三参构造函数，彻底不做任何 DNS 解析
     * （{@code remoteIp}/{@code remoteHostname} 保持 null，NanoHTTPD 已做空值兼容）。
     */
    @Override
    protected ClientHandler createClientHandler(final Socket finalAccept, final InputStream inputStream) {
        return new FastClientHandler(inputStream, finalAccept);
    }

    private final class FastClientHandler extends NanoHTTPD.ClientHandler {
        private final InputStream clientInput;
        private final Socket clientSocket;

        FastClientHandler(InputStream inputStream, Socket acceptSocket) {
            super(inputStream, acceptSocket);
            this.clientInput = inputStream;
            this.clientSocket = acceptSocket;
        }

        @Override
        public void run() {
            OutputStream outputStream = null;
            try {
                outputStream = clientSocket.getOutputStream();
                TempFileManager tempFileManager = getTempFileManagerFactory().create();
                // 三参构造：不传 InetAddress → 零 DNS 解析
                HTTPSession session = new HTTPSession(tempFileManager, clientInput, outputStream);
                while (!clientSocket.isClosed()) {
                    session.execute();
                }
            } catch (Exception e) {
                boolean expected = (e instanceof SocketException && "NanoHttpd Shutdown".equals(e.getMessage()))
                        || e instanceof SocketTimeoutException;
                if (!expected) {
                    Log.w(TAG, "客户端连接处理异常", e);
                }
            } finally {
                closeQuietly(outputStream);
                closeQuietly(clientInput);
                closeQuietly(clientSocket);
                asyncRunner.closed(this);
            }
        }
    }

    private static void closeQuietly(Object closeable) {
        if (closeable instanceof Closeable) {
            try {
                ((Closeable) closeable).close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 全部响应都使用确定的 Content-Length，不使用 gzip/chunked。
     *
     * <p>局域网下 82KB 静态资源不值得为压缩引入 chunked：chunked 会丢掉 Content-Length，
     * 让浏览器无法判断响应完整性与进度，也让响应难以被缓存复用。
     */
    @Override
    protected boolean useGzipWhenAccepted(Response response) {
        return false;
    }

    @Override
    public Response serve(IHTTPSession session) {
        // WebSocket 握手：必须先通过 token 鉴权，否则返回 401。
        // 这样客户端只要 onopen 就代表"已配对"，可以安全关闭 PIN 弹窗，
        // 也不会为未鉴权客户端白占一个连接线程。
        if (isWebsocketRequested(session)) {
            Map<String, String> wsParams = session.getParms();
            String wsToken = wsParams != null ? wsParams.get("token") : null;
            if (!sessionManager.isValidToken(wsToken)) {
                return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}");
            }
            return super.serve(session);
        }

        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = session.getParms();

        // 1. 静态资源路由
        if (Method.GET.equals(method)) {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return serveAsset(session, "web/index.html", "text/html; charset=utf-8");
            } else if ("/style.css".equals(uri)) {
                return serveAsset(session, "web/style.css", "text/css; charset=utf-8");
            } else if ("/app.js".equals(uri)) {
                return serveAsset(session, "web/app.js", "application/javascript; charset=utf-8");
            } else if ("/favicon.png".equals(uri) || "/favicon.ico".equals(uri)) {
                return serveAsset(session, "web/favicon.png", "image/png");
            } else if ("/logo.png".equals(uri)) {
                return serveAsset(session, "web/logo.png", "image/png");
            }
        }

        // 2. API 路由
        if (uri.startsWith("/api/")) {
            return handleApi(session, uri, method, params);
        }

        return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

    // ------------------------------------------------------------------
    // WebSocket
    // ------------------------------------------------------------------

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ContextWebSocket(handshake);
    }

    public void setMessageListener(OnMessageReceivedListener listener) {
        this.messageListener = listener;
    }

    public int getConnectedCount() {
        return connectedClients.size();
    }

    public void broadcast(WsMessage message) {
        String json = message.toJson();
        for (ContextWebSocket client : connectedClients) {
            if (client.isOpen() && client.isAuthenticated()) {
                try {
                    client.send(json);
                } catch (IOException ignored) {
                    // 客户端已断开，onClose/onException 会把它移除
                }
            }
        }
    }

    class ContextWebSocket extends WebSocket {
        private final boolean authenticated;

        ContextWebSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
            String token = (handshakeRequest != null && handshakeRequest.getParms() != null)
                    ? handshakeRequest.getParms().get("token")
                    : null;
            this.authenticated = sessionManager.isValidToken(token);
        }

        public boolean isAuthenticated() {
            return authenticated;
        }

        @Override
        protected void onOpen() {
            connectedClients.add(this);
        }

        @Override
        protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
            connectedClients.remove(this);
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            try {
                String payload = message.getTextPayload();
                WsMessage wsMsg = WsMessage.fromJson(payload);
                if (wsMsg == null) {
                    return;
                }
                if ("CLIPBOARD_SEND".equals(wsMsg.getType())) {
                    if (!authenticated) {
                        return;
                    }
                    String text = wsMsg.getPayload() != null ? wsMsg.getPayload().toString() : "";
                    OnMessageReceivedListener listener = messageListener;
                    if (listener != null) {
                        listener.onClipboardReceived(text);
                    }
                } else if ("PING".equals(wsMsg.getType())) {
                    send(new WsMessage("PONG", "pong").toJson());
                }
            } catch (Exception ignored) {
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {
        }

        @Override
        protected void onException(IOException exception) {
            connectedClients.remove(this);
        }
    }

    // ------------------------------------------------------------------
    // 静态资源
    // ------------------------------------------------------------------

    private Response serveAsset(IHTTPSession session, String assetPath, String mimeType) {
        if (context == null) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset not found: " + assetPath);
        }
        AssetEntry entry;
        try {
            entry = loadAsset(assetPath);
        } catch (Exception e) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset not found: " + assetPath);
        }
        // 页面/脚本/样式必须每次重校验，否则升级 APK 后浏览器会继续用旧前端；
        // 图片体积较大且不会变，直接给一天缓存。
        String cacheControl = (mimeType != null && mimeType.startsWith("image/"))
                ? CACHE_CONTROL_IMAGE : CACHE_CONTROL_REVALIDATE;

        String ifNoneMatch = session != null && session.getHeaders() != null
                ? session.getHeaders().get("if-none-match") : null;
        if (ifNoneMatch != null && ifNoneMatch.contains(entry.etag)) {
            Response notModified = newFixedLengthResponse(Status.NOT_MODIFIED, mimeType, "");
            notModified.addHeader("ETag", entry.etag);
            notModified.addHeader("Cache-Control", cacheControl);
            return notModified;
        }

        Response res = newFixedLengthResponse(Status.OK, mimeType,
                new ByteArrayInputStream(entry.data), entry.data.length);
        res.addHeader("ETag", entry.etag);
        res.addHeader("Cache-Control", cacheControl);
        return res;
    }

    private AssetEntry loadAsset(String assetPath) throws IOException {
        AssetEntry cached = assetCache.get(assetPath);
        if (cached != null) {
            return cached;
        }
        byte[] data;
        try (InputStream is = context.getAssets().open(assetPath)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(1024, is.available()));
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            data = out.toByteArray();
        }
        AssetEntry entry = new AssetEntry(data, computeEtag(data));
        assetCache.put(assetPath, entry);
        return entry;
    }

    private static String computeEtag(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(2 + hash.length * 2);
            sb.append('"');
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            sb.append('"');
            return sb.toString();
        } catch (Exception e) {
            return "\"" + data.length + "\"";
        }
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    private Response handleApi(IHTTPSession session, String uri, Method method, Map<String, String> params) {
        try {
            if ("/api/info".equals(uri) && Method.GET.equals(method)) {
                return getDeviceInfoResponse();
            } else if ("/api/auth/verify".equals(uri) && Method.POST.equals(method)) {
                return verifyAuth(session);
            }

            // 需鉴权接口校验
            String token = params.get("token");
            if (!sessionManager.isValidToken(token)) {
                return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}");
            }

            if ("/api/fs/volumes".equals(uri) && Method.GET.equals(method)) {
                return listVolumesResponse();
            } else if ("/api/fs/grants".equals(uri) && Method.GET.equals(method)) {
                return listGrantsResponse();
            } else if ("/api/action/request-saf-access".equals(uri) && Method.POST.equals(method)) {
                return requestSafAccess(params.get("target"));
            } else if ("/api/files/list".equals(uri) && Method.GET.equals(method)) {
                // 旧客户端只带 category（返回裸数组）；新客户端带 path（返回带目录元信息的对象）
                if (params.get("path") == null && params.get("category") != null) {
                    List<FileItem> list = StorageHelper.listFiles(params.get("category"));
                    return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(list));
                }
                return listDirectoryResponse(params.get("path"));
            } else if ("/api/files/download".equals(uri) && Method.GET.equals(method)) {
                return serveFileDownload(session, params.get("path"));
            } else if ("/api/files/zip".equals(uri) && Method.POST.equals(method)) {
                return serveZipDownload(session);
            } else if ("/api/fs/search".equals(uri) && Method.GET.equals(method)) {
                return searchResponse(params.get("path"), params.get("q"), params.get("limit"), params.get("depth"));
            } else if ("/api/files/upload".equals(uri) && Method.POST.equals(method)) {
                return handleFileUpload(session, params.get("path"));
            } else if ("/api/action/open-url".equals(uri) && Method.POST.equals(method)) {
                return handleOpenUrl(session);
            } else if ("/api/action/request-file-access".equals(uri) && Method.POST.equals(method)) {
                return requestAllFilesAccess();
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
        return newFixedLengthResponse(Status.NOT_FOUND, "application/json", "{\"error\":\"API Not Found\"}");
    }

    // ------------------------------------------------------------------
    // 下载文件名编码
    // ------------------------------------------------------------------

    /**
     * 构造 Content-Disposition。
     *
     * <p>HTTP 头只能是 ASCII（NanoHTTPD 用 US-ASCII 输出头），直接把中文文件名写进去
     * 会变成 {@code filename="??????.txt"}。按 RFC 6266/5987 同时给出：
     * <ul>
     *   <li>ASCII 回退 {@code filename="..."}（老客户端用）；</li>
     *   <li>UTF-8 扩展 {@code filename*=UTF-8''...}（现代浏览器优先采用，中文名得以保留）。</li>
     * </ul>
     */
    static String buildContentDisposition(String fileName) {
        return buildContentDisposition(fileName, false);
    }

    /**
     * @param inline true → {@code inline}（浏览器直接预览：图片/视频/PDF/文本），
     *               false → {@code attachment}（触发下载）
     */
    static String buildContentDisposition(String fileName, boolean inline) {
        String name = fileName == null ? "download" : fileName;
        String disposition = inline ? "inline" : "attachment";
        return disposition + "; filename=\"" + toAsciiFallback(name) + "\"; filename*=UTF-8''" + rfc5987Encode(name);
    }

    /** 网页端预览用：带 inline=1 时让浏览器直接渲染。 */
    private static boolean wantsInline(IHTTPSession session) {
        if (session == null) return false;
        Map<String, String> parms;
        try {
            parms = session.getParms();
        } catch (Throwable t) {
            return false;
        }
        return parms != null && "1".equals(parms.get("inline"));
    }

    /** 非 ASCII / 会破坏头部的字符全部换成下划线，保证回退名字也是合法 ASCII。 */
    private static String toAsciiFallback(String fileName) {
        StringBuilder sb = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char ch = fileName.charAt(i);
            boolean safe = ch >= 0x20 && ch <= 0x7E && ch != '"' && ch != '\\' && ch != ';';
            sb.append(safe ? ch : '_');
        }
        String fallback = sb.toString().trim();
        return fallback.isEmpty() ? "download" : fallback;
    }

    /** RFC 5987 ext-value：仅 attr-char 可直接出现，其余按 UTF-8 字节百分号编码。 */
    private static String rfc5987Encode(String value) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean attrChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '!' || c == '#' || c == '$' || c == '&' || c == '+' || c == '-'
                    || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~';
            if (attrChar) {
                sb.append((char) c);
            } else {
                sb.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // SAF（Android/data、Android/obb）下载与上传
    // ------------------------------------------------------------------

    /** 通过 ContentResolver 流式下载受限目录里的文件，支持 Range（用跳过字节实现）。 */
    private Response serveSafFileDownload(IHTTPSession session, SafDocuments.Grant grant, String filePath) {
        String fileName = new File(filePath).getName();
        String mimeType = StorageHelper.getMimeType(fileName);
        try {
            long fileLength = SafDocuments.size(grant, filePath);
            if (fileLength < 0) {
                return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File not found");
            }
            String rangeHeader = session != null && session.getHeaders() != null
                    ? session.getHeaders().get("range") : null;
            long start = 0;
            boolean partial = false;
            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0) {
                try {
                    String spec = rangeHeader.substring("bytes=".length()).trim();
                    long end;
                    if (spec.startsWith("-")) {
                        start = Math.max(0, fileLength - Long.parseLong(spec.substring(1)));
                        end = fileLength - 1;
                    } else {
                        String[] parts = spec.split("-", 2);
                        start = parts[0].trim().isEmpty() ? 0 : Long.parseLong(parts[0].trim());
                        end = parts.length > 1 && !parts[1].trim().isEmpty()
                                ? Long.parseLong(parts[1].trim()) : fileLength - 1;
                    }
                    if (start <= end && start < fileLength) {
                        end = Math.min(end, fileLength - 1);
                        InputStream ranged = SafDocuments.openInput(grant, filePath);
                        if (start > 0) {
                            skipFully(ranged, start);
                        }
                        long contentLength = end - start + 1;
                        Response res = newFixedLengthResponse(Status.PARTIAL_CONTENT, mimeType, ranged, contentLength);
                        res.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
                        res.addHeader("Accept-Ranges", "bytes");
                        res.addHeader("Content-Disposition", buildContentDisposition(fileName, wantsInline(session)));
                        return res;
                    }
                    Response res = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain",
                            "Requested Range Not Satisfiable");
                    res.addHeader("Content-Range", "bytes */" + fileLength);
                    return res;
                } catch (NumberFormatException ignored) {
                    // 解析失败则当作完整下载
                }
            }
            InputStream in = SafDocuments.openInput(grant, filePath);
            Response res = newFixedLengthResponse(Status.OK, mimeType, in, fileLength);
            res.addHeader("Accept-Ranges", "bytes");
            res.addHeader("Content-Disposition", buildContentDisposition(fileName, wantsInline(session)));
            return res;
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "SAF 读取失败: " + e.getMessage());
        }
    }

    /** 上传到受限目录（先查重名，再 createDocument + 写入）。 */
    private Response handleSafUpload(IHTTPSession session, SafDocuments.Grant grant, String dirPath) throws Exception {
        if (!SafDocuments.isDirectory(grant, dirPath)) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Invalid target directory");
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
        }
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        Map<String, String> parms = session.getParms();

        List<FileItem> existing = SafDocuments.list(grant, dirPath);
        // 拖入整个文件夹时带的相对目录：在授权范围内逐级创建
        String safRelative = StorageHelper.sanitizeRelativePath(parms.get("relativePath"));
        String uploadDir = dirPath;
        if (safRelative != null) {
            String nested = SafDocuments.ensureDirectory(grant, dirPath, safRelative);
            if (nested == null) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "无法创建目录: " + safRelative);
                return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(error));
            }
            uploadDir = nested;
            existing = SafDocuments.list(grant, uploadDir);
        }
        JsonArray saved = new JsonArray();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String safeName = StorageHelper.sanitizeFileName(parms.get(entry.getKey()));
            if (safeName == null) {
                safeName = "upload_" + System.currentTimeMillis();
            }
            String name = uniqueName(existing, safeName);
            String created = SafDocuments.createFile(grant, uploadDir, name, StorageHelper.getMimeType(name));
            if (created == null) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Write failed: " + name);
                return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(error));
            }
            try (InputStream in = new FileInputStream(new File(entry.getValue()));
                 OutputStream out = SafDocuments.openOutput(grant, created)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Write failed: " + e.getMessage());
                return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(error));
            }
            saved.add(name);
            existing.add(new FileItem(name, null, 0, 0, false, null));
        }
        JsonObject res = new JsonObject();
        res.addProperty("status", "success");
        res.addProperty("dir", uploadDir);
        res.add("saved", saved);
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    /** 已有同名文件时自动加 " (1)"、" (2)"，不覆盖。 */
    private static String uniqueName(List<FileItem> existing, String name) {
        Set<String> names = new HashSet<>();
        for (FileItem item : existing) {
            names.add(item.getName());
        }
        if (!names.contains(name)) return name;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (!names.contains(candidate)) return candidate;
        }
        return stem + "_" + System.currentTimeMillis() + ext;
    }

    /** ContentResolver 的流不支持随机定位，Range 用“读丢弃”实现。 */
    private static void skipFully(InputStream in, long bytes) throws IOException {
        byte[] scratch = new byte[64 * 1024];
        long remaining = bytes;
        while (remaining > 0) {
            int read = in.read(scratch, 0, (int) Math.min(scratch.length, remaining));
            if (read <= 0) break;
            remaining -= read;
        }
    }

    // ------------------------------------------------------------------
    // 统一读取入口（File 通道 / SAF 受限目录通道）
    // ------------------------------------------------------------------

    /** 路径是否可读且是目录。 */
    private boolean isReadableDirectory(String path) {
        if (path == null) return false;
        SafDocuments.Grant grant = SafDocuments.findGrant(path);
        if (grant != null) {
            return SafDocuments.isDirectory(grant, path);
        }
        if (SafDocuments.safTarget(path) != null) {
            return false;   // 受限目录未授权
        }
        File dir = StorageHelper.resolveBrowsableDirectory(path);
        return dir != null && dir.isDirectory();
    }

    /** 列举目录（File 或 SAF）；不可读时返回空列表。 */
    private List<FileItem> readEntries(String path) {
        SafDocuments.Grant grant = SafDocuments.findGrant(path);
        if (grant != null) {
            try {
                return SafDocuments.list(grant, path);
            } catch (Exception e) {
                return new ArrayList<>();
            }
        }
        if (SafDocuments.safTarget(path) != null) {
            return new ArrayList<>();
        }
        File dir = StorageHelper.resolveBrowsableDirectory(path);
        return dir == null ? new ArrayList<>() : StorageHelper.listDirectory(dir);
    }

    /** 打开文件的输入流（File 或 SAF）。 */
    private InputStream openReadStream(String path) throws IOException {
        SafDocuments.Grant grant = SafDocuments.findGrant(path);
        if (grant != null) {
            return SafDocuments.openInput(grant, path);
        }
        File file = new File(path);
        if (!file.isFile() || !StorageHelper.isPathAllowed(file)) {
            throw new IOException("不可读: " + path);
        }
        return new FileInputStream(file);
    }

    // ------------------------------------------------------------------
    // ZIP 打包下载（多选 / 整个目录）
    // ------------------------------------------------------------------

    private static final int ZIP_MAX_DEPTH = 32;
    private static final int ZIP_MAX_ENTRIES = 20000;
    private static final int ZIP_PIPE_SIZE = 256 * 1024;

    /**
     * 把选中的多个文件/目录（目录递归）打包成一个 zip 流式返回。
     * 浏览器只需要下一次下载，不会被“多个下载”拦截；目录也会保留层级结构。
     */
    private Response serveZipDownload(IHTTPSession session) throws Exception {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        // 同时支持两种提交方式：
        //  1) 网页端的表单提交（application/x-www-form-urlencoded，paths 可能重复出现）
        //     —— NanoHTTPD 会把这种 body 解析进 multi-value 参数表，而不是 postData
        //  2) JSON 提交 {"paths":[...]}
        List<String> rawPaths = new ArrayList<>();
        Map<String, List<String>> multiParams = session.getParameters();
        List<String> formPaths = multiParams != null ? multiParams.get("paths") : null;
        if (formPaths != null) {
            for (String value : formPaths) {
                if (value != null && !value.trim().isEmpty()) {
                    rawPaths.add(value.trim());
                }
            }
        }
        if (rawPaths.isEmpty()) {
            String postData = body.get("postData");
            if (postData != null && postData.trim().startsWith("{")) {
                JsonObject json = gson.fromJson(postData.trim(), JsonObject.class);
                JsonArray array = json != null && json.has("paths") ? json.getAsJsonArray("paths") : null;
                if (array != null) {
                    for (int i = 0; i < array.size(); i++) {
                        rawPaths.add(array.get(i).getAsString());
                    }
                }
            }
        }
        if (rawPaths.isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "未选择任何文件");
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
        }

        // 先全部校验，避免开始流式输出后才失败
        final List<String> targets = new ArrayList<>();
        for (String raw : rawPaths) {
            if (isReadableDirectory(raw) || isReadableFile(raw)) {
                targets.add(raw);
            } else {
                JsonObject error = new JsonObject();
                error.addProperty("error", "不可读取: " + raw);
                return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
            }
        }

        final PipedInputStream pipeIn = new PipedInputStream(ZIP_PIPE_SIZE);
        final PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(pipeOut, 64 * 1024))) {
                    Set<String> usedNames = new HashSet<>();
                    int[] counter = new int[]{0};
                    for (String target : targets) {
                        addToZip(zip, target, uniqueEntryName(usedNames, baseName(target)), 0, counter);
                    }
                    zip.finish();
                } catch (Throwable t) {
                    Log.w(TAG, "打包过程中断", t);
                } finally {
                    closeQuietly(pipeOut);
                }
            }
        }, "cs-zip");
        worker.setDaemon(true);
        worker.start();

        Response res = newChunkedResponse(Status.OK, "application/zip", pipeIn);
        res.addHeader("Content-Disposition", buildContentDisposition(zipFileName(), false));
        return res;
    }

    private boolean isReadableFile(String path) {
        if (path == null) return false;
        SafDocuments.Grant grant = SafDocuments.findGrant(path);
        if (grant != null) {
            return !SafDocuments.isDirectory(grant, path) && SafDocuments.exists(grant, path);
        }
        if (SafDocuments.safTarget(path) != null) return false;
        File file = new File(path);
        return file.isFile() && StorageHelper.isPathAllowed(file);
    }

    /** 递归把文件/目录写进 zip。 */
    private void addToZip(ZipOutputStream zip, String path, String entryName, int depth, int[] counter) throws IOException {
        if (depth > ZIP_MAX_DEPTH || counter[0] > ZIP_MAX_ENTRIES) return;
        if (isReadableDirectory(path)) {
            zip.putNextEntry(new ZipEntry(entryName.endsWith("/") ? entryName : entryName + "/"));
            zip.closeEntry();
            Set<String> childNames = new HashSet<>();
            for (FileItem child : readEntries(path)) {
                addToZip(zip, child.getPath(),
                        zipEntryNameForChild(entryName, uniqueEntryName(childNames, child.getName())),
                        depth + 1, counter);
            }
            return;
        }
        if (!isReadableFile(path)) return;
        counter[0]++;
        String suffix = path.toLowerCase();
        boolean storeOnly = ALREADY_COMPRESSED.matcher(suffix).find();
        ZipEntry entry = new ZipEntry(entryName);
        if (storeOnly) {
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(entrySize(path));
            entry.setCompressedSize(entrySize(path));
            entry.setCrc(entryCrc(path));
        }
        zip.putNextEntry(entry);
        try (InputStream in = openReadStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    /** 已压缩格式没必要再压缩（省手机 CPU，快很多）。 */
    private static final java.util.regex.Pattern ALREADY_COMPRESSED = java.util.regex.Pattern.compile(
            "\\.(jpe?g|png|gif|webp|heic|avif|mp4|m4v|mov|avi|mkv|webm|flv|mp3|m4a|aac|ogg|opus|flac|zip|rar|7z|gz|bz2|xz|apk|apks|jar|docx|xlsx|pptx|epub|pdf)$");

    private long entrySize(String path) {
        SafDocuments.Grant grant = SafDocuments.findGrant(path);
        if (grant != null) {
            long size = SafDocuments.size(grant, path);
            return size < 0 ? 0 : size;
        }
        return new File(path).length();
    }

    private long entryCrc(String path) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (InputStream in = openReadStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
        }
        return crc.getValue();
    }

    /** 同一层级里重名时加 " (1)"、" (2)"。 */
    static String uniqueEntryName(Set<String> used, String name) {
        String candidate = name;
        if (!used.contains(candidate)) {
            used.add(candidate);
            return candidate;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            candidate = stem + " (" + i + ")" + ext;
            if (!used.contains(candidate)) {
                used.add(candidate);
                return candidate;
            }
        }
        candidate = stem + "_" + System.currentTimeMillis() + ext;
        used.add(candidate);
        return candidate;
    }

    /**
     * 子条目在 zip 里的完整路径。
     *
     * <p>必须把父目录前缀带上，否则子文件会被放到 zip 根目录（曾经就踩过这个坑：
     * 目录 sub/ 建了，但内容变成根目录的 b.bin）。
     */
    static String zipEntryNameForChild(String parentEntryName, String childName) {
        if (parentEntryName == null || parentEntryName.isEmpty()) return childName;
        return parentEntryName.endsWith("/") ? parentEntryName + childName : parentEntryName + "/" + childName;
    }

    static String baseName(String path) {
        if (path == null) return "download";
        String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        int slash = trimmed.lastIndexOf('/');
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return name.isEmpty() ? "download" : name;
    }

    private static String zipFileName() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US);
        return "ContextShared_" + format.format(new java.util.Date()) + ".zip";
    }

    // ------------------------------------------------------------------
    // 递归搜索文件名
    // ------------------------------------------------------------------

    /**
     * 在指定目录下递归搜文件名（不区分大小写）。
     * 限时（6 秒）+ 限量（默认 200，最多 1000）+ 限深度，避免在大目录树上把手机拖死。
     */
    private Response searchResponse(String rawPath, String rawQuery, String rawLimit, String rawDepth) {
        String query = rawQuery == null ? "" : rawQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "缺少关键字");
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
        }
        String root = rawPath == null || rawPath.trim().isEmpty()
                ? StorageHelper.getBrowseRoot().getAbsolutePath() : rawPath.trim();
        if (!isReadableDirectory(root)) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "目录不可读: " + root);
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
        }
        int limit = 200;
        try {
            if (rawLimit != null) limit = Math.max(1, Math.min(1000, Integer.parseInt(rawLimit.trim())));
        } catch (NumberFormatException ignored) {
        }
        int maxDepth = 8;
        try {
            if (rawDepth != null) maxDepth = Math.max(1, Math.min(32, Integer.parseInt(rawDepth.trim())));
        } catch (NumberFormatException ignored) {
        }

        long deadline = System.currentTimeMillis() + 6000L;
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        List<FileItem> hits = new ArrayList<>();
        int visited = 0;
        boolean truncated = false;
        int rootDepth = root.endsWith("/") ? root.length() : root.length() + 1;

        while (!queue.isEmpty()) {
            if (hits.size() >= limit || System.currentTimeMillis() > deadline) {
                truncated = true;
                break;
            }
            String dir = queue.poll();
            if (dir.length() - rootDepth > maxDepth) {
                continue;
            }
            List<FileItem> entries = readEntries(dir);
            for (FileItem child : entries) {
                visited++;
                if (child.getName().toLowerCase().contains(query)) {
                    hits.add(child);
                    if (hits.size() >= limit) break;
                }
                if (child.isDirectory()) {
                    queue.add(child.getPath());
                }
            }
        }

        JsonObject res = new JsonObject();
        res.addProperty("query", rawQuery);
        res.addProperty("root", root);
        res.addProperty("visited", visited);
        res.addProperty("truncated", truncated);
        res.add("entries", gson.toJsonTree(hits));
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    private Response getDeviceInfoResponse() {
        BatteryManager bm = context != null
                ? (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE) : null;
        int battery = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 100;
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long freeBytes = stat.getAvailableBytes();
        long totalBytes = stat.getTotalBytes();

        DeviceInfo info = new DeviceInfo(
                Build.MANUFACTURER + " " + Build.MODEL,
                battery,
                false,
                freeBytes,
                totalBytes,
                sessionManager.isAuthRequired(),
                StorageHelper.hasAllFilesAccess(),
                StorageHelper.getBrowseRoot().getAbsolutePath()
        );
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(info));
    }

    /**
     * 目录浏览：返回当前目录、所属存储卷根、上级目录信息与条目列表。
     * 路径校验（越界/Android\u002fdata|obb/不存在）统一由 StorageHelper 完成。
     */
    private Response listDirectoryResponse(String rawPath) {
        // ① 已授权（SAF）的受限目录：Android/data、Android/obb
        SafDocuments.Grant grant = SafDocuments.findGrant(rawPath);
        if (grant != null) {
            return safListingResponse(grant, rawPath);
        }
        // ② 受限目录但还没授权：明确告诉前端去手机上授权，而不是给一个含糊的 400
        String safTarget = SafDocuments.safTarget(rawPath);
        if (safTarget != null) {
            JsonObject need = new JsonObject();
            need.addProperty("needAuth", true);
            need.addProperty("target", safTarget);
            need.addProperty("path", rawPath);
            need.addProperty("supported", SafDocuments.isSupported());
            need.addProperty("error", SafDocuments.isSupported()
                    ? "Android/" + safTarget + " 受系统保护，需在手机上用系统文件选择器授权一次"
                    : "当前系统版本不允许第三方应用访问该目录");
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(need));
        }

        File dir = StorageHelper.resolveBrowsableDirectory(rawPath);
        if (dir == null) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Invalid path or not accessible");
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
        }
        File volumeRoot = StorageHelper.findVolumeRoot(dir);
        if (volumeRoot == null) {
            volumeRoot = StorageHelper.getBrowseRoot();
        }
        File parent = dir.getParentFile();
        boolean canGoUp = !dir.equals(volumeRoot) && parent != null
                && StorageHelper.resolveBrowsableDirectory(parent.getAbsolutePath()) != null;

        JsonObject res = new JsonObject();
        res.addProperty("path", dir.getAbsolutePath());
        // root 保留为旧字段（当前卷根），新增 volumeRoot 语义更清晰
        res.addProperty("root", volumeRoot.getAbsolutePath());
        res.addProperty("volumeRoot", volumeRoot.getAbsolutePath());
        res.addProperty("hasAllFilesAccess", StorageHelper.hasAllFilesAccess());
        res.addProperty("canGoUp", canGoUp);
        if (canGoUp && parent != null) {
            res.addProperty("parent", parent.getAbsolutePath());
        }
        res.add("entries", gson.toJsonTree(StorageHelper.listDirectory(dir)));
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    /** SAF 授权列表（网页端轮询用）。 */
    private Response listGrantsResponse() {
        JsonArray array = new JsonArray();
        for (SafDocuments.Grant grant : SafDocuments.getGrants()) {
            JsonObject item = new JsonObject();
            item.addProperty("path", grant.mountPoint.getAbsolutePath());
            item.addProperty("relative", grant.relativePath);
            item.addProperty("target", grant.relativePath.substring("Android/".length()));
            array.add(item);
        }
        JsonObject res = new JsonObject();
        res.addProperty("supported", SafDocuments.isSupported());
        res.add("grants", array);
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    /** 网页端请求授权：让手机弹出系统文件选择器（用户点“使用此文件夹”→“允许”）。 */
    private Response requestSafAccess(String rawTarget) {
        String target = "obb".equalsIgnoreCase(rawTarget) ? "obb" : "data";
        JsonObject res = new JsonObject();
        if (!SafDocuments.isSupported()) {
            res.addProperty("status", "unsupported");
            res.addProperty("hint", "当前系统版本已禁止第三方应用访问该目录（Android 13 起堵掉了这个途径）");
            return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        }
        if (SafDocuments.hasGrantFor(target)) {
            res.addProperty("status", "granted");
            return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        }
        SafDocuments.requestAccess(target);
        res.addProperty("status", "requested");
        res.addProperty("hint", "请在手机上选择当前文件夹并允许授权");
        // 尽力把应用带到前台；Android 10+ 后台启动 Activity 可能被拒，
        // 此时用户手动打开一次 App 也会消费该请求（见 MainActivity.onResume）。
        if (context != null) {
            try {
                Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(launch);
                }
            } catch (Throwable ignored) {
            }
        }
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    /** SAF（受限目录）的目录列举。 */
    private Response safListingResponse(SafDocuments.Grant grant, String rawPath) {
        // 先判断是否存在且为目录：否则 ContentResolver 会抛 FileNotFoundException（应给 404 而不是 500）
        if (!SafDocuments.isDirectory(grant, rawPath)) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "目录不存在或不可访问");
            return newFixedLengthResponse(Status.NOT_FOUND, "application/json", gson.toJson(error));
        }
        List<FileItem> entries;
        try {
            entries = SafDocuments.list(grant, rawPath);
        } catch (Exception e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "读取失败: " + e.getMessage());
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", gson.toJson(error));
        }
        File mountPoint = grant.mountPoint;
        File current = new File(rawPath);
        String parent = current.getParent();
        boolean canGoUp = parent != null && !current.equals(mountPoint);

        JsonObject res = new JsonObject();
        res.addProperty("path", rawPath);
        res.addProperty("root", mountPoint.getAbsolutePath());
        res.addProperty("volumeRoot", mountPoint.getAbsolutePath());
        res.addProperty("saf", true);
        res.addProperty("hasAllFilesAccess", StorageHelper.hasAllFilesAccess());
        res.addProperty("canGoUp", canGoUp);
        if (canGoUp) {
            res.addProperty("parent", parent);
        }
        res.add("entries", gson.toJsonTree(entries));
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    /** 可浏览的存储卷列表：内部存储 + 存在的外置存储（SD 卡 / U 盘）。 */
    private Response listVolumesResponse() {
        JsonArray volumes = new JsonArray();
        for (StorageHelper.Volume volume : StorageHelper.getVolumes()) {
            JsonObject item = new JsonObject();
            item.addProperty("path", volume.getPath());
            item.addProperty("removable", volume.isRemovable());
            if (volume.getLabel() != null && !volume.getLabel().isEmpty()) {
                item.addProperty("label", volume.getLabel());
            }
            volumes.add(item);
        }
        JsonObject res = new JsonObject();
        res.addProperty("hasAllFilesAccess", StorageHelper.hasAllFilesAccess());
        res.add("volumes", volumes);
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    /** 请在手机端弹出「所有文件访问权限」设置页；受后台启动 Activity 限制时可能失败，不影响其他功能。 */
    private Response requestAllFilesAccess() {
        JsonObject res = new JsonObject();
        if (StorageHelper.hasAllFilesAccess()) {
            res.addProperty("status", "granted");
            return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            res.addProperty("status", "not_required");
            return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        }
        if (context == null) {
            res.addProperty("status", "failed");
            return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            res.addProperty("status", "settings_opened");
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
                res.addProperty("status", "settings_opened");
            } catch (Exception ex) {
                res.addProperty("status", "failed");
                res.addProperty("message", "请在手机上打开应用，点『去授权』");
            }
        }
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    private Response verifyAuth(IHTTPSession session) throws Exception {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String postData = body.get("postData");
        JsonObject json = gson.fromJson(postData, JsonObject.class);
        String pin = json != null && json.has("pin") ? json.get("pin").getAsString() : "";

        if (sessionManager.verifyPin(pin)) {
            String token = sessionManager.createSession();
            JsonObject res = new JsonObject();
            res.addProperty("status", "success");
            res.addProperty("token", token);
            return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        } else {
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json", "{\"status\":\"error\",\"message\":\"Invalid PIN\"}");
        }
    }

    Response serveFileDownload(IHTTPSession session, String filePath) {
        if (filePath == null) return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing path");
        // Android/data、Android/obb：走 SAF 通道（File API 读不到）
        SafDocuments.Grant safGrant = SafDocuments.findGrant(filePath);
        if (safGrant != null) {
            return serveSafFileDownload(session, safGrant, filePath);
        }
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File not found");
        }
        if (!StorageHelper.isPathAllowed(file)) {
            return newFixedLengthResponse(Status.FORBIDDEN, "text/plain", "Forbidden: Access denied");
        }
        try {
            long fileLength = file.length();
            String mimeType = StorageHelper.getMimeType(file.getName());

            String rangeHeader = null;
            if (session != null && session.getHeaders() != null) {
                rangeHeader = session.getHeaders().get("range");
                if (rangeHeader == null) {
                    rangeHeader = session.getHeaders().get("Range");
                }
            }

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String rangeSpec = rangeHeader.substring("bytes=".length()).trim();
                long start = 0;
                long end = fileLength - 1;
                boolean valid = false;

                try {
                    if (rangeSpec.startsWith("-")) {
                        long suffix = Long.parseLong(rangeSpec.substring(1));
                        start = Math.max(0, fileLength - suffix);
                        end = fileLength - 1;
                        valid = true;
                    } else {
                        String[] parts = rangeSpec.split("-", 2);
                        if (parts.length > 0 && !parts[0].trim().isEmpty()) {
                            start = Long.parseLong(parts[0].trim());
                        }
                        if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                            end = Long.parseLong(parts[1].trim());
                        }
                        valid = true;
                    }
                } catch (NumberFormatException ignored) {
                    valid = false;
                }

                if (valid && start <= end && start < fileLength) {
                    if (end >= fileLength) {
                        end = fileLength - 1;
                    }
                    long contentLength = end - start + 1;
                    FileInputStream fis = new FileInputStream(file);
                    if (start > 0) {
                        long skipped = 0;
                        while (skipped < start) {
                            long s = fis.skip(start - skipped);
                            if (s <= 0) break;
                            skipped += s;
                        }
                    }
                    Response res = newFixedLengthResponse(Status.PARTIAL_CONTENT, mimeType, fis, contentLength);
                    res.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + fileLength);
                    res.addHeader("Accept-Ranges", "bytes");
                    res.addHeader("Content-Length", String.valueOf(contentLength));
                    res.addHeader("Content-Disposition", buildContentDisposition(file.getName(), wantsInline(session)));
                    return res;
                } else {
                    Response res = newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "Requested Range Not Satisfiable");
                    res.addHeader("Content-Range", "bytes */" + fileLength);
                    return res;
                }
            }

            FileInputStream fis = new FileInputStream(file);
            Response res = newFixedLengthResponse(Status.OK, mimeType, fis, fileLength);
            res.addHeader("Accept-Ranges", "bytes");
            res.addHeader("Content-Length", String.valueOf(fileLength));
            res.addHeader("Content-Disposition", buildContentDisposition(file.getName(), wantsInline(session)));
            return res;
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleFileUpload(IHTTPSession session, String rawPath) throws Exception {
        // Android/data、Android/obb：走 SAF 通道
        SafDocuments.Grant safGrant = SafDocuments.findGrant(rawPath);
        if (safGrant != null) {
            return handleSafUpload(session, safGrant, rawPath);
        }
        File targetDir;
        if (rawPath == null || rawPath.trim().isEmpty()) {
            // 旧客户端不带 path：仍写入默认共享目录
            targetDir = StorageHelper.getSharedStorageDir();
        } else {
            targetDir = StorageHelper.resolveBrowsableDirectory(rawPath);
        }
        if (targetDir == null || !targetDir.isDirectory()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Invalid target directory");
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", gson.toJson(error));
        }
        if (!targetDir.canWrite()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Target directory is not writable. Grant \"All files access\" on the phone.");
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(error));
        }

        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        Map<String, String> parms = session.getParms();

        // 拖入整个文件夹时带的相对目录：逐级创建，保留目录结构
        String relative = StorageHelper.sanitizeRelativePath(parms.get("relativePath"));
        if (relative != null) {
            File nested = new File(targetDir, relative);
            if (!nested.exists() && !nested.mkdirs()) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "无法创建目录: " + relative);
                return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(error));
            }
            targetDir = nested;
        }

        JsonArray saved = new JsonArray();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String tempFilePath = entry.getValue();
            String safeName = StorageHelper.sanitizeFileName(parms.get(entry.getKey()));
            if (safeName == null) {
                safeName = "upload_" + System.currentTimeMillis();
            }
            File target = StorageHelper.buildUploadTarget(targetDir, safeName);
            File tempFile = new File(tempFilePath);
            try (InputStream in = new FileInputStream(tempFile)) {
                StorageHelper.saveStreamToFile(context, in, target);
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("error", "Write failed: " + e.getMessage());
                return newFixedLengthResponse(Status.FORBIDDEN, "application/json", gson.toJson(error));
            }
            saved.add(target.getName());
        }

        JsonObject res = new JsonObject();
        res.addProperty("status", "success");
        res.addProperty("dir", targetDir.getAbsolutePath());
        res.add("saved", saved);
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
    }

    private Response handleOpenUrl(IHTTPSession session) throws Exception {
        Map<String, String> body = new HashMap<>();
        session.parseBody(body);
        String postData = body.get("postData");
        JsonObject json = gson.fromJson(postData, JsonObject.class);
        String url = json != null && json.has("url") ? json.get("url").getAsString() : "";

        if (url != null && !url.isEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"success\"}");
        }
        return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"status\":\"error\"}");
    }
}
