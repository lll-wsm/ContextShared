package com.lll.contextshared.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class NetworkUtilsTest {
    @Test
    public void testFindAvailablePort() {
        int port = NetworkUtils.findAvailablePort(8080);
        assertTrue(port >= 8080);
        assertTrue(NetworkUtils.isPortAvailable(port));
    }

    @Test
    public void testMimeTypeResolution() {
        assertEquals("image/jpeg", StorageHelper.getMimeType("photo.jpg"));
        assertEquals("image/jpeg", StorageHelper.getMimeType("photo.JPEG"));
        assertEquals("application/pdf", StorageHelper.getMimeType("doc.pdf"));
        assertEquals("application/vnd.android.package-archive", StorageHelper.getMimeType("app.apk"));
        assertEquals("application/octet-stream", StorageHelper.getMimeType("unknown.xyz123"));
        assertEquals("application/octet-stream", StorageHelper.getMimeType(null));
        assertEquals("application/octet-stream", StorageHelper.getMimeType("noextension"));
    }

    @Test
    public void testGetLocalIpAddress() {
        String ip = NetworkUtils.getLocalIpAddress(null);
        assertNotNull(ip);
        assertFalse(ip.isEmpty());
    }
}
