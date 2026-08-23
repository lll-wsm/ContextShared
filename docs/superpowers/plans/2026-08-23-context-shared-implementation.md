# ContextShared Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the ContextShared Android app (Java + View/XML) and embedded Web Client (HTML5/CSS3/JS) to turn an Android device into a local HTTP & WebSocket server for seamless, bidirectional clipboard sync and file transfer with any computer browser.

**Architecture:** 
- Embedded `NanoHTTPD` and `NanoWSD` running inside an Android `ForegroundService` with notification keep-alive.
- Dynamic IP detection & port discovery, ZXing QR code generation, and 4-digit PIN authentication.
- Single Page Web App embedded in `assets/web/` providing drag-and-drop file upload, real-time WebSocket clipboard sync, and file browser.

**Tech Stack:** Java 11, Android SDK 36 (minSdk 24), NanoHTTPD 2.3.1 (HTTP & WebSocket), ZXing Core 3.5.3, Gson 2.11.0, Material Components.

**Spec:** [`docs/superpowers/specs/2026-08-23-context-shared-android-server-design.md`](file:///Users/lll/Projects.localized/GitHubProjects/ContextShared/docs/superpowers/specs/2026-08-23-context-shared-android-server-design.md)

## Global Constraints
- Target Android minSdk = 24, compileSdk / targetSdk = 36.
- Pure Java for Android backend and vanilla HTML5/CSS3/JS for embedded Web client (zero Node.js/npm dependencies).
- Strictly follow Scoped Storage & Foreground Service constraints on modern Android versions.
- All file operations must be streaming-based to prevent OutOfMemory errors on large file transfers.

---

### Task 1: Project Dependencies & Android Manifest Setup

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: Unit test build verification via `./gradlew assembleDebug`

**Interfaces:**
- Produces: Gradle dependencies (`nanohttpd`, `nanohttpd-websocket`, `gson`, `zxing`) and configured manifest permissions.

- [ ] **Step 1: Update version catalog with required libraries**

Modify `gradle/libs.versions.toml`:
```toml
[versions]
agp = "9.3.1"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
appcompat = "1.6.1"
material = "1.10.0"
nanohttpd = "2.3.1"
gson = "2.11.0"
zxing = "3.5.3"

[libraries]
junit = { group = "junit", name = "junit", version.ref = "junit" }
ext-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
nanohttpd = { group = "org.nanohttpd", name = "nanohttpd", version.ref = "nanohttpd" }
nanohttpd-websocket = { group = "org.nanohttpd", name = "nanohttpd-websocket", version.ref = "nanohttpd" }
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }
zxing = { group = "com.google.zxing", name = "core", version.ref = "zxing" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
```

- [ ] **Step 2: Add dependencies in app/build.gradle**

Add dependencies to `app/build.gradle`:
```groovy
dependencies {
    implementation libs.appcompat
    implementation libs.material
    implementation libs.nanohttpd
    implementation libs.nanohttpd.websocket
    implementation libs.gson
    implementation libs.zxing

    testImplementation libs.junit
    androidTestImplementation libs.espresso.core
    androidTestImplementation libs.ext.junit
}
```

- [ ] **Step 3: Update AndroidManifest.xml with permissions and service**

Update `app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="29" tools:ignore="ScopedStorage" />
    <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ContextShared"
        android:requestLegacyExternalStorage="true">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.WebService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="dataSync" />
    </application>

</manifest>
```

- [ ] **Step 4: Verify build configuration**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle app/src/main/AndroidManifest.xml
git commit -m "chore: setup dependencies and permissions for ContextShared"
```

---

### Task 2: Data Models and Session/Security Manager

**Files:**
- Create: `app/src/main/java/com/lll/contextshared/model/DeviceInfo.java`
- Create: `app/src/main/java/com/lll/contextshared/model/WsMessage.java`
- Create: `app/src/main/java/com/lll/contextshared/model/FileItem.java`
- Create: `app/src/main/java/com/lll/contextshared/server/SessionManager.java`
- Test: `app/src/test/java/com/lll/contextshared/server/SessionManagerTest.java`

**Interfaces:**
- Consumes: None
- Produces:
  - `DeviceInfo`: POJO for device status JSON serialization
  - `WsMessage`: POJO for WebSocket events (`type`, `payload`, `timestamp`)
  - `FileItem`: POJO for file browser metadata (`name`, `path`, `size`, `lastModified`, `isDirectory`, `mimeType`)
  - `SessionManager`:
    - `String getPinCode()`
    - `String refreshPinCode()`
    - `boolean verifyPin(String pin)`
    - `String createSession()`
    - `boolean isValidToken(String token)`
    - `void invalidateToken(String token)`

- [ ] **Step 1: Write the failing unit tests for SessionManager and WsMessage**

Create `app/src/test/java/com/lll/contextshared/server/SessionManagerTest.java`:
```java
package com.lll.contextshared.server;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

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
        assertFalse(sessionManager.verifyPin("wrong-pin"));
    }

    @Test
    public void testSessionTokenLifecycle() {
        String token = sessionManager.createSession();
        assertNotNull(token);
        assertTrue(sessionManager.isValidToken(token));

        sessionManager.invalidateToken(token);
        assertFalse(sessionManager.isValidToken(token));
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
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.lll.contextshared.server.SessionManagerTest`
Expected: Compilation error (Classes do not exist yet)

- [ ] **Step 3: Implement Data Models and SessionManager**

Create `app/src/main/java/com/lll/contextshared/model/DeviceInfo.java`:
```java
package com.lll.contextshared.model;

public class DeviceInfo {
    private String deviceName;
    private int batteryLevel;
    private boolean isCharging;
    private long freeStorageBytes;
    private long totalStorageBytes;
    private boolean authRequired;

    public DeviceInfo(String deviceName, int batteryLevel, boolean isCharging, 
                      long freeStorageBytes, long totalStorageBytes, boolean authRequired) {
        this.deviceName = deviceName;
        this.batteryLevel = batteryLevel;
        this.isCharging = isCharging;
        this.freeStorageBytes = freeStorageBytes;
        this.totalStorageBytes = totalStorageBytes;
        this.authRequired = authRequired;
    }

    public String getDeviceName() { return deviceName; }
    public int getBatteryLevel() { return batteryLevel; }
    public boolean isCharging() { return isCharging; }
    public long getFreeStorageBytes() { return freeStorageBytes; }
    public long getTotalStorageBytes() { return totalStorageBytes; }
    public boolean isAuthRequired() { return authRequired; }
}
```

Create `app/src/main/java/com/lll/contextshared/model/FileItem.java`:
```java
package com.lll.contextshared.model;

public class FileItem {
    private String name;
    private String path;
    private long size;
    private long lastModified;
    private boolean isDirectory;
    private String mimeType;

    public FileItem(String name, String path, long size, long lastModified, boolean isDirectory, String mimeType) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
        this.isDirectory = isDirectory;
        this.mimeType = mimeType;
    }

    public String getName() { return name; }
    public String getPath() { return path; }
    public long getSize() { return size; }
    public long getLastModified() { return lastModified; }
    public boolean isDirectory() { return isDirectory; }
    public String getMimeType() { return mimeType; }
}
```

Create `app/src/main/java/com/lll/contextshared/model/WsMessage.java`:
```java
package com.lll.contextshared.model;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

