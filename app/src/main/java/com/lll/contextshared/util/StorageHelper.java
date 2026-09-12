package com.lll.contextshared.util;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.webkit.MimeTypeMap;

import com.lll.contextshared.model.FileItem;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StorageHelper {
    public static final String FOLDER_NAME = "ContextShared";

    /** 每个存储卷（内部存储 / 外置 SD 卡）内都不可访问的相对路径。 */
    private static final String[] RESTRICTED_RELATIVE_PATHS = {"Android/data", "Android/obb"};

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

    /** 一个可浏览的存储卷。 */
    public static final class Volume {
        private final File root;
        private final boolean removable;
        private final String label;

        public Volume(File root, boolean removable, String label) {
            this.root = root;
            this.removable = removable;
            this.label = label;
        }

        public File getRoot() { return root; }
        public String getPath() { return root.getAbsolutePath(); }
        public boolean isRemovable() { return removable; }
        public String getLabel() { return label; }
    }

    private static List<File> extraAllowedRoots = null;
    private static volatile List<Volume> cachedVolumes = null;
    private static List<Volume> volumesOverride = null;

    public static void setExtraAllowedRoots(List<File> roots) {
        extraAllowedRoots = roots;
    }

    /** 仅供单元测试注入单一根目录。 */
    public static void setBrowseRootForTesting(File root) {
        volumesOverride = root == null ? null
                : Collections.singletonList(new Volume(root, false, null));
    }

    /** 仅供单元测试注入多个存储卷。 */
    public static void setVolumesForTesting(List<Volume> volumes) {
        volumesOverride = volumes;
    }

    // ------------------------------------------------------------------
    // 存储卷发现
    // ------------------------------------------------------------------

    /** 重新探测存储卷（服务启动时调用一次；外置存储热插拔后重启服务即可刷新）。 */
    public static void refreshVolumes(Context context) {
        cachedVolumes = detectVolumes(context);
    }

    public static List<Volume> getVolumes() {
        if (volumesOverride != null) {
            return volumesOverride;
        }
        List<Volume> volumes = cachedVolumes;
        if (volumes == null || volumes.isEmpty()) {
            volumes = detectVolumes(null);
            cachedVolumes = volumes;
        }
        return volumes;
    }

    /**
     * 探测可浏览的存储卷：内部共享存储 + 可移动存储（SD 卡 / U 盘，若存在）。
     * 可移动卷在 Android 11+ 用 StorageManager 拿；旧版本用 getExternalFilesDirs() 反推卷根。
     */
    public static List<Volume> detectVolumes(Context context) {
        List<Volume> volumes = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        File primary = primaryStorageDir();
        volumes.add(new Volume(primary, false, null));
        seen.add(primary.getPath());

        if (context == null) {
            return volumes;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                StorageManager storageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                if (storageManager != null) {
                    for (StorageVolume volume : storageManager.getStorageVolumes()) {
                        if (!volume.isRemovable()) {
                            continue;   // 内部共享存储已由 primary 覆盖
                        }
                        File dir = volume.getDirectory();
                        if (dir == null) {
                            continue;
                        }
                        addVolume(volumes, seen, dir, true, volume.getDescription(context));
                    }
                }
            } catch (Throwable ignored) {
            }
        } else {
            try {
                File[] dirs = context.getExternalFilesDirs(null);
                if (dirs != null) {
                    for (File dir : dirs) {
                        File root = deriveVolumeRoot(dir);
                        if (root != null) {
                            addVolume(volumes, seen, root, true, null);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return volumes;
    }

    private static void addVolume(List<Volume> volumes, Set<String> seen, File dir, boolean removable, String label) {
        try {
            File canonical = dir.getCanonicalFile();
            if (!canonical.isDirectory()) {
                return;
            }
            if (seen.add(canonical.getPath())) {
                volumes.add(new Volume(canonical, removable, label));
            }
        } catch (Throwable ignored) {
        }
    }

    /** 由 /storage/XXXX-XXXX/Android/data/&lt;pkg&gt;/files 反推出卷根 /storage/XXXX-XXXX。 */
    private static File deriveVolumeRoot(File appExternalDir) {
        if (appExternalDir == null) {
            return null;
        }
        File root = appExternalDir;
        for (int i = 0; i < 4 && root != null; i++) {
            root = root.getParentFile();
        }
        return root;
    }

    private static File primaryStorageDir() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null) {
                return ext.getCanonicalFile();
            }
        } catch (Throwable ignored) {
        }
        return new File("/storage/emulated/0");
    }

    /** 默认（内部共享存储）根目录，兼容旧调用。 */
    public static File getBrowseRoot() {
        return getVolumes().get(0).getRoot();
    }

    /** 找到某个文件所属的存储卷根；不属于任何卷时返回 null。 */
    public static File findVolumeRoot(File file) {
        if (file == null) {
            return null;
        }
        try {
            File canonical = file.getCanonicalFile();
            String path = canonical.getPath();
            for (Volume volume : getVolumes()) {
                String rootPath = volume.getRoot().getCanonicalPath();
                if (path.equals(rootPath) || path.startsWith(rootPath + File.separator)) {
                    return volume.getRoot();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 存储访问能力
    // ------------------------------------------------------------------

    /**
     * Android 11(API 30) 起，targetSdk 30+ 的应用必须持有"所有文件访问权限"
     * （MANAGE_EXTERNAL_STORAGE，需用户在系统设置里手动开启）才能读写共享存储里的任意文件；
     * READ_MEDIA_* 只能读媒体文件。Android 10 及以下由 READ/WRITE_EXTERNAL_STORAGE 覆盖。
     */
    public static boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    public static List<File> getAllowedRoots() {
        List<File> roots = new ArrayList<>();
        if (extraAllowedRoots != null) {
            roots.addAll(extraAllowedRoots);
        }
        for (Volume volume : getVolumes()) {
            roots.add(volume.getRoot());
        }
        try {
            File shared = getSharedStorageDir();
            if (shared != null) roots.add(shared);
        } catch (Throwable ignored) {}
        try {
            File dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
            if (dcim != null) roots.add(dcim);
        } catch (Throwable ignored) {}
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs != null) roots.add(docs);
        } catch (Throwable ignored) {}
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) roots.add(downloads);
        } catch (Throwable ignored) {}
        return roots;
    }

    public static boolean isPathAllowed(File file) {
        return isPathAllowed(file, getAllowedRoots());
    }

    public static boolean isPathAllowed(File file, List<File> allowedRoots) {
        if (file == null || allowedRoots == null || allowedRoots.isEmpty()) {
            return false;
        }
        try {
            File canonical = file.getCanonicalFile();
            if (isRestrictedSystemPath(canonical)) {
                return false;
            }
            String canonicalPath = canonical.getPath();
            for (File root : allowedRoots) {
                if (root == null) continue;
                String rootCanonical = root.getCanonicalPath();
                if (canonicalPath.equals(rootCanonical) || canonicalPath.startsWith(rootCanonical + File.separator)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            return false;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 路径解析与安全
    // ------------------------------------------------------------------

    /** 相对某个卷根的路径；不在该卷内返回 null。 */
    private static String relativize(File root, File target) {
        if (root == null || target == null) return null;
        String rootPath = root.getPath();
        String targetPath = target.getPath();
        if (targetPath.equals(rootPath)) return "";
        if (!targetPath.startsWith(rootPath + File.separator)) return null;
        return targetPath.substring(rootPath.length() + 1);
    }

    public static boolean isRestrictedSystemPath(File file) {
        return isRestrictedSystemPath(file, findVolumeRoot(file));
    }

    public static boolean isRestrictedSystemPath(File file, File volumeRoot) {
        if (file == null || volumeRoot == null) {
            return false;
        }
        try {
            String relative = relativize(volumeRoot.getCanonicalFile(), file.getCanonicalFile());
            if (relative == null) {
                return false;
            }
            for (String restricted : RESTRICTED_RELATIVE_PATHS) {
                if (relative.equals(restricted) || relative.startsWith(restricted + "/")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * 把客户端传来的路径解析成"可浏览目录"。
     *
     * <p>安全规则：必须落在某个存储卷内（{@code ..} 穿越会被 canonical 化解后再校验）、
     * 不得是 Android/data|obb、必须真实存在且是目录。空值 / "/" 表示默认的内部存储根；
     * 相对路径按内部存储根解析。
     *
     * @return 合法的目录；非法时返回 null
     */
    public static File resolveBrowsableDirectory(String rawPath) {
        List<Volume> volumes = getVolumes();
        File defaultRoot = volumes.get(0).getRoot();
        String trimmed = rawPath == null ? "" : rawPath.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return defaultRoot.isDirectory() ? defaultRoot : null;
        }
        try {
            File candidate = new File(trimmed);
            if (!candidate.isAbsolute()) {
                candidate = new File(defaultRoot, trimmed);
            }
            File canonical = candidate.getCanonicalFile();
            File volumeRoot = findVolumeRoot(canonical);
            if (volumeRoot == null) {
                return null;
            }
            if (isRestrictedSystemPath(canonical, volumeRoot)) {
                return null;
            }
            return canonical.isDirectory() ? canonical : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在指定根目录范围内解析（单元测试与复用场景）。 */
    public static File resolveBrowsableDirectory(String rawPath, File root) {
        if (root == null) return null;
        String trimmed = rawPath == null ? "" : rawPath.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return root.isDirectory() ? root : null;
        }
        try {
            File candidate = new File(trimmed);
            if (!candidate.isAbsolute()) {
                candidate = new File(root, trimmed);
            }
            File canonical = candidate.getCanonicalFile();
            File canonicalRoot = root.getCanonicalFile();
            if (relativize(canonicalRoot, canonical) == null) {
                return null;
            }
            if (isRestrictedSystemPath(canonical, canonicalRoot)) {
                return null;
            }
            return canonical.isDirectory() ? canonical : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 去掉路径成分与控制字符，防止上传时被写入任意位置。非法名返回 null。 */
    public static String sanitizeFileName(String rawName) {
        if (rawName == null) return null;
        String base = rawName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.isEmpty() || ".".equals(base) || "..".equals(base)) {
            return null;
        }
        if (base.length() > 200) {
            int dot = base.lastIndexOf('.');
            String ext = (dot > 0 && base.length() - dot <= 12) ? base.substring(dot) : "";
            base = base.substring(0, Math.max(1, 200 - ext.length())) + ext;
        }
        return base;
    }

    /** 目录里已有同名文件时自动加 " (1)"、" (2)" 后缀，避免覆盖用户文件。 */
    public static File buildUploadTarget(File dir, String fileName) {
        File direct = new File(dir, fileName);
        if (!direct.exists()) {
            return direct;
        }
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, stem + "_" + System.currentTimeMillis() + ext);
    }

    // ------------------------------------------------------------------
    // 目录列举
    // ------------------------------------------------------------------

    /**
     * 列出目录内容：隐藏文件与 Android/data|obb 跳过，目录优先、再按名称不区分大小写排序。
     */
    public static List<FileItem> listDirectory(File dir) {
        List<FileItem> list = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) {
            return list;
        }
        File volumeRoot = findVolumeRoot(dir);
        File[] children = dir.listFiles();
        if (children == null) {
            return list;
        }
        for (File f : children) {
            if (f.isHidden()) continue;
            if (volumeRoot != null && isRestrictedSystemPath(f, volumeRoot)) continue;
            boolean isDir = f.isDirectory();
            list.add(new FileItem(
                    f.getName(),
                    f.getAbsolutePath(),
                    isDir ? 0L : f.length(),
                    f.lastModified(),
                    isDir,
                    isDir ? null : getMimeType(f.getName())
            ));
        }
        Collections.sort(list, new Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return list;
    }

    /** 兼容旧接口：按分类返回列表（内部复用 listDirectory）。 */
    public static List<FileItem> listFiles(String category) {
        File targetDir;
        if ("photos".equalsIgnoreCase(category)) {
            targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        } else if ("documents".equalsIgnoreCase(category)) {
            targetDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        } else {
            targetDir = getSharedStorageDir();
        }
        return listDirectory(targetDir);
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

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

    /** 兼容旧调用：写入共享目录 Downloads/ContextShared。 */
    public static File saveStreamToFile(Context context, InputStream in, String fileName) throws IOException {
        return saveStreamToFile(context, in, new File(getSharedStorageDir(), fileName));
    }

    public static File saveStreamToFile(Context context, InputStream in, File targetFile) throws IOException {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
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
