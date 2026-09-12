package com.lll.contextshared.server;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

import com.lll.contextshared.model.WsMessage;

public class ServerComponentsTest {
    private SessionManager sessionManager;
    private AppHttpServer wsServer;

    @Before
    public void setUp() {
        sessionManager = new SessionManager();
        // 单端口服务器：HTTP 与 WebSocket 由同一个 AppHttpServer 承载
        wsServer = new AppHttpServer(null, 9090, sessionManager);
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

        AppHttpServer.ContextWebSocket unauthClient = (AppHttpServer.ContextWebSocket) wsServer.openWebSocket(invalidSession);
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

        AppHttpServer.ContextWebSocket authClient = (AppHttpServer.ContextWebSocket) wsServer.openWebSocket(validSession);
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

    @Test
    public void testStorageHelperPathTraversalProtection() throws java.io.IOException {
        java.io.File tempDir = java.io.File.createTempFile("cs_test_root", "");
        tempDir.delete();
        tempDir.mkdirs();

        java.io.File outsideDir = java.io.File.createTempFile("cs_outside_root", "");
        outsideDir.delete();
        outsideDir.mkdirs();

        try {
            com.lll.contextshared.util.StorageHelper.setExtraAllowedRoots(java.util.Collections.singletonList(tempDir));

            java.io.File allowedFile = new java.io.File(tempDir, "allowed.txt");
            allowedFile.createNewFile();
            assertTrue(com.lll.contextshared.util.StorageHelper.isPathAllowed(allowedFile));

            java.io.File subFile = new java.io.File(new java.io.File(tempDir, "sub"), "nested.txt");
            subFile.getParentFile().mkdirs();
            subFile.createNewFile();
            assertTrue(com.lll.contextshared.util.StorageHelper.isPathAllowed(subFile));

            java.io.File outsideFile = new java.io.File(outsideDir, "secret.txt");
            outsideFile.createNewFile();
            assertFalse(com.lll.contextshared.util.StorageHelper.isPathAllowed(outsideFile));

            java.io.File traversalFile = new java.io.File(tempDir, "../" + outsideDir.getName() + "/secret.txt");
            assertFalse(com.lll.contextshared.util.StorageHelper.isPathAllowed(traversalFile));

            assertFalse(com.lll.contextshared.util.StorageHelper.isPathAllowed(null));
        } finally {
            com.lll.contextshared.util.StorageHelper.setExtraAllowedRoots(null);
            deleteRecursively(tempDir);
            deleteRecursively(outsideDir);
        }
    }

    @Test
    public void testHttpServerFileDownload_RangeAndSecurity() throws java.io.IOException {
        java.io.File tempDir = java.io.File.createTempFile("cs_http_root", "");
        tempDir.delete();
        tempDir.mkdirs();

        java.io.File outsideDir = java.io.File.createTempFile("cs_http_outside", "");
        outsideDir.delete();
        outsideDir.mkdirs();

        try {
            com.lll.contextshared.util.StorageHelper.setExtraAllowedRoots(java.util.Collections.singletonList(tempDir));
            AppHttpServer httpServer = new AppHttpServer(null, 8080, sessionManager);

            // 1. Create a 100-byte test file
            java.io.File testFile = new java.io.File(tempDir, "video.mp4");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(testFile)) {
                byte[] bytes = new byte[100];
                for (int i = 0; i < 100; i++) bytes[i] = (byte) i;
                fos.write(bytes);
            }

            // 2. Test Path Traversal Blocked -> 403 FORBIDDEN
            java.io.File outsideFile = new java.io.File(outsideDir, "secret.txt");
            outsideFile.createNewFile();
            fi.iki.elonen.NanoHTTPD.Response resForbidden = httpServer.serveFileDownload(null, outsideFile.getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.FORBIDDEN.getRequestStatus(), resForbidden.getStatus().getRequestStatus());

            // 3. Test Missing & Not Found
            fi.iki.elonen.NanoHTTPD.Response resMissing = httpServer.serveFileDownload(null, null);
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.BAD_REQUEST.getRequestStatus(), resMissing.getStatus().getRequestStatus());

            fi.iki.elonen.NanoHTTPD.Response resNotFound = httpServer.serveFileDownload(null, new java.io.File(tempDir, "none.mp4").getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.NOT_FOUND.getRequestStatus(), resNotFound.getStatus().getRequestStatus());

            // 4. Test Normal Full Download -> 200 OK with Accept-Ranges
            fi.iki.elonen.NanoHTTPD.Response resFull = httpServer.serveFileDownload(null, testFile.getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.OK.getRequestStatus(), resFull.getStatus().getRequestStatus());
            assertEquals("video/mp4", resFull.getMimeType());
            assertEquals("bytes", resFull.getHeader("accept-ranges"));
            assertEquals("100", resFull.getHeader("content-length"));
            byte[] fullBytes = readBytes(resFull);
            assertEquals(100, fullBytes.length);
            assertEquals(0, fullBytes[0]);
            assertEquals(99, fullBytes[99]);

            // 5. Test Range Header (bytes=10-20) -> 206 Partial Content
            java.util.Map<String, String> rangeHeaders = new java.util.HashMap<>();
            rangeHeaders.put("range", "bytes=10-20");
            fi.iki.elonen.NanoHTTPD.IHTTPSession rangeSession = (fi.iki.elonen.NanoHTTPD.IHTTPSession) java.lang.reflect.Proxy.newProxyInstance(
                    fi.iki.elonen.NanoHTTPD.IHTTPSession.class.getClassLoader(),
                    new Class<?>[]{fi.iki.elonen.NanoHTTPD.IHTTPSession.class},
                    (proxy, method, args) -> "getHeaders".equals(method.getName()) ? rangeHeaders : null
            );
            fi.iki.elonen.NanoHTTPD.Response resRange = httpServer.serveFileDownload(rangeSession, testFile.getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.PARTIAL_CONTENT.getRequestStatus(), resRange.getStatus().getRequestStatus());
            assertEquals("bytes 10-20/100", resRange.getHeader("content-range"));
            assertEquals("11", resRange.getHeader("content-length"));
            assertEquals("bytes", resRange.getHeader("accept-ranges"));
            byte[] rangeBytes = readBytes(resRange);
            assertEquals(11, rangeBytes.length);
            assertEquals(10, rangeBytes[0]);
            assertEquals(20, rangeBytes[10]);

            // 6. Test Open-Ended Range Header (bytes=80-)
            rangeHeaders.put("range", "bytes=80-");
            fi.iki.elonen.NanoHTTPD.Response resOpenRange = httpServer.serveFileDownload(rangeSession, testFile.getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.PARTIAL_CONTENT.getRequestStatus(), resOpenRange.getStatus().getRequestStatus());
            assertEquals("bytes 80-99/100", resOpenRange.getHeader("content-range"));
            assertEquals("20", resOpenRange.getHeader("content-length"));
            byte[] openRangeBytes = readBytes(resOpenRange);
            assertEquals(20, openRangeBytes.length);
            assertEquals(80, openRangeBytes[0]);
            assertEquals(99, openRangeBytes[19]);

            // 7. Test Suffix Range Header (bytes=-15)
            rangeHeaders.put("range", "bytes=-15");
            fi.iki.elonen.NanoHTTPD.Response resSuffixRange = httpServer.serveFileDownload(rangeSession, testFile.getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.PARTIAL_CONTENT.getRequestStatus(), resSuffixRange.getStatus().getRequestStatus());
            assertEquals("bytes 85-99/100", resSuffixRange.getHeader("content-range"));
            assertEquals("15", resSuffixRange.getHeader("content-length"));
            byte[] suffixBytes = readBytes(resSuffixRange);
            assertEquals(15, suffixBytes.length);
            assertEquals(85, suffixBytes[0]);
            assertEquals(99, suffixBytes[14]);

            // 8. Test Invalid Range -> 416
            rangeHeaders.put("range", "bytes=200-300");
            fi.iki.elonen.NanoHTTPD.Response resInvalidRange = httpServer.serveFileDownload(rangeSession, testFile.getAbsolutePath());
            assertEquals(fi.iki.elonen.NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE.getRequestStatus(), resInvalidRange.getStatus().getRequestStatus());
            assertEquals("bytes */100", resInvalidRange.getHeader("content-range"));
        } finally {
            com.lll.contextshared.util.StorageHelper.setExtraAllowedRoots(null);
            deleteRecursively(tempDir);
            deleteRecursively(outsideDir);
        }
    }

    private byte[] readBytes(fi.iki.elonen.NanoHTTPD.Response res) throws java.io.IOException {
        java.io.InputStream is = res.getData();
        if (is == null) return new byte[0];
        long len = -1;
        String cl = res.getHeader("content-length");
        if (cl != null) {
            try { len = Long.parseLong(cl); } catch (NumberFormatException ignored) {}
        }
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int r;
        long totalRead = 0;
        while ((len < 0 || totalRead < len) && (r = is.read(buf, 0, (int)(len < 0 ? buf.length : Math.min(buf.length, len - totalRead)))) != -1) {
            baos.write(buf, 0, r);
            totalRead += r;
        }
        return baos.toByteArray();
    }

    private void deleteRecursively(java.io.File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children != null) {
                for (java.io.File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}
