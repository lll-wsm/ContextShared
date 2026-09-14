package com.lll.contextshared.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import com.lll.contextshared.model.FileItem;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 通过 SAF（Storage Access Framework）访问系统受限目录 {@code Android/data}、{@code Android/obb}。
 *
 * <p><b>背景</b>：Android 11 起，「所有文件访问权限」(MANAGE_EXTERNAL_STORAGE) 明确排除
 * {@code /Android/data}、{@code /sdcard/Android} 及其大部分子目录（官方文档原文：
 * "except /Android/data/, /sdcard/Android, and most subdirectories of /sdcard/Android"），
 * 直接用 File API 读不到。Android 11/12 上存在一个官方选择器的空子：
 * {@code ACTION_OPEN_DOCUMENT_TREE} 本身禁止选择这些目录，但如果把
 * {@link DocumentsContract#EXTRA_INITIAL_URI} 指向 {@code Android/data}，
 * 用户仍可在该目录下点「使用此文件夹」完成授权（Android 13 已把这个漏洞堵掉，
 * 届时用户会发现该目录不可选，我们给出明确提示）。
 *
 * <p>授权后所有访问都走 {@link ContentResolver}（DocumentFile 语义），与 File API 是两套命名空间。
 * 本类负责把「文件系统路径」映射成 SAF 的 documentId，使上层（HTTP 接口、网页端）可以继续用普通路径。
 */
public final class SafDocuments {

    private static final String TAG = "ContextShared";
    private static final String EXTERNAL_STORAGE_PROVIDER = "com.android.externalstorage.documents";
    private static final String DIRECTORY_MIME = DocumentsContract.Document.MIME_TYPE_DIR;

    /** 需要 SAF 授权的相对目录（相对某个存储卷根）。 */
    private static final String[] SAF_ONLY_RELATIVE = {"Android/data", "Android/obb"};

    /** 一次已持久化的授权。 */
    public static final class Grant {
        public final Uri treeUri;
        /** SAF 里的树根 documentId，例如 {@code primary:Android/data} */
        public final String treeDocumentId;
        /** 卷标识，例如 primary / 1234-5678 */
        public final String volumeId;
        /** 相对卷根的路径，例如 Android/data */
        public final String relativePath;
        /** 卷根的本地路径，例如 /storage/emulated/0 */
        public final File volumeRoot;
        /** 该授权对应的本地路径（volumeRoot + relativePath），例如 /storage/emulated/0/Android/data */
        public final File mountPoint;

        Grant(Uri treeUri, String treeDocumentId, String volumeId, String relativePath,
              File volumeRoot, File mountPoint) {
            this.treeUri = treeUri;
            this.treeDocumentId = treeDocumentId;
            this.volumeId = volumeId;
            this.relativePath = relativePath;
            this.volumeRoot = volumeRoot;
            this.mountPoint = mountPoint;
        }
    }

    private static Context appContext;
    private static volatile List<Grant> grants = Collections.emptyList();
    /** 网页端请求授权时待处理的目标（data / obb），由 MainActivity 在前台时消费 */
    private static volatile String pendingTarget;

    private SafDocuments() {
    }

    /** 服务启动时调用一次。 */
    public static void init(Context context) {
        appContext = context != null ? context.getApplicationContext() : null;
        refreshGrants();
    }

    public static Context context() {
        return appContext;
    }

    // ------------------------------------------------------------------
    // 授权管理
    // ------------------------------------------------------------------

    /** 从系统的持久化 URI 授权里重建授权列表（应用重启后依然有效）。 */
    public static void refreshGrants() {
        List<Grant> result = new ArrayList<>();
        Context ctx = appContext;
        if (ctx == null) {
            grants = result;
            return;
        }
        try {
            for (UriPermission permission : ctx.getContentResolver().getPersistedUriPermissions()) {
                Uri uri = permission.getUri();
                if (uri == null || !EXTERNAL_STORAGE_PROVIDER.equals(uri.getAuthority()) || !permission.isReadPermission()) {
                    continue;
                }
                Grant grant = parseTreeUri(uri);
                if (grant != null) {
                    result.add(grant);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读取 SAF 授权失败", t);
        }
        grants = result;
    }

    /** 把 {@code content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata} 解析成 Grant。 */
    static Grant parseTreeUri(Uri uri) {
        String treeDocId = null;
        try {
            treeDocId = DocumentsContract.getTreeDocumentId(uri);
        } catch (Throwable ignored) {
        }
        if (treeDocId == null || !treeDocId.contains(":")) {
            return null;
        }
        int split = treeDocId.indexOf(':');
        String volumeId = treeDocId.substring(0, split);
        String relative = treeDocId.substring(split + 1);
        File volumeRoot = volumeRootFor(volumeId);
        if (volumeRoot == null) {
            return null;
        }
        File mountPoint = relative.isEmpty() ? volumeRoot : new File(volumeRoot, relative);
        return new Grant(uri, treeDocId, volumeId, relative, volumeRoot, mountPoint);
    }

    /** SAF 的卷标识 → 本地路径（primary 对应内部共享存储）。 */
    static File volumeRootFor(String volumeId) {
        if (volumeId == null) return null;
        if ("primary".equalsIgnoreCase(volumeId)) {
            try {
                File ext = Environment.getExternalStorageDirectory();
                if (ext != null) return ext.getCanonicalFile();
            } catch (Throwable ignored) {
            }
            return new File("/storage/emulated/0");
        }
        File candidate = new File("/storage/" + volumeId);
        return candidate.isDirectory() ? candidate : candidate;
    }

    /** 用户在选择器里授权某个目录后调用。 */
    public static boolean onTreeGranted(Uri treeUri) {
        Context ctx = appContext;
        if (ctx == null || treeUri == null) return false;
        try {
            ctx.getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            // 某些机型/版本只给只读，仍按读权限继续
            Log.w(TAG, "takePersistableUriPermission 失败（可能只读）", t);
        }
        refreshGrants();
        return findGrantFor(treeUri) != null;
    }

    public static List<Grant> getGrants() {
        return grants;
    }

    /** 仅供单元测试注入授权列表。 */
    static void setGrantsForTesting(List<Grant> testGrants) {
        grants = testGrants == null ? Collections.<Grant>emptyList() : testGrants;
    }

    private static Grant findGrantFor(Uri treeUri) {
        for (Grant grant : grants) {
            if (grant.treeUri.equals(treeUri)) return grant;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 路径判定
    // ------------------------------------------------------------------

    public static boolean isSafOnlyPath(String path) {
        return safTarget(path) != null;
    }

    /**
     * 若路径位于需要 SAF 授权的目录下，返回其目标类型：{@code "data"} 或 {@code "obb"}；否则 null。
     */
    public static String safTarget(String path) {
        if (path == null) return null;
        File root = StorageHelper.getBrowseRoot();
        return safTarget(path, root);
    }

    static String safTarget(String path, File volumeRoot) {
        if (path == null || volumeRoot == null) return null;
        String rootPath = volumeRoot.getPath();
        String target = path;
        if (target.equals(rootPath)) return null;
        if (target.startsWith(rootPath + File.separator)) {
            target = target.substring(rootPath.length() + 1);
        } else if (target.startsWith("/")) {
            return null;   // 不属于该卷
        }
        for (String relative : SAF_ONLY_RELATIVE) {
            if (target.equals(relative) || target.startsWith(relative + "/")) {
                return relative.substring("Android/".length());
            }
        }
        return null;
    }

    /** 找到覆盖该路径的授权（按路径最长匹配）。 */
    public static Grant findGrant(String path) {
        if (path == null) return null;
        try {
            File target = new File(path).getCanonicalFile();
            Grant best = null;
            for (Grant grant : grants) {
                String mount = grant.mountPoint.getCanonicalPath();
                String p = target.getPath();
                if (p.equals(mount) || p.startsWith(mount + File.separator)) {
                    if (best == null || mount.length() > best.mountPoint.getCanonicalPath().length()) {
                        best = grant;
                    }
                }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 路径 ↔ documentId 映射
    // ------------------------------------------------------------------

    /**
     * 本地路径 → SAF documentId。
     *
     * <p>例如 grant 为 {@code primary:Android/data}、路径为
     * {@code /storage/emulated/0/Android/data/com.tencent.mm} 时返回
     * {@code primary:Android/data/com.tencent.mm}。
     */
    static String documentIdFor(Grant grant, String path) {
        if (grant == null || path == null) return null;
        try {
            File target = new File(path).getCanonicalFile();
            String mount = grant.mountPoint.getCanonicalPath();
            String p = target.getPath();
            if (p.equals(mount)) return grant.treeDocumentId;
            if (!p.startsWith(mount + File.separator)) return null;
            String relative = p.substring(mount.length() + 1);
            return grant.treeDocumentId + "/" + relative;
        } catch (Throwable t) {
            return null;
        }
    }

    /** SAF display name → 本地路径（用于列举结果）。 */
    static String childPathOf(String parentPath, String displayName) {
        if (parentPath == null || displayName == null) return null;
        if (parentPath.endsWith(File.separator)) return parentPath + displayName;
        return parentPath + File.separator + displayName;
    }

    // ------------------------------------------------------------------
    // 列举 / 读 / 写
    // ------------------------------------------------------------------

    private static final String[] PROJECTION = {
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    };

    public static List<FileItem> list(Grant grant, String path) throws Exception {
        List<FileItem> items = new ArrayList<>();
        Context ctx = appContext;
        if (ctx == null || grant == null) return items;
        String docId = documentIdFor(grant, path);
        if (docId == null) return items;
        ContentResolver resolver = ctx.getContentResolver();
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(grant.treeUri, docId);
        try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) return items;
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                String mime = cursor.getString(1);
                long size = cursor.isNull(2) ? 0L : cursor.getLong(2);
                long lastModified = cursor.isNull(3) ? 0L : cursor.getLong(3);
                if (name == null || name.startsWith(".")) continue;
                boolean isDir = DIRECTORY_MIME.equals(mime);
                items.add(new FileItem(
                        name,
                        childPathOf(path, name),
                        isDir ? 0L : Math.max(0L, size),
                        lastModified,
                        isDir,
                        isDir ? null : StorageHelper.getMimeType(name)
                ));
            }
        }
        Collections.sort(items, new Comparator<FileItem>() {
            @Override
            public int compare(FileItem a, FileItem b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return items;
    }

    /** 该路径在授权范围内是否真实存在（目录或文件）。 */
    public static boolean exists(Grant grant, String path) {
        Context ctx = appContext;
        if (ctx == null || grant == null) return false;
        String docId = documentIdFor(grant, path);
        if (docId == null) return false;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, docId);
        try (Cursor cursor = ctx.getContentResolver().query(uri, PROJECTION, null, null, null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 该路径在授权范围内是否是目录。 */
    public static boolean isDirectory(Grant grant, String path) {
        Context ctx = appContext;
        if (ctx == null || grant == null) return false;
        String docId = documentIdFor(grant, path);
        if (docId == null) return false;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, docId);
        try (Cursor cursor = ctx.getContentResolver().query(uri,
                new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() && DIRECTORY_MIME.equals(cursor.getString(0));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 文件大小（用于 Content-Length / Range）。 */
    public static long size(Grant grant, String path) {
        Context ctx = appContext;
        if (ctx == null || grant == null) return -1L;
        String docId = documentIdFor(grant, path);
        if (docId == null) return -1L;
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, docId);
        try (android.os.ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r")) {
            return pfd != null ? pfd.getStatSize() : -1L;
        } catch (Throwable t) {
            try (Cursor cursor = ctx.getContentResolver().query(uri,
                    new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                    return cursor.getLong(0);
                }
            } catch (Throwable ignored) {
            }
            return -1L;
        }
    }

    public static InputStream openInput(Grant grant, String path) throws IOException {
        Context ctx = appContext;
        if (ctx == null || grant == null) throw new IOException("SAF 不可用");
        String docId = documentIdFor(grant, path);
        if (docId == null) throw new IOException("路径不在授权范围内");
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, docId);
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法打开文件");
        return in;
    }

    /**
     * 在授权范围内的某个目录下新建文件（用于上传）。
     *
     * @return 新文件的本地路径；失败返回 null
     */
    public static String createFile(Grant grant, String dirPath, String displayName, String mimeType) {
        Context ctx = appContext;
        if (ctx == null || grant == null) return null;
        String parentDocId = documentIdFor(grant, dirPath);
        if (parentDocId == null) return null;
        Uri parentUri = DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, parentDocId);
        try {
            Uri created = DocumentsContract.createDocument(ctx.getContentResolver(), parentUri,
                    mimeType == null ? "application/octet-stream" : mimeType, displayName);
            if (created == null) return null;
            String newDocId = DocumentsContract.getDocumentId(created);
            if (newDocId != null && newDocId.startsWith(grant.treeDocumentId)) {
                return childPathOf(grant.mountPoint.getPath(), newDocId.substring(grant.treeDocumentId.length()).replaceFirst("^/", ""));
            }
            return childPathOf(dirPath, displayName);
        } catch (Throwable t) {
            Log.w(TAG, "SAF 创建文件失败: " + displayName, t);
            return null;
        }
    }

    public static OutputStream openOutput(Grant grant, String path) throws IOException {
        Context ctx = appContext;
        if (ctx == null || grant == null) throw new IOException("SAF 不可用");
        String docId = documentIdFor(grant, path);
        if (docId == null) throw new IOException("路径不在授权范围内");
        Uri uri = DocumentsContract.buildDocumentUriUsingTree(grant.treeUri, docId);
        OutputStream out = ctx.getContentResolver().openOutputStream(uri, "w");
        if (out == null) throw new IOException("无法写入文件");
        return out;
    }

    /**
     * 在授权范围内逐级创建目录（上传整个文件夹时用）。
     *
     * @param basePath 已授权的起始目录
     * @param relativePath 形如 "a/b/c" 的相对路径（已由上层清洗）
     * @return 最终目录的本地路径；失败返回 null
     */
    public static String ensureDirectory(Grant grant, String basePath, String relativePath) {
        Context ctx = appContext;
        if (ctx == null || grant == null || relativePath == null) return null;
        String current = basePath;
        for (String segment : relativePath.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) continue;
            String next = childPathOf(current, segment);
            if (isDirectory(grant, next)) {
                current = next;
                continue;
            }
            String created = createFile(grant, current, segment, DIRECTORY_MIME);
            if (created == null) {
                Log.w(TAG, "SAF 创建目录失败: " + next);
                return null;
            }
            current = next;
        }
        return current;
    }

    /** 系统是否支持这种访问方式（Android 11+ 才有这套限制，低版本直接用 File API）。 */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** 供界面展示：某个目标是否已授权。 */
    public static boolean hasGrantFor(String target) {
        for (Grant grant : grants) {
            if (grant.relativePath.endsWith("Android/" + target)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 授权请求传递（服务端 → Activity）
    // ------------------------------------------------------------------

    /** 网页端点“在手机上授权”时调用，记录待处理目标。 */
    public static void requestAccess(String target) {
        pendingTarget = target;
    }

    public static boolean hasPendingRequest() {
        return pendingTarget != null;
    }

    /** Activity 在前台时取走待处理目标（只取一次）。 */
    public static String consumePendingTarget() {
        String target = pendingTarget;
        pendingTarget = null;
        return target;
    }
}
