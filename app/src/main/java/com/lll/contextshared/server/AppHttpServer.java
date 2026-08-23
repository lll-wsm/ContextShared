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

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

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
    public Response serve(IHTTPSession session) {
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

        return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found");
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
                return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json", "{\"error\":\"Unauthorized\"}");
            }

            if ("/api/files/list".equals(uri) && Method.GET.equals(method)) {
                String category = params.get("category");
                List<FileItem> list = StorageHelper.listFiles(category);
                return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(list));
            } else if ("/api/files/download".equals(uri) && Method.GET.equals(method)) {
                return serveFileDownload(params.get("path"));
            } else if ("/api/files/upload".equals(uri) && Method.POST.equals(method)) {
                return handleFileUpload(session);
            } else if ("/api/action/open-url".equals(uri) && Method.POST.equals(method)) {
                return handleOpenUrl(session);
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
        return newFixedLengthResponse(Status.NOT_FOUND, "application/json", "{\"error\":\"API Not Found\"}");
    }

    private Response serveAsset(String assetPath, String mimeType) {
        try {
            InputStream is = context.getAssets().open(assetPath);
            return newChunkedResponse(Status.OK, mimeType, is);
        } catch (Exception e) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset not found: " + assetPath);
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
        return newFixedLengthResponse(Status.OK, "application/json", gson.toJson(info));
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

    private Response serveFileDownload(String filePath) {
        if (filePath == null) return newFixedLengthResponse(Status.BAD_REQUEST, "text/plain", "Missing path");
        File file = new File(filePath);
        if (!file.exists() || file.isDirectory()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File not found");
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            Response res = newFixedLengthResponse(Status.OK, StorageHelper.getMimeType(file.getName()), fis, file.length());
            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            return res;
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", e.getMessage());
        }
    }

    private Response handleFileUpload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        Map<String, String> parms = session.getParms();

        for (Map.Entry<String, String> entry : files.entrySet()) {
            String tempFilePath = entry.getValue();
            String originalFileName = parms.get(entry.getKey());
            if (originalFileName == null || originalFileName.trim().isEmpty()) {
                originalFileName = "upload_" + System.currentTimeMillis();
            }
            originalFileName = new File(originalFileName).getName();
            if (originalFileName.isEmpty()) {
                originalFileName = "upload_" + System.currentTimeMillis();
            }
            File tempFile = new File(tempFilePath);
            try (InputStream in = new FileInputStream(tempFile)) {
                StorageHelper.saveStreamToFile(context, in, originalFileName);
            }
        }
        return newFixedLengthResponse(Status.OK, "application/json", "{\"status\":\"success\"}");
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