public class WsMessage {
    private static final Gson GSON = new Gson();

    private String type;
    private Object payload;
    private long timestamp;

    public WsMessage() {}

    public WsMessage(String type, Object payload) {
        this.type = type;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static WsMessage fromJson(String json) {
        return GSON.fromJson(json, WsMessage.class);
    }
}
```

Create `app/src/main/java/com/lll/contextshared/server/SessionManager.java`:
```java
package com.lll.contextshared.server;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final SecureRandom random = new SecureRandom();
    private String currentPinCode;
    private final Set<String> validTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean authRequired = true;

    public SessionManager() {
        refreshPinCode();
    }

    public synchronized String getPinCode() {
        return currentPinCode;
    }

    public synchronized String refreshPinCode() {
        int pin = 1000 + random.nextInt(9000);
        this.currentPinCode = String.valueOf(pin);
        return this.currentPinCode;
    }

    public synchronized boolean verifyPin(String pin) {
        if (!authRequired) return true;
        return currentPinCode != null && currentPinCode.equals(pin != null ? pin.trim() : null);
    }

    public String createSession() {
        String token = UUID.randomUUID().toString().replace("-", "");
        validTokens.add(token);
        return token;
    }

    public boolean isValidToken(String token) {
        if (!authRequired) return true;
        return token != null && validTokens.contains(token);
    }

    public void invalidateToken(String token) {
        if (token != null) {
            validTokens.remove(token);
        }
    }

