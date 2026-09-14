package com.lll.contextshared.server;

import android.content.Context;

import com.lll.contextshared.model.DeviceInfo;
import com.lll.contextshared.model.WsMessage;
import com.lll.contextshared.util.ClipboardWatcher;
import com.lll.contextshared.util.NetworkUtils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerManager {
    public interface ServerStateListener {
        void onServerStarted(String ip, int httpPort, int wsPort);
        void onServerStopped();
        void onLog(String log);
    }

    private final Context context;
    private final SessionManager sessionManager;
    private final ClipboardWatcher clipboardWatcher;
    /**
     * 空闲连接的超时时间。
     *
     * <p>不能用 NanoHTTPD 的默认值 {@code SOCKET_READ_TIMEOUT = 5000}：那个超时同时作用于
     * WebSocket 连接，而 NanoWSD 的 {@code readWebsocket()} 把 SocketTimeoutException 当作连接
     * 错误，会把空闲的连接直接关掉。实测：连上后不发数据，<b>t=5.0s 服务端就关闭了连接</b>，
     * 于是网页端一直在"连上→5 秒断开→2 秒重连"循环，剪贴板广播经常发出去时客户端不在线。
     *
     * <p>网页端每 15 秒发一次 PING（后台标签页会被浏览器限流到约 60 秒），所以 5 分钟足够
     * 保持长连接，同时又能回收真正死掉的连接。
     */
    private static final int SOCKET_READ_TIMEOUT_MS = 5 * 60 * 1000;
    /** 单端口同时承载 HTTP 与 WebSocket（旧实现是 8080 + 8081 两个实例，客户端连错端口导致无限重连）。 */
    private AppHttpServer server;
    private int port = 8080;
    private boolean isRunning = false;
    private ServerStateListener stateListener;
    /**
     * 广播专用线程。
     *
     * <p>必须离开主线程：读剪贴板的时机是 Activity 的 onWindowFocusChanged/onResume（主线程），
     * 而广播要写 WebSocket socket。在 Android 上主线程做网络 I/O 会抛
     * {@code NetworkOnMainThreadException}，导致"内容读到了但发不出去"。
     */
    private volatile ExecutorService broadcastExecutor;

    public ServerManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessionManager = new SessionManager();
        this.clipboardWatcher = new ClipboardWatcher(context);

        this.clipboardWatcher.setListener(text -> {
            broadcastClipboard(text);
            if (stateListener != null) {
                stateListener.onLog("手机剪贴板更新已同步至 Web");
            }
        });
    }

    public void setStateListener(ServerStateListener stateListener) {
        this.stateListener = stateListener;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public synchronized void startServer() throws IOException {
        startServer(8080);
    }

    public synchronized void startServer(int preferredPort) throws IOException {
        if (isRunning) return;

        this.port = NetworkUtils.findAvailablePort(preferredPort);
        this.broadcastExecutor = Executors.newSingleThreadExecutor();

        server = new AppHttpServer(context, port, sessionManager);
        server.setMessageListener(text -> {
            clipboardWatcher.setPrimaryClipText(text);
            if (stateListener != null) {
                stateListener.onLog("收到来自 Web 端的剪贴板内容已写入手机");
            }
        });

        server.start(SOCKET_READ_TIMEOUT_MS, true);
        clipboardWatcher.startWatching();

        isRunning = true;
        String ip = NetworkUtils.getLocalIpAddress(context);
        if (stateListener != null) {
            stateListener.onServerStarted(ip, port, port);
            stateListener.onLog("服务已启动: http://" + ip + ":" + port);
        }
    }

    public synchronized void stopServer() {
        if (!isRunning) return;
        if (server != null) server.stop();
        if (clipboardWatcher != null) clipboardWatcher.stopWatching();
        ExecutorService executor = broadcastExecutor;
        broadcastExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
        isRunning = false;
        if (stateListener != null) {
            stateListener.onServerStopped();
            stateListener.onLog("服务已停止");
        }
    }

    public boolean isRunning() {
        return isRunning;
    }

    public int getPort() {
        return port;
    }

    public int getHttpPort() {
        return port;
    }

    /** HTTP 与 WebSocket 同端口，保留该方法是为了兼容既有调用方。 */
    public int getWsPort() {
        return port;
    }

    public void broadcastClipboard(final String text) {
        ExecutorService executor = broadcastExecutor;
        if (server == null || !isRunning || executor == null) {
            return;
        }
        final AppHttpServer target = server;
        executor.execute(() -> {
            try {
                target.broadcast(new WsMessage("CLIPBOARD_PUSH", text));
            } catch (Throwable t) {
                android.util.Log.w("ContextShared", "广播剪贴板失败", t);
            }
        });
    }

    /**
     * 应用回到前台时立即同步一次手机剪贴板。
     *
     * <p>Android 10+ 只有应用在前台/有焦点时才能读剪贴板，后台读取会被系统拒绝，
     * 因此手机→电脑的同步依赖这个时机。
     */
    public void pollClipboardNow() {
        if (isRunning && clipboardWatcher != null) {
            clipboardWatcher.pollNow();
        }
    }

    /** 把一条消息写进手机端的“传输动态”列表。 */
    public void logToListener(String message) {
        if (stateListener != null && message != null) {
            stateListener.onLog(message);
        }
    }

    public void broadcastDeviceStatus(final DeviceInfo info) {
        ExecutorService executor = broadcastExecutor;
        if (server == null || !isRunning || executor == null) {
            return;
        }
        final AppHttpServer target = server;
        executor.execute(() -> {
            try {
                target.broadcast(new WsMessage("DEVICE_STATUS", info));
            } catch (Throwable t) {
                android.util.Log.w("ContextShared", "广播设备状态失败", t);
            }
        });
    }
}
