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
import com.lll.contextshared.util.StorageHelper;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoWSD;
import fi.iki.elonen.NanoWSD.WebSocket;
import fi.iki.elonen.NanoWSD.WebSocketFrame;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
            } else if ("/api/files/list".equals(uri) && Method.GET.equals(method)) {
                // 旧客户端只带 category（返回裸数组）；新客户端带 path（返回带目录元信息的对象）
                if (params.get("path") == null && params.get("category") != null) {
                    List<FileItem> list = StorageHelper.listFiles(params.get("category"));
                    return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(list));
                }
                return listDirectoryResponse(params.get("path"));
            } else if ("/api/files/download".equals(uri) && Method.GET.equals(method)) {
                return serveFileDownload(session, params.get("path"));
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
        String name = fileName == null ? "download" : fileName;
        return "attachment; filename=\"" + toAsciiFallback(name) + "\"; filename*=UTF-8''" + rfc5987Encode(name);
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
                    res.addHeader("Content-Disposition", buildContentDisposition(file.getName()));
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
            res.addHeader("Content-Disposition", buildContentDisposition(file.getName()));
            return res;
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleFileUpload(IHTTPSession session, String rawPath) throws Exception {
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
