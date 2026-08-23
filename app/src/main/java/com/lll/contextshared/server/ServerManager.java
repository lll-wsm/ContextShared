package com.lll.contextshared.server;

import android.content.Context;

import com.lll.contextshared.model.DeviceInfo;
import com.lll.contextshared.model.WsMessage;
import com.lll.contextshared.util.ClipboardWatcher;
import com.lll.contextshared.util.NetworkUtils;

import java.io.IOException;

public class ServerManager {
    public interface ServerStateListener {
        void onServerStarted(String ip, int httpPort, int wsPort);
        void onServerStopped();
        void onLog(String log);
    }

    private final Context context;
    private final SessionManager sessionManager;
    private final ClipboardWatcher clipboardWatcher;
    private AppHttpServer httpServer;
    private AppWebSocketServer wsServer;
    private int httpPort = 8080;
    private int wsPort = 8081;
    private boolean isRunning = false;
    private ServerStateListener stateListener;

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

        this.httpPort = NetworkUtils.findAvailablePort(preferredPort);
        this.wsPort = NetworkUtils.findAvailablePort(this.httpPort + 1);

        httpServer = new AppHttpServer(context, httpPort, sessionManager);
        wsServer = new AppWebSocketServer(wsPort, sessionManager);

        wsServer.setMessageListener(text -> {
            clipboardWatcher.setPrimaryClipText(text);
            if (stateListener != null) {
                stateListener.onLog("收到来自 Web 端的剪贴板内容已写入手机");
            }
        });

        httpServer.start();
        wsServer.start();
        clipboardWatcher.startWatching();

        isRunning = true;
        String ip = NetworkUtils.getLocalIpAddress(context);
        if (stateListener != null) {
            stateListener.onServerStarted(ip, httpPort, wsPort);
            stateListener.onLog("服务已启动: http://" + ip + ":" + httpPort);
        }
    }

    public synchronized void stopServer() {
        if (!isRunning) return;
        if (httpServer != null) httpServer.stop();
        if (wsServer != null) wsServer.stop();
        if (clipboardWatcher != null) clipboardWatcher.stopWatching();
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
        return httpPort;
    }

    public int getHttpPort() {
        return httpPort;
    }

    public int getWsPort() {
        return wsPort;
    }

    public void broadcastClipboard(String text) {
        if (wsServer != null && isRunning) {
            wsServer.broadcast(new WsMessage("CLIPBOARD_PUSH", text));
        }
    }

    public void broadcastDeviceStatus(DeviceInfo info) {
        if (wsServer != null && isRunning) {
            wsServer.broadcast(new WsMessage("DEVICE_STATUS", info));
        }
    }
}