    public boolean isAuthRequired() {
        return authRequired;
    }

    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests com.lll.contextshared.server.SessionManagerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lll/contextshared/model/ app/src/main/java/com/lll/contextshared/server/SessionManager.java app/src/test/java/com/lll/contextshared/server/SessionManagerTest.java
git commit -m "feat: add models and session manager with unit tests"
```

---

### Task 3: Utility Classes (Network, QR Code, Storage Helper, Clipboard Watcher)

**Files:**
- Create: `app/src/main/java/com/lll/contextshared/util/NetworkUtils.java`
- Create: `app/src/main/java/com/lll/contextshared/util/QrCodeGenerator.java`
- Create: `app/src/main/java/com/lll/contextshared/util/StorageHelper.java`
- Create: `app/src/main/java/com/lll/contextshared/util/ClipboardWatcher.java`
- Test: `app/src/test/java/com/lll/contextshared/util/NetworkUtilsTest.java`

**Interfaces:**
- Consumes: `FileItem`
- Produces:
  - `NetworkUtils.getLocalIpAddress(Context)`
  - `NetworkUtils.isPortAvailable(int)`
  - `NetworkUtils.findAvailablePort(int)`
  - `QrCodeGenerator.generateQrCodeBitmap(String, int, int)`
  - `StorageHelper`: file listing, streaming safe saving, mime types.
  - `ClipboardWatcher`: read/write clipboard, listener callbacks.

- [ ] **Step 1: Write unit tests for NetworkUtils and StorageHelper MIME helper**

Create `app/src/test/java/com/lll/contextshared/util/NetworkUtilsTest.java`:
```java
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
        assertEquals("application/pdf", StorageHelper.getMimeType("doc.pdf"));
        assertEquals("application/vnd.android.package-archive", StorageHelper.getMimeType("app.apk"));
        assertEquals("application/octet-stream", StorageHelper.getMimeType("unknown.xyz123"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.lll.contextshared.util.NetworkUtilsTest`
Expected: Compilation error (Classes not created yet)

- [ ] **Step 3: Implement Utility Classes**

Create `app/src/main/java/com/lll/contextshared/util/NetworkUtils.java`:
```java
package com.lll.contextshared.util;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.List;

public class NetworkUtils {
    public static String getLocalIpAddress(Context context) {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.startsWith("127.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static int findAvailablePort(int startingPort) {
        for (int port = startingPort; port < startingPort + 100; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return startingPort;
    }
}
```

Create `app/src/main/java/com/lll/contextshared/util/StorageHelper.java`:
```java
package com.lll.contextshared.util;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.webkit.MimeTypeMap;

import com.lll.contextshared.model.FileItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class StorageHelper {
    public static final String FOLDER_NAME = "ContextShared";

    public static File getSharedStorageDir() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File appDir = new File(downloads, FOLDER_NAME);
        if (!appDir.exists()) {
            appDir.mkdirs();
        }
        return appDir;
    }

    public static String getMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        int lastDot = filename.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < filename.length() - 1) {
            String ext = filename.substring(lastDot + 1).toLowerCase();
            if ("apk".equals(ext)) return "application/vnd.android.package-archive";
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    public static List<FileItem> listFiles(String category) {
        List<FileItem> list = new ArrayList<>();
        File targetDir;
        if ("photos".equalsIgnoreCase(category)) {
            targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        } else if ("documents".equalsIgnoreCase(category)) {
            targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        } else {
            targetDir = getSharedStorageDir();
        }

        if (targetDir != null && targetDir.exists()) {
            File[] files = targetDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isHidden()) continue;
                    list.add(new FileItem(
                            f.getName(),
                            f.getAbsolutePath(),
                            f.length(),
                            f.lastModified(),
                            f.isDirectory(),
                            getMimeType(f.getName())
                    ));
                }
            }
        }
        return list;
    }

    public static File saveStreamToFile(Context context, InputStream in, String fileName) throws IOException {
        File dir = getSharedStorageDir();
        File targetFile = new File(dir, fileName);
        try (OutputStream out = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        if (context != null) {
            MediaScannerConnection.scanFile(context, new String[]{targetFile.getAbsolutePath()}, null, null);
        }
        return targetFile;
    }
}
```

Create `app/src/main/java/com/lll/contextshared/util/QrCodeGenerator.java`:
```java
package com.lll.contextshared.util;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

public class QrCodeGenerator {
    public static Bitmap generateQrCodeBitmap(String content, int width, int height) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
```

Create `app/src/main/java/com/lll/contextshared/util/ClipboardWatcher.java`:
```java
package com.lll.contextshared.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class ClipboardWatcher {
    public interface OnClipboardChangeListener {
        void onClipboardChanged(String text);
    }

    private final Context context;
    private final ClipboardManager clipboardManager;
    private final Handler mainHandler;
    private OnClipboardChangeListener listener;
    private String lastCopiedText = "";

    public ClipboardWatcher(Context context) {
        this.context = context.getApplicationContext();
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(OnClipboardChangeListener listener) {
        this.listener = listener;
    }

    public void startWatching() {
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipListener);
        }
    }

    public void stopWatching() {
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
    }

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        String text = getPrimaryClipText();
        if (text != null && !text.isEmpty() && !text.equals(lastCopiedText)) {
            lastCopiedText = text;
            if (listener != null) {
                listener.onClipboardChanged(text);
            }
        }
    };

    public String getPrimaryClipText() {
        if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                CharSequence text = clipData.getItemAt(0).getText();
                return text != null ? text.toString() : null;
            }
        }
        return null;
    }

    public void setPrimaryClipText(String text) {
        if (text == null) return;
        this.lastCopiedText = text;
        mainHandler.post(() -> {
            if (clipboardManager != null) {
                ClipData clip = ClipData.newPlainText("ContextShared", text);
                clipboardManager.setPrimaryClip(clip);
            }
        });
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests com.lll.contextshared.util.NetworkUtilsTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lll/contextshared/util/ app/src/test/java/com/lll/contextshared/util/NetworkUtilsTest.java
git commit -m "feat: add network, storage, QR code and clipboard utilities"
```

---

### Task 4: Embedded Web Frontend Assets (HTML, CSS, JS)

**Files:**
- Create: `app/src/main/assets/web/index.html`
- Create: `app/src/main/assets/web/style.css`
- Create: `app/src/main/assets/web/app.js`

**Interfaces:**
- Produces: Complete responsive Single Page Application bundle inside `assets/web/` capable of connecting to `/ws`, uploading files, triggering actions, and syncing clipboard.

- [ ] **Step 1: Create index.html**

Create `app/src/main/assets/web/index.html`:
```html
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>ContextShared - 极速跨端互联</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <!-- 顶部状态栏 -->
    <header class="app-header">
        <div class="header-left">
            <div class="logo">📱 ContextShared</div>
            <div class="device-tag" id="deviceTag">Pixel 8</div>
        </div>
        <div class="header-right">
            <div class="status-indicator connected" id="statusIndicator">
                <span class="status-dot"></span>
                <span class="status-text" id="statusText">已连接</span>
            </div>
            <div class="battery-tag" id="batteryTag">🔋 85%</div>
        </div>
    </header>

    <main class="container">
        <!-- 快速双向操作面板 -->
        <div class="dashboard-grid">
            <!-- 剪贴板与文本传输 -->
            <section class="card clip-card">
                <div class="card-header">
                    <h2>📋 剪贴板同步</h2>
                    <span class="badge" id="clipStatus">实时监听中</span>
                </div>
                <div class="card-body">
                    <div class="latest-clip-box" id="latestClipBox">
                        <div class="clip-label">手机端最近复制：</div>
                        <div class="clip-content" id="phoneClipContent">暂无复制内容</div>
                        <button class="btn btn-secondary btn-sm" id="btnCopyPhoneClip">复制到电脑</button>
                    </div>

                    <div class="send-text-box">
                        <textarea id="textToSend" placeholder="输入要发送到手机的文本或网址... (按 Ctrl/Cmd+Enter 发送)"></textarea>
                        <div class="btn-group">
                            <button class="btn btn-primary" id="btnSendClipboard">📤 发送到手机剪贴板</button>
                            <button class="btn btn-outline" id="btnOpenUrl">🌐 手机浏览器打开</button>
                        </div>
                    </div>
                </div>
            </section>

            <!-- 文件拖拽上传 -->
            <section class="card upload-card">
                <div class="card-header">
                    <h2>📁 文件极速快传</h2>
                    <span class="badge">拖拽即传</span>
                </div>
                <div class="card-body">
                    <div class="dropzone" id="dropzone">
                        <div class="dropzone-icon">☁️</div>
                        <div class="dropzone-text">将文件拖拽至此，或 <span class="dropzone-btn">点击选择文件</span></div>
                        <input type="file" id="fileInput" multiple style="display: none;">
                    </div>

                    <div class="upload-progress-list" id="uploadList"></div>
                </div>
            </section>
        </div>

        <!-- 手机文件管理与浏览 -->
        <section class="card files-section">
            <div class="card-header">
                <h2>📂 手机文件库</h2>
                <div class="tab-group">
                    <button class="tab-btn active" data-category="downloads">📥 下载/共享</button>
                    <button class="tab-btn" data-category="photos">🖼 照片/相册</button>
                    <button class="tab-btn" data-category="documents">📑 文档</button>
                </div>
            </div>
            <div class="card-body">
                <div class="file-grid" id="fileGrid">
                    <div class="loading-spinner">加载中...</div>
                </div>
            </div>
        </section>
    </main>

    <!-- PIN 码配对弹窗 -->
    <div class="modal-overlay" id="pinModal">
        <div class="modal-card">
            <h3>🔒 设备配对验证</h3>
            <p>请输入手机屏幕上显示的 4 位连接验证码：</p>
            <div class="pin-inputs">
                <input type="text" maxlength="4" id="pinCodeInput" placeholder="输入4位PIN码" autofocus>
            </div>
            <button class="btn btn-primary btn-block" id="btnSubmitPin">验证并连接</button>
        </div>
    </div>

    <!-- 全屏拖拽提示遮罩 -->
    <div class="drag-overlay" id="dragOverlay">
        <div class="drag-overlay-content">
            <div class="drag-icon">📥</div>
            <h2>松开鼠标即可立即上传至手机</h2>
        </div>
    </div>

    <script src="app.js"></script>
</body>
</html>
```

- [ ] **Step 2: Create style.css**

Create `app/src/main/assets/web/style.css`:
```css
:root {
    --primary: #2563eb;
    --primary-hover: #1d4ed8;
    --bg-main: #f8fafc;
    --bg-card: #ffffff;
    --text-main: #0f172a;
    --text-muted: #64748b;
    --border: #e2e8f0;
    --success: #10b981;
    --radius: 12px;
    --shadow: 0 4px 6px -1px rgb(0 0 0 / 0.07), 0 2px 4px -2px rgb(0 0 0 / 0.07);
}

@media (prefers-color-scheme: dark) {
    :root {
        --bg-main: #0f172a;
        --bg-card: #1e293b;
        --text-main: #f8fafc;
        --text-muted: #94a3b8;
        --border: #334155;
        --shadow: 0 4px 6px -1px rgb(0 0 0 / 0.3);
    }
}

* { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
body { background-color: var(--bg-main); color: var(--text-main); min-height: 100vh; }

.app-header { display: flex; justify-content: space-between; align-items: center; padding: 1rem 2rem; background: var(--bg-card); border-bottom: 1px solid var(--border); }
.header-left, .header-right { display: flex; align-items: center; gap: 1rem; }
.logo { font-size: 1.25rem; font-weight: 700; color: var(--primary); }
.device-tag, .battery-tag { padding: 0.25rem 0.75rem; background: var(--border); border-radius: 20px; font-size: 0.875rem; }

.status-indicator { display: flex; align-items: center; gap: 0.5rem; font-size: 0.875rem; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success); }
.status-indicator.disconnected .status-dot { background: #ef4444; }

.container { max-width: 1200px; margin: 2rem auto; padding: 0 1.5rem; display: flex; flex-direction: column; gap: 1.5rem; }
.dashboard-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; }
@media (max-width: 768px) { .dashboard-grid { grid-template-columns: 1fr; } }

.card { background: var(--bg-card); border-radius: var(--radius); border: 1px solid var(--border); box-shadow: var(--shadow); overflow: hidden; }
.card-header { display: flex; justify-content: space-between; align-items: center; padding: 1rem 1.25rem; border-bottom: 1px solid var(--border); }
.card-header h2 { font-size: 1.1rem; }
.badge { font-size: 0.75rem; padding: 0.2rem 0.5rem; background: var(--border); border-radius: 4px; color: var(--text-muted); }
.card-body { padding: 1.25rem; display: flex; flex-direction: column; gap: 1rem; }

.latest-clip-box { background: var(--bg-main); border-radius: 8px; padding: 1rem; border: 1px dashed var(--border); }
.clip-label { font-size: 0.75rem; color: var(--text-muted); margin-bottom: 0.25rem; }
.clip-content { font-size: 0.95rem; word-break: break-all; max-height: 100px; overflow-y: auto; margin-bottom: 0.5rem; }

textarea { width: 100%; height: 100px; padding: 0.75rem; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-main); color: var(--text-main); resize: vertical; }
.btn-group { display: flex; gap: 0.75rem; }

