package com.lll.contextshared.server;

import com.google.gson.Gson;
import com.lll.contextshared.model.WsMessage;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoWSD;
import fi.iki.elonen.NanoWSD.WebSocketFrame.CloseCode;

import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AppWebSocketServer extends NanoWSD {
    public interface OnMessageReceivedListener {
        void onClipboardReceived(String text);
    }

    private final SessionManager sessionManager;
    private final Set<ContextWebSocket> connectedClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private OnMessageReceivedListener messageListener;
    private final Gson gson = new Gson();

    public AppWebSocketServer(int port, SessionManager sessionManager) {
        super(port);
        this.sessionManager = sessionManager;
    }

    public void setMessageListener(OnMessageReceivedListener messageListener) {
        this.messageListener = messageListener;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new ContextWebSocket(handshake);
    }

    public void broadcast(WsMessage message) {
        String json = message.toJson();
        for (ContextWebSocket client : connectedClients) {
            if (client.isOpen()) {
                try {
                    client.send(json);
                } catch (IOException ignored) {}
            }
        }
    }

    public int getConnectedCount() {
        return connectedClients.size();
    }

    private class ContextWebSocket extends WebSocket {
        private boolean authenticated = false;

        public ContextWebSocket(IHTTPSession handshakeRequest) {
            super(handshakeRequest);
            String token = handshakeRequest.getParms().get("token");
            this.authenticated = sessionManager.isValidToken(token);
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
                if (wsMsg != null) {
                    if ("CLIPBOARD_SEND".equals(wsMsg.getType())) {
                        String text = wsMsg.getPayload() != null ? wsMsg.getPayload().toString() : "";
                        if (messageListener != null) {
                            messageListener.onClipboardReceived(text);
                        }
                    } else if ("PING".equals(wsMsg.getType())) {
                        send(new WsMessage("PONG", "pong").toJson());
                    }
                }
            } catch (Exception ignored) {}
        }

        @Override
        protected void onPong(WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            connectedClients.remove(this);
        }
    }
}
