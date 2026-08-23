package com.lll.contextshared.server;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.lll.contextshared.model.WsMessage;

public class ServerComponentsTest {
    private SessionManager sessionManager;
    private AppWebSocketServer wsServer;

    @Before
    public void setUp() {
        sessionManager = new SessionManager();
        wsServer = new AppWebSocketServer(9090, sessionManager);
    }

    @Test
    public void testWebSocketServerInitializationAndBroadcast() {
        assertEquals(0, wsServer.getConnectedCount());
        
        final boolean[] received = {false};
        wsServer.setMessageListener(text -> {
            if ("test-text".equals(text)) {
                received[0] = true;
            }
        });

        // Broadcast to empty client list should not throw exception
        wsServer.broadcast(new WsMessage("PING", "ping"));
        assertEquals(0, wsServer.getConnectedCount());
    }

    @Test
    public void testWebSocketAuthGating() throws Exception {
        sessionManager.setAuthRequired(true);
        String validToken = sessionManager.createSession();

        // 1. Unauthenticated client with no token or invalid token
        java.util.Map<String, String> invalidParms = new java.util.HashMap<>();
        invalidParms.put("token", "invalid_token");
        fi.iki.elonen.NanoHTTPD.IHTTPSession invalidSession = (fi.iki.elonen.NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(
                fi.iki.elonen.NanoHTTPD.IHTTPSession.class.getClassLoader(),
                new Class<?>[]{fi.iki.elonen.NanoHTTPD.IHTTPSession.class},
                (proxy, method, args) -> "getParms".equals(method.getName()) ? invalidParms : null
        );

        AppWebSocketServer.ContextWebSocket unauthClient = (AppWebSocketServer.ContextWebSocket) wsServer.openWebSocket(invalidSession);
        assertFalse(unauthClient.isAuthenticated());

        final boolean[] received = {false};
        wsServer.setMessageListener(text -> received[0] = true);

        // Send CLIPBOARD_SEND from unauthenticated client
        fi.iki.elonen.NanoWSD.WebSocketFrame clipFrame = new fi.iki.elonen.NanoWSD.WebSocketFrame(
                fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Text,
                true,
                "{\"type\":\"CLIPBOARD_SEND\",\"payload\":\"unauthorized-data\"}"
        );
        unauthClient.onMessage(clipFrame);
        assertFalse("Unauthenticated client should not be allowed to send clipboard data", received[0]);

        // 2. Authenticated client
        java.util.Map<String, String> validParms = new java.util.HashMap<>();
        validParms.put("token", validToken);
        fi.iki.elonen.NanoHTTPD.IHTTPSession validSession = (fi.iki.elonen.NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(
                fi.iki.elonen.NanoHTTPD.IHTTPSession.class.getClassLoader(),
                new Class<?>[]{fi.iki.elonen.NanoHTTPD.IHTTPSession.class},
                (proxy, method, args) -> "getParms".equals(method.getName()) ? validParms : null
        );

        AppWebSocketServer.ContextWebSocket authClient = (AppWebSocketServer.ContextWebSocket) wsServer.openWebSocket(validSession);
        assertTrue(authClient.isAuthenticated());

        // Send CLIPBOARD_SEND from authenticated client
        fi.iki.elonen.NanoWSD.WebSocketFrame validClipFrame = new fi.iki.elonen.NanoWSD.WebSocketFrame(
                fi.iki.elonen.NanoWSD.WebSocketFrame.OpCode.Text,
                true,
                "{\"type\":\"CLIPBOARD_SEND\",\"payload\":\"authorized-data\"}"
        );
        authClient.onMessage(validClipFrame);
        assertTrue("Authenticated client should be allowed to send clipboard data", received[0]);
    }

    @Test
    public void testSessionManagerSecurity() {
        sessionManager.setAuthRequired(true);
        String pin = sessionManager.getPinCode();
        assertFalse(sessionManager.verifyPin("0000".equals(pin) ? "1111" : "0000"));
        assertTrue(sessionManager.verifyPin(pin));

        String token = sessionManager.createSession();
        assertTrue(sessionManager.isValidToken(token));
        sessionManager.invalidateToken(token);
        assertFalse(sessionManager.isValidToken(token));
    }
}