.btn { padding: 0.6rem 1.2rem; border-radius: 6px; border: none; font-weight: 600; cursor: pointer; transition: all 0.2s; }
.btn-primary { background: var(--primary); color: white; }
.btn-primary:hover { background: var(--primary-hover); }
.btn-secondary { background: var(--border); color: var(--text-main); }
.btn-outline { background: transparent; border: 1px solid var(--border); color: var(--text-main); }
.btn-sm { padding: 0.3rem 0.75rem; font-size: 0.8rem; }
.btn-block { width: 100%; }

.dropzone { border: 2px dashed var(--border); border-radius: var(--radius); padding: 2.5rem 1rem; text-align: center; cursor: pointer; transition: 0.2s; }
.dropzone:hover, .dropzone.dragover { border-color: var(--primary); background: rgba(37, 99, 235, 0.05); }
.dropzone-icon { font-size: 2.5rem; margin-bottom: 0.5rem; }
.dropzone-btn { color: var(--primary); font-weight: 600; }

.upload-item { display: flex; align-items: center; justify-content: space-between; padding: 0.5rem 0; border-bottom: 1px solid var(--border); font-size: 0.875rem; }
.progress-bar-bg { width: 100%; height: 6px; background: var(--border); border-radius: 3px; overflow: hidden; margin-top: 4px; }
.progress-bar-fill { height: 100%; background: var(--primary); width: 0%; transition: width 0.1s; }

