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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StorageHelper {
    public static final String FOLDER_NAME = "ContextShared";

    private static final Map<String, String> COMMON_MIME_TYPES = new HashMap<>();
    static {
        COMMON_MIME_TYPES.put("jpg", "image/jpeg");
        COMMON_MIME_TYPES.put("jpeg", "image/jpeg");
        COMMON_MIME_TYPES.put("png", "image/png");
        COMMON_MIME_TYPES.put("gif", "image/gif");
        COMMON_MIME_TYPES.put("webp", "image/webp");
        COMMON_MIME_TYPES.put("bmp", "image/bmp");
        COMMON_MIME_TYPES.put("svg", "image/svg+xml");
        COMMON_MIME_TYPES.put("pdf", "application/pdf");
        COMMON_MIME_TYPES.put("apk", "application/vnd.android.package-archive");
        COMMON_MIME_TYPES.put("txt", "text/plain");
        COMMON_MIME_TYPES.put("html", "text/html");
        COMMON_MIME_TYPES.put("htm", "text/html");
        COMMON_MIME_TYPES.put("json", "application/json");
        COMMON_MIME_TYPES.put("xml", "text/xml");
        COMMON_MIME_TYPES.put("zip", "application/zip");
        COMMON_MIME_TYPES.put("tar", "application/x-tar");
        COMMON_MIME_TYPES.put("gz", "application/gzip");
        COMMON_MIME_TYPES.put("mp3", "audio/mpeg");
        COMMON_MIME_TYPES.put("wav", "audio/wav");
        COMMON_MIME_TYPES.put("mp4", "video/mp4");
        COMMON_MIME_TYPES.put("avi", "video/x-msvideo");
        COMMON_MIME_TYPES.put("mkv", "video/x-matroska");
        COMMON_MIME_TYPES.put("doc", "application/msword");
        COMMON_MIME_TYPES.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        COMMON_MIME_TYPES.put("xls", "application/vnd.ms-excel");
        COMMON_MIME_TYPES.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        COMMON_MIME_TYPES.put("ppt", "application/vnd.ms-powerpoint");
        COMMON_MIME_TYPES.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

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
            if (COMMON_MIME_TYPES.containsKey(ext)) {
                return COMMON_MIME_TYPES.get(ext);
            }
            try {
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) return mime;
            } catch (Throwable ignored) {}
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
