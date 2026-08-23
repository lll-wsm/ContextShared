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