.tab-group { display: flex; gap: 0.5rem; }
.tab-btn { background: transparent; border: 1px solid var(--border); padding: 0.35rem 0.75rem; border-radius: 6px; color: var(--text-main); cursor: pointer; font-size: 0.85rem; }
.tab-btn.active { background: var(--primary); color: white; border-color: var(--primary); }

.file-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 1rem; }
.file-card { border: 1px solid var(--border); border-radius: 8px; padding: 0.75rem; display: flex; flex-direction: column; gap: 0.5rem; background: var(--bg-main); }
.file-name { font-size: 0.85rem; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-meta { font-size: 0.75rem; color: var(--text-muted); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: none; place-items: center; z-index: 1000; }
.modal-overlay.active { display: grid; }
.modal-card { background: var(--bg-card); padding: 2rem; border-radius: var(--radius); width: 90%; max-width: 360px; text-align: center; }
.pin-inputs input { width: 100%; padding: 0.75rem; font-size: 1.5rem; text-align: center; letter-spacing: 8px; border: 2px solid var(--border); border-radius: 8px; margin: 1rem 0; }

.drag-overlay { position: fixed; inset: 0; background: rgba(37, 99, 235, 0.85); color: white; display: none; place-items: center; z-index: 2000; }
.drag-overlay.active { display: grid; }
.drag-overlay-content { text-align: center; }
.drag-icon { font-size: 5rem; }
```

- [ ] **Step 3: Create app.js**

Create `app/src/main/assets/web/app.js`:
```javascript
let token = localStorage.getItem('cs_token') || '';
let ws = null;
let currentCategory = 'downloads';

document.addEventListener('DOMContentLoaded', () => {
    initWebSocket();
    initEventListeners();
    fetchDeviceInfo();
    fetchFiles(currentCategory);
});

function initWebSocket() {
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const wsUrl = `${protocol}//${location.host}/ws?token=${token}`;
    ws = new WebSocket(wsUrl);

    ws.onopen = () => {
        document.getElementById('statusIndicator').className = 'status-indicator connected';
        document.getElementById('statusText').innerText = '已连接';
        document.getElementById('pinModal').classList.remove('active');
    };

    ws.onmessage = (event) => {
        try {
            const data = JSON.parse(event.data);
            handleWsMessage(data);
        } catch (e) {
            console.error('Invalid WS payload', e);
        }
    };

    ws.onclose = () => {
        document.getElementById('statusIndicator').className = 'status-indicator disconnected';
        document.getElementById('statusText').innerText = '连接已断开 (重连中...)';
        setTimeout(initWebSocket, 2000);
    };
}

function handleWsMessage(msg) {
    if (msg.type === 'CLIPBOARD_PUSH') {
        const text = typeof msg.payload === 'string' ? msg.payload : JSON.stringify(msg.payload);
        document.getElementById('phoneClipContent').innerText = text;
    } else if (msg.type === 'DEVICE_STATUS') {
        updateDeviceStatus(msg.payload);
    }
}

function updateDeviceStatus(info) {
    if (!info) return;
    if (info.deviceName) document.getElementById('deviceTag').innerText = info.deviceName;
    if (info.batteryLevel !== undefined) {
        document.getElementById('batteryTag').innerText = `🔋 ${info.batteryLevel}%${info.isCharging ? ' ⚡' : ''}`;
    }
}

async function fetchDeviceInfo() {
    try {
        const res = await fetch('/api/info');
        const info = await res.json();
        updateDeviceStatus(info);
        if (info.authRequired && !token) {
            document.getElementById('pinModal').classList.add('active');
        }
    } catch (e) {
        console.error('Fetch info error', e);
    }
}

async function fetchFiles(category) {
    try {
        const res = await fetch(`/api/files/list?category=${category}&token=${token}`);
        if (res.status === 401) {
            document.getElementById('pinModal').classList.add('active');
            return;
        }
        const files = await res.json();
        renderFileList(files);
    } catch (e) {
        console.error('Fetch files error', e);
    }
}

function renderFileList(files) {
    const grid = document.getElementById('fileGrid');
    if (!files || files.length === 0) {
        grid.innerHTML = '<div class="empty-state">目录为空</div>';
        return;
    }
    grid.innerHTML = files.map(file => `
        <div class="file-card">
            <div class="file-name" title="${file.name}">📄 ${file.name}</div>
            <div class="file-meta">${formatBytes(file.size)}</div>
            <a href="/api/files/download?path=${encodeURIComponent(file.path)}&token=${token}" class="btn btn-secondary btn-sm" download>下载</a>
        </div>
    `).join('');
}

function initEventListeners() {
    // 复制手机剪贴板
    document.getElementById('btnCopyPhoneClip').addEventListener('click', () => {
        const text = document.getElementById('phoneClipContent').innerText;
        if (text && text !== '暂无复制内容') {
            navigator.clipboard.writeText(text).then(() => alert('已复制到电脑剪贴板'));
        }
    });

    // 发送剪贴板到手机
    document.getElementById('btnSendClipboard').addEventListener('click', sendClipboardText);
    document.getElementById('textToSend').addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
            sendClipboardText();
        }
    });

    // 手机浏览器打开
    document.getElementById('btnOpenUrl').addEventListener('click', async () => {
        const text = document.getElementById('textToSend').value.trim();
        if (!text) return;
        await fetch('/api/action/open-url', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ url: text, token: token })
        });
        alert('已发送打开指令至手机');
    });

    // PIN 码提交
    document.getElementById('btnSubmitPin').addEventListener('click', async () => {
        const pin = document.getElementById('pinCodeInput').value.trim();
        if (!pin) return;
        const res = await fetch('/api/auth/verify', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pin: pin })
        });
        const data = await res.json();
        if (data.status === 'success' && data.token) {
            token = data.token;
            localStorage.setItem('cs_token', token);
            document.getElementById('pinModal').classList.remove('active');
            if (ws) ws.close();
            initWebSocket();
            fetchFiles(currentCategory);
        } else {
            alert('PIN 码错误，请重新输入');
        }
    });

    // 文件选择与拖拽
    const dropzone = document.getElementById('dropzone');
    const fileInput = document.getElementById('fileInput');
    dropzone.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => handleFilesUpload(e.target.files));

    // 全局拖拽监听
    window.addEventListener('dragover', (e) => {
        e.preventDefault();
        document.getElementById('dragOverlay').classList.add('active');
    });
    window.addEventListener('dragleave', (e) => {
        if (e.relatedTarget === null) {
            document.getElementById('dragOverlay').classList.remove('active');
        }
    });
    window.addEventListener('drop', (e) => {
        e.preventDefault();
        document.getElementById('dragOverlay').classList.remove('active');
        if (e.dataTransfer && e.dataTransfer.files.length > 0) {
            handleFilesUpload(e.dataTransfer.files);
        }
    });

    // 标签切换
    document.querySelectorAll('.tab-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            currentCategory = e.target.getAttribute('data-category');
            fetchFiles(currentCategory);
        });
    });
}

