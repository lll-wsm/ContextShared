package com.lll.contextshared.server;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.lll.contextshared.model.DeviceInfo;
import com.lll.contextshared.model.FileItem;
import com.lll.contextshared.model.WsMessage;

public class SessionManagerTest {
    private SessionManager sessionManager;

    @Before
    public void setUp() {
        sessionManager = new SessionManager();
    }

    @Test
    public void testPinGenerationAndVerification() {
        String pin = sessionManager.getPinCode();
        assertNotNull(pin);
        assertEquals(4, pin.length());
        assertTrue(sessionManager.verifyPin(pin));
        assertTrue(sessionManager.verifyPin(" " + pin + " "));
        assertFalse(sessionManager.verifyPin("wrong-pin"));
        assertFalse(sessionManager.verifyPin(null));
    }

    @Test
    public void testRefreshPinCode() {
        String pin1 = sessionManager.getPinCode();
        String pin2 = sessionManager.refreshPinCode();
        assertEquals(pin2, sessionManager.getPinCode());
        assertEquals(4, pin2.length());
        assertTrue(sessionManager.verifyPin(pin2));
    }

    @Test
    public void testSessionTokenLifecycle() {
        String token = sessionManager.createSession();
        assertNotNull(token);
        assertTrue(sessionManager.isValidToken(token));

        sessionManager.invalidateToken(token);
        assertFalse(sessionManager.isValidToken(token));
        assertFalse(sessionManager.isValidToken(null));
    }

    @Test
    public void testAuthNotRequired() {
        sessionManager.setAuthRequired(false);
        assertFalse(sessionManager.isAuthRequired());
        assertTrue(sessionManager.verifyPin("any-pin"));
        assertTrue(sessionManager.isValidToken("any-token"));
        assertTrue(sessionManager.isValidToken(null));
    }

    @Test
    public void testWsMessageSerialization() {
        WsMessage msg = new WsMessage("CLIPBOARD_PUSH", "hello world");
        assertEquals("CLIPBOARD_PUSH", msg.getType());
        assertEquals("hello world", msg.getPayload());
        assertTrue(msg.getTimestamp() > 0);
        String json = msg.toJson();
        assertTrue(json.contains("CLIPBOARD_PUSH"));
        assertTrue(json.contains("hello world"));

        WsMessage parsed = WsMessage.fromJson(json);
        assertNotNull(parsed);
        assertEquals("CLIPBOARD_PUSH", parsed.getType());
        assertEquals("hello world", parsed.getPayload());
        assertEquals(msg.getTimestamp(), parsed.getTimestamp());
    }

    @Test
    public void testDeviceInfoAndFileItemModels() {
        DeviceInfo device = new DeviceInfo("TestDevice", 85, true, 1000L, 5000L, true);
        assertEquals("TestDevice", device.getDeviceName());
        assertEquals(85, device.getBatteryLevel());
        assertTrue(device.isCharging());
        assertEquals(1000L, device.getFreeStorageBytes());
        assertEquals(5000L, device.getTotalStorageBytes());
        assertTrue(device.isAuthRequired());

        FileItem fileItem = new FileItem("test.txt", "/sdcard/test.txt", 1234L, 9999L, false, "text/plain");
        assertEquals("test.txt", fileItem.getName());
        assertEquals("/sdcard/test.txt", fileItem.getPath());
        assertEquals(1234L, fileItem.getSize());
        assertEquals(9999L, fileItem.getLastModified());
        assertFalse(fileItem.isDirectory());
        assertEquals("text/plain", fileItem.getMimeType());
    }
}
