package com.lll.contextshared.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.lll.contextshared.model.FileItem;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 文件浏览相关逻辑的单元测试：路径边界（越权/穿越/受限目录）、上传目标去重、目录列举排序与过滤。
 * 全部基于临时目录运行，可在 JVM 上直接执行。
 */
public class StorageHelperBrowseTest {

    private File root;
    private File outside;

    @Before
    public void setUp() throws Exception {
        root = canonicalTempDir("cs_browse_root");
        outside = canonicalTempDir("cs_browse_outside");
        StorageHelper.setBrowseRootForTesting(root);
    }

    @After
    public void tearDown() {
        StorageHelper.setBrowseRootForTesting(null);
        deleteRecursively(root);
        deleteRecursively(outside);
    }

    private File canonicalTempDir(String prefix) throws Exception {
        File dir = File.createTempFile(prefix, "");
        assertTrue(dir.delete());
        assertTrue(dir.mkdirs());
        return dir.getCanonicalFile();
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    // ---------------------------------------------------------------- 路径边界

    @Test
    public void resolveAcceptsRootAndNestedDirectory() throws Exception {
        File nested = new File(root, "Download/sub");
        assertTrue(nested.mkdirs());

        assertEquals(root, StorageHelper.resolveBrowsableDirectory(null));
        assertEquals(root, StorageHelper.resolveBrowsableDirectory(""));
        assertEquals(root, StorageHelper.resolveBrowsableDirectory("/"));
        assertEquals(root, StorageHelper.resolveBrowsableDirectory(root.getAbsolutePath()));
        assertEquals(nested.getCanonicalFile(),
                StorageHelper.resolveBrowsableDirectory(nested.getAbsolutePath()));
        // 相对路径按根目录解析
        assertEquals(nested.getCanonicalFile(),
                StorageHelper.resolveBrowsableDirectory("Download/sub"));
    }

    @Test
    public void resolveRejectsTraversalAndOutsidePaths() throws Exception {
        File sibling = new File(outside, "secret.txt");
        try (FileOutputStream fos = new FileOutputStream(sibling)) {
            fos.write("top secret".getBytes(StandardCharsets.UTF_8));
        }

        // 绝对路径越界
        assertNull(StorageHelper.resolveBrowsableDirectory(outside.getAbsolutePath()));
        // .. 穿越（canonical 化解后仍越界）
        assertNull(StorageHelper.resolveBrowsableDirectory(root.getAbsolutePath() + "/../" + outside.getName()));
        assertNull(StorageHelper.resolveBrowsableDirectory(".."));
        assertNull(StorageHelper.resolveBrowsableDirectory("../" + outside.getName()));
        // 不存在的目录
        assertNull(StorageHelper.resolveBrowsableDirectory(new File(root, "nope").getAbsolutePath()));
        // 普通文件不是目录
        File file = new File(root, "a.txt");
        assertTrue(file.createNewFile());
        assertNull(StorageHelper.resolveBrowsableDirectory(file.getAbsolutePath()));
    }

    @Test
    public void resolveRejectsAndroidDataAndObb() throws Exception {
        File dataDir = new File(root, "Android/data/com.example");
        File obbDir = new File(root, "Android/obb/com.example");
        File androidDir = new File(root, "Android");
        assertTrue(dataDir.mkdirs());
        assertTrue(obbDir.mkdirs());

        // Android 目录本身可以进，但 data/obb 必须挡住
        assertEquals(androidDir.getCanonicalFile(),
                StorageHelper.resolveBrowsableDirectory(androidDir.getAbsolutePath()));
        assertNull(StorageHelper.resolveBrowsableDirectory(dataDir.getAbsolutePath()));
        assertNull(StorageHelper.resolveBrowsableDirectory(obbDir.getAbsolutePath()));
        assertNull(StorageHelper.resolveBrowsableDirectory("Android/data"));
        assertTrue(StorageHelper.isRestrictedSystemPath(new File(root, "Android/obb/x")));
        assertFalse(StorageHelper.isRestrictedSystemPath(new File(root, "Android/media/x")));
    }

    // ---------------------------------------------------------------- 上传

    @Test
    public void sanitizeFileNameStripsPathAndControlChars() {
        assertEquals("a.txt", StorageHelper.sanitizeFileName("a.txt"));
        assertEquals("a.txt", StorageHelper.sanitizeFileName("../../etc/passwd/a.txt"));
        assertEquals("passwd", StorageHelper.sanitizeFileName("/etc/passwd"));
        assertEquals("passwd", StorageHelper.sanitizeFileName("..\\..\\windows\\passwd"));
        assertEquals("evil.sh", StorageHelper.sanitizeFileName("evil\u0000.sh"));
        assertNull(StorageHelper.sanitizeFileName(null));
        assertNull(StorageHelper.sanitizeFileName(""));
        assertNull(StorageHelper.sanitizeFileName("   "));
        assertNull(StorageHelper.sanitizeFileName(".."));
        assertNull(StorageHelper.sanitizeFileName("."));
        assertNull(StorageHelper.sanitizeFileName("dir/"));
        assertEquals(200, StorageHelper.sanitizeFileName("x".repeat(400) + ".txt").length());
    }

    @Test
    public void buildUploadTargetDoesNotOverwriteExistingFile() throws Exception {
        File dir = new File(root, "Download");
        assertTrue(dir.mkdirs());
        assertTrue(new File(dir, "note.txt").createNewFile());

        // 同名文件已存在 → 自动加 (1)，不覆盖用户文件
        assertEquals("note (1).txt", StorageHelper.buildUploadTarget(dir, "note.txt").getName());

        assertTrue(new File(dir, "note (1).txt").createNewFile());
        assertEquals("note (2).txt", StorageHelper.buildUploadTarget(dir, "note.txt").getName());

        // 无扩展名与全新文件名
        assertEquals("fresh.txt", StorageHelper.buildUploadTarget(dir, "fresh.txt").getName());
        assertEquals("plain", StorageHelper.buildUploadTarget(dir, "plain").getName());
    }

    // ---------------------------------------------------------------- 列举

    @Test
    public void listDirectorySortsDirsFirstAndHidesSystemEntries() throws Exception {
        File dir = new File(root, "Download");
        assertTrue(dir.mkdirs());
        assertTrue(new File(dir, "zdir").mkdirs());
        assertTrue(new File(dir, "adir").mkdirs());
        assertTrue(new File(dir, "b.txt").createNewFile());
        assertTrue(new File(dir, "A.txt").createNewFile());
        assertTrue(new File(dir, ".hidden").createNewFile());
        assertTrue(new File(root, "Android/data/other").mkdirs());

        List<FileItem> items = StorageHelper.listDirectory(dir);
        assertEquals(4, items.size());
        assertEquals("adir", items.get(0).getName());
        assertEquals("zdir", items.get(1).getName());
        assertTrue(items.get(0).isDirectory());
        assertTrue(items.get(1).isDirectory());
        assertEquals("A.txt", items.get(2).getName());
        assertEquals("b.txt", items.get(3).getName());
        assertFalse(items.get(2).isDirectory());
        assertEquals("text/plain", items.get(2).getMimeType());
        assertTrue(items.get(2).getPath().startsWith(dir.getAbsolutePath()));

        // Android/data 必须从 Android 目录的列表中隐藏
        assertTrue(StorageHelper.listDirectory(new File(root, "Android")).isEmpty());
    }

    @Test
    public void listDirectoryReturnsEmptyForInvalidInput() {
        assertTrue(StorageHelper.listDirectory(null).isEmpty());
        assertTrue(StorageHelper.listDirectory(new File(root, "missing")).isEmpty());
    }

    @Test
    public void pathAllowedAlsoRejectsRestrictedSystemPaths() throws Exception {
        // isPathAllowed 的白名单来自系统目录，这里注入临时目录保证可重现
        StorageHelper.setExtraAllowedRoots(Collections.singletonList(root));
        try {
            assertTrue(StorageHelper.isPathAllowed(new File(root, "Download/b.txt")));
            assertFalse(StorageHelper.isPathAllowed(new File(root, "Android/data/app/secret.db")));
            assertFalse(StorageHelper.isPathAllowed(new File(root, "Android/obb/app/base.obb")));
        } finally {
            StorageHelper.setExtraAllowedRoots(null);
        }
    }

    // ---------------------------------------------------------------- 多存储卷

    @Test
    public void resolveFindsCorrectVolumeRootAmongMultipleVolumes() throws Exception {
        File sd = canonicalTempDir("cs_browse_sd");
        try {
            StorageHelper.setVolumesForTesting(java.util.Arrays.asList(
                    new StorageHelper.Volume(root, false, null),
                    new StorageHelper.Volume(sd, true, "SD card")));

            // 两个卷的根都能解析，并且各自能被识别为对应卷
            assertEquals(root, StorageHelper.resolveBrowsableDirectory(root.getAbsolutePath()));
            assertEquals(sd, StorageHelper.resolveBrowsableDirectory(sd.getAbsolutePath()));
            assertEquals(root, StorageHelper.findVolumeRoot(new File(root, "Download/x")));
            assertEquals(sd, StorageHelper.findVolumeRoot(new File(sd, "Music/x")));
            assertNull(StorageHelper.findVolumeRoot(outside));

            // 相对路径落在默认（内部存储）卷
            assertTrue(new File(root, "DCIM").mkdirs());
            assertEquals(new File(root, "DCIM").getCanonicalFile(),
                    StorageHelper.resolveBrowsableDirectory("DCIM"));

            // 不存在的卷外路径仍然拒绝
            assertNull(StorageHelper.resolveBrowsableDirectory(outside.getAbsolutePath()));
        } finally {
            StorageHelper.setBrowseRootForTesting(root);
            deleteRecursively(sd);
        }
    }

    @Test
    public void restrictedDirsAreBlockedInsideEveryVolume() throws Exception {
        File sd = canonicalTempDir("cs_browse_sd2");
        try {
            StorageHelper.setVolumesForTesting(java.util.Arrays.asList(
                    new StorageHelper.Volume(root, false, null),
                    new StorageHelper.Volume(sd, true, null)));

            File sdData = new File(sd, "Android/data/com.example");
            assertTrue(sdData.mkdirs());
            assertTrue(new File(sd, "Music").mkdirs());

            assertNull("外置卷里的 Android/data 也必须挡住",
                    StorageHelper.resolveBrowsableDirectory(sdData.getAbsolutePath()));
            assertNotNull(StorageHelper.resolveBrowsableDirectory(new File(sd, "Music").getAbsolutePath()));
            assertTrue("Android 目录下 data 应被隐藏",
                    StorageHelper.listDirectory(new File(sd, "Android")).isEmpty());
        } finally {
            StorageHelper.setBrowseRootForTesting(root);
            deleteRecursively(sd);
        }
    }
}