function sendClipboardText() {
    const text = document.getElementById('textToSend').value;
    if (!text) return;
    if (ws && ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({
            type: 'CLIPBOARD_SEND',
            payload: text,
            timestamp: Date.now()
        }));
        document.getElementById('textToSend').value = '';
    }
}

function handleFilesUpload(files) {
    if (!files || files.length === 0) return;
    for (let file of files) {
        uploadSingleFile(file);
    }
}

function uploadSingleFile(file) {
    const list = document.getElementById('uploadList');
    const item = document.createElement('div');
    item.className = 'upload-item';
    item.innerHTML = `
        <div style="flex: 1; margin-right: 10px;">
            <div>${file.name} (${formatBytes(file.size)})</div>
            <div class="progress-bar-bg"><div class="progress-bar-fill"></div></div>
        </div>
        <span class="status-percent">0%</span>
    `;
    list.prepend(item);

    const fill = item.querySelector('.progress-bar-fill');
    const percentText = item.querySelector('.status-percent');

    const formData = new FormData();
    formData.append('file', file);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/api/files/upload?token=${token}`);

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const p = Math.round((e.loaded / e.total) * 100);
            fill.style.width = p + '%';
            percentText.innerText = p + '%';
        }
    };

    xhr.onload = () => {
        if (xhr.status === 200) {
            percentText.innerText = '✅ 完成';
            fetchFiles(currentCategory);
        } else {
            percentText.innerText = '❌ 失败';
        }
    };

    xhr.send(formData);
}

function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/web/
git commit -m "feat: add embedded Web client HTML/CSS/JS assets"
```

---

### Task 5: HTTP & WebSocket Server Implementation

**Files:**
- Create: `app/src/main/java/com/lll/contextshared/server/AppHttpServer.java`
- Create: `app/src/main/java/com/lll/contextshared/server/AppWebSocketServer.java`
- Create: `app/src/main/java/com/lll/contextshared/server/ServerManager.java`

**Interfaces:**
- Consumes: `SessionManager`, `StorageHelper`, `ClipboardWatcher`, `DeviceInfo`, `WsMessage`
- Produces:
  - `ServerManager`:
    - `void startServer(int port)`
    - `void stopServer()`
    - `int getPort()`
    - `boolean isRunning()`
    - `void broadcastClipboard(String text)`
    - `void broadcastDeviceStatus(DeviceInfo info)`

- [ ] **Step 1: Implement AppHttpServer with static routing, REST APIs, and Multipart file streaming**

Create `app/src/main/java/com/lll/contextshared/server/AppHttpServer.java`:
```java
package com.lll.contextshared.server;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.lll.contextshared.model.DeviceInfo;
import com.lll.contextshared.model.FileItem;
import com.lll.contextshared.util.StorageHelper;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AppHttpServer extends NanoHTTPD {
    private final Context context;
    private final SessionManager sessionManager;
    private final Gson gson = new Gson();

    public AppHttpServer(Context context, int port, SessionManager sessionManager) {
        super(port);
        this.context = context.getApplicationContext();
        this.sessionManager = sessionManager;
    }

    @Override
    public Response handle(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        Map<String, String> params = session.getParms();

        // 1. 静态资源路由
        if (Method.GET.equals(method)) {
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return serveAsset("web/index.html", "text/html; charset=utf-8");
            } else if ("/style.css".equals(uri)) {
                return serveAsset("web/style.css", "text/css; charset=utf-8");
            } else if ("/app.js".equals(uri)) {
                return serveAsset("web/app.js", "application/javascript; charset=utf-8");
            }
        }

        // 2. API 路由
        if (uri.startsWith("/api/")) {
            return handleApi(session, uri, method, params);
        }

        return Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found");
    }

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
                return Response.newFixedLengthResponse(Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}");
            }

            if ("/api/files/list".equals(uri) && Method.GET.equals(method)) {
                String category = params.get("category");
                List<FileItem> list = StorageHelper.listFiles(category);
                return Response.newFixedLengthResponse(Status.OK, "application/json", gson.toJson(list));
            } else if ("/api/files/download".equals(uri) && Method.GET.equals(method)) {
                return serveFileDownload(params.get("path"));
            } else if ("/api/files/upload".equals(uri) && Method.POST.equals(method)) {
                return handleFileUpload(session);
            } else if ("/api/action/open-url".equals(uri) && Method.POST.equals(method)) {
                return handleOpenUrl(session);
            }
        } catch (Exception e) {
            return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
        return Response.newFixedLengthResponse(Status.NOT_FOUND, "application/json", "{\"error\":\"API Not Found\"}");
    }

    private Response serveAsset(String assetPath, String mimeType) {
        try {
            InputStream is = context.getAssets().open(assetPath);
            return Response.newChunkedResponse(Status.OK, mimeType, is);
        } catch (Exception e) {
            return Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset not found: " + assetPath);
        }
    }

    private Response getDeviceInfoResponse() {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
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
                sessionManager.isAuthRequired()
        );
        return Response.newFixedLengthResponse(Status.OK, "application/json", gson.toJson(info));
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
            return Response.newFixedLengthResponse(Status.OK, "application/json", gson.toJson(res));
        } else {
            return Response.newFixedLengthResponse(Status.FORBIDDEN, "application/json", "{\"status\":\"error\",\"message\":\"Invalid PIN\"}");
        }
    }

    private Response serveFileDownload(String filePath) {
        if (filePath == null) return Response.newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing path");
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory()) {
            return Response.newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File not found");
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            Response res = Response.newFixedLengthResponse(Status.OK, StorageHelper.getMimeType(file.getName()), fis, file.length());
            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            return res;
        } catch (Exception e) {
            return Response.newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleFileUpload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        Map<String, String> parms = session.getParms();

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String tempFilePath = entry.getValue();
            String originalFileName = parms.get(entry.getKey());
            if (originalFileName == null || originalFileName.isEmpty()) {
                originalFileName = "upload_" + System.currentTimeMillis();
            }
            File tempFile = new File(tempFilePath);
            try (InputStream in = new FileInputStream(tempFile)) {
                StorageHelper.saveStreamToFile(context, in, originalFileName);
            }
        }
        return Response.newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"success\"}");
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
            return Response.newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"success\"}");
        }
        return Response.newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"status\":\"error\"}");
    }
}
```

- [ ] **Step 2: Implement AppWebSocketServer & WebSocket Handler**

Create `app/src/main/java/com/lll/contextshared/server/AppWebSocketServer.java`:
```java
package com.lll.contextshared.server;

import com.google.gson.Gson;
import com.lll.contextshared.model.WsMessage;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.websockets.CloseCode;
import org.nanohttpd.protocols.websockets.NanoWSD;
import org.nanohttpd.protocols.websockets.WebSocket;
import org.nanohttpd.protocols.websockets.WebSocketFrame;

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
```

- [ ] **Step 3: Implement ServerManager to coordinate HTTP and WebSocket**

Create `app/src/main/java/com/lll/contextshared/server/ServerManager.java`:
```java
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
        if (isRunning) return;

        this.httpPort = NetworkUtils.findAvailablePort(8080);
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

    public int getHttpPort() {
        return httpPort;
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
```

- [ ] **Step 4: Verify build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lll/contextshared/server/
git commit -m "feat: implement HTTP and WebSocket embedded servers and ServerManager"
```

---

### Task 6: Foreground WebService & Android Native UI

**Files:**
- Create: `app/src/main/java/com/lll/contextshared/service/WebService.java`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/lll/contextshared/MainActivity.java`

**Interfaces:**
- Produces: Complete working Android application with start/stop switch, IP/QR code rendering, PIN code display, real-time logging, and foreground service.

- [ ] **Step 1: Implement Foreground WebService**

Create `app/src/main/java/com/lll/contextshared/service/WebService.java`:
```java
package com.lll.contextshared.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.lll.contextshared.MainActivity;
import com.lll.contextshared.R;
import com.lll.contextshared.server.ServerManager;
import com.lll.contextshared.util.NetworkUtils;

public class WebService extends Service {
    private static final String CHANNEL_ID = "context_shared_service";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_STOP_SERVICE = "com.lll.contextshared.ACTION_STOP";

    private final IBinder binder = new LocalBinder();
    private ServerManager serverManager;

    public class LocalBinder extends Binder {
        public WebService getService() {
            return WebService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serverManager = new ServerManager(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startServer();
        return START_STICKY;
    }

    public ServerManager getServerManager() {
        return serverManager;
    }

    public void startServer() {
        try {
            if (!serverManager.isRunning()) {
                serverManager.startServer();
            }
            startForegroundWithNotification();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopServer() {
        if (serverManager != null) {
            serverManager.stopServer();
        }
    }

    private void startForegroundWithNotification() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        int port = serverManager.getHttpPort();
        String contentText = "服务已就绪: http://" + ip + ":" + port;

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, WebService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ContextShared 互联服务运行中")
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止服务", stopPendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ContextShared Server Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("保持 ContextShared 本地局域网传输服务在后台正常运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
```

- [ ] **Step 2: Create activity_main.xml layout**

Modify `app/src/main/res/layout/activity_main.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F8FAFC">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="20dp">

        <!-- 顶部标题与开关卡片 -->
        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:cardCornerRadius="16dp"
            app:cardElevation="2dp"
            app:strokeColor="#E2E8F0"
            app:strokeWidth="1dp"
            android:layout_marginBottom="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="20dp">

                <RelativeLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="📱 ContextShared"
                        android:textSize="22sp"
                        android:textStyle="bold"
                        android:textColor="#0F172A" />

                    <com.google.android.material.switchmaterial.SwitchMaterial
                        android:id="@+id/switchServer"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:layout_alignParentEnd="true" />
                </RelativeLayout>

                <TextView
                    android:id="@+id/tvServerStatus"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="服务已停止"
                    android:textColor="#64748B"
                    android:textSize="14sp"
                    android:layout_marginTop="4dp" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <!-- 连接信息卡片 (二维码 & URL) -->
        <com.google.android.material.card.MaterialCardView
            android:id="@+id/cardConnection"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:cardCornerRadius="16dp"
            app:cardElevation="2dp"
            app:strokeColor="#E2E8F0"
            app:strokeWidth="1dp"
            android:layout_marginBottom="16dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center_horizontal"
                android:orientation="vertical"
                android:padding="20dp">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="电脑浏览器访问以下网址："
                    android:textColor="#64748B"
                    android:textSize="14sp" />

                <TextView
                    android:id="@+id/tvServerUrl"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="http://192.168.1.100:8080"
                    android:textColor="#2563EB"
                    android:textSize="18sp"
                    android:textStyle="bold"
                    android:layout_marginTop="6dp"
                    android:layout_marginBottom="16dp" />

                <ImageView
                    android:id="@+id/ivQrCode"
                    android:layout_width="180dp"
                    android:layout_height="180dp"
                    android:background="#FFFFFF"
                    android:scaleType="fitCenter" />

                <!-- 配对 PIN 码 -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:gravity="center_vertical"
                    android:layout_marginTop="16dp">

                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="配对 PIN 码："
                        android:textColor="#0F172A"
                        android:textSize="16sp" />

                    <TextView
                        android:id="@+id/tvPinCode"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="8848"
                        android:textColor="#2563EB"
                        android:textSize="20sp"
                        android:textStyle="bold"
                        android:layout_marginStart="8dp" />

                    <Button
                        android:id="@+id/btnRefreshPin"
                        style="@style/Widget.MaterialComponents.Button.TextButton"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="刷新"
                        android:layout_marginStart="8dp" />
                </LinearLayout>
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <!-- 实时日志卡片 -->
        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            app:cardCornerRadius="16dp"
            app:cardElevation="2dp"
            app:strokeColor="#E2E8F0"
            app:strokeWidth="1dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="16dp">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📊 传输日志"
                    android:textColor="#0F172A"
                    android:textStyle="bold"
                    android:textSize="15sp"
                    android:layout_marginBottom="8dp" />

                <TextView
                    android:id="@+id/tvLogs"
                    android:layout_width="match_parent"
                    android:layout_height="120dp"
                    android:background="#F1F5F9"
                    android:padding="8dp"
                    android:textColor="#334155"
                    android:textSize="12sp"
                    android:scrollbars="vertical"
                    android:text="等待连接..." />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

    </LinearLayout>
</ScrollView>
```

- [ ] **Step 3: Implement MainActivity with service binding, permissions, and QR code rendering**

Modify `app/src/main/java/com/lll/contextshared/MainActivity.java`:
```java
package com.lll.contextshared;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.lll.contextshared.server.ServerManager;
import com.lll.contextshared.service.WebService;
import com.lll.contextshared.util.NetworkUtils;
import com.lll.contextshared.util.QrCodeGenerator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ServerManager.ServerStateListener {
    private static final int PERMISSION_REQUEST_CODE = 101;

    private SwitchMaterial switchServer;
    private TextView tvServerStatus;
    private MaterialCardView cardConnection;
    private TextView tvServerUrl;
    private ImageView ivQrCode;
    private TextView tvPinCode;
    private Button btnRefreshPin;
    private TextView tvLogs;

    private WebService webService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            WebService.LocalBinder localBinder = (WebService.LocalBinder) binder;
            webService = localBinder.getService();
            isBound = true;
            webService.getServerManager().setStateListener(MainActivity.this);
            updateUiState();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            webService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        bindWebService();
    }

    private void initViews() {
        switchServer = findViewById(R.id.switchServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        cardConnection = findViewById(R.id.cardConnection);
        tvServerUrl = findViewById(R.id.tvServerUrl);
        ivQrCode = findViewById(R.id.ivQrCode);
        tvPinCode = findViewById(R.id.tvPinCode);
        btnRefreshPin = findViewById(R.id.btnRefreshPin);
        tvLogs = findViewById(R.id.tvLogs);
        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        switchServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startWebService();
            } else {
                stopWebService();
            }
        });

        btnRefreshPin.setOnClickListener(v -> {
            if (webService != null) {
                String newPin = webService.getServerManager().getSessionManager().refreshPinCode();
                tvPinCode.setText(newPin);
                Toast.makeText(this, "PIN 码已刷新: " + newPin, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void checkPermissions() {
        List<String> neededPermissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    private void bindWebService() {
        Intent intent = new Intent(this, WebService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startWebService() {
        Intent intent = new Intent(this, WebService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopWebService() {
        Intent intent = new Intent(this, WebService.class);
        intent.setAction(WebService.ACTION_STOP_SERVICE);
        startService(intent);
        updateUiState();
    }

    private void updateUiState() {
        boolean running = webService != null && webService.getServerManager() != null && webService.getServerManager().isRunning();
        switchServer.setChecked(running);
        if (running) {
            String ip = NetworkUtils.getLocalIpAddress(this);
            int port = webService.getServerManager().getHttpPort();
            onServerStarted(ip, port, port + 1);
        } else {
            onServerStopped();
        }
    }

    @Override
    public void onServerStarted(String ip, int httpPort, int wsPort) {
        runOnUiThread(() -> {
            String url = "http://" + ip + ":" + httpPort;
            tvServerStatus.setText("🟢 局域网服务运行中");
            cardConnection.setVisibility(View.VISIBLE);
            tvServerUrl.setText(url);

            if (webService != null) {
                tvPinCode.setText(webService.getServerManager().getSessionManager().getPinCode());
            }

            Bitmap qr = QrCodeGenerator.generateQrCodeBitmap(url, 400, 400);
            if (qr != null) {
                ivQrCode.setImageBitmap(qr);
            }
        });
    }

    @Override
    public void onServerStopped() {
        runOnUiThread(() -> {
            tvServerStatus.setText("⚪ 服务已停止");
            cardConnection.setVisibility(View.GONE);
        });
    }

    @Override
    public void onLog(String log) {
        runOnUiThread(() -> {
            String current = tvLogs.getText().toString();
            tvLogs.setText(log + "\n" + current);
        });
    }

    @Override
    protected void onDestroy() {
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        super.onDestroy();
    }
}
```

- [ ] **Step 4: Verify complete project build and tests**

Run: `./gradlew test`
Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lll/contextshared/service/ app/src/main/res/layout/activity_main.xml app/src/main/java/com/lll/contextshared/MainActivity.java
git commit -m "feat: implement WebService and MainActivity UI"
```

---

### Task 7: End-to-End System Verification & Polish

**Files:**
- Test: Unit tests pass (`./gradlew test`)
- Test: Full debug APK build verification (`./gradlew assembleDebug`)

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew test`
Expected: All tests PASS

- [ ] **Step 2: Run build to generate debug APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL with generated APK

- [ ] **Step 3: Commit final polish and documentation update**

```bash
git commit --allow-empty -m "chore: complete ContextShared end-to-end verification"
```
