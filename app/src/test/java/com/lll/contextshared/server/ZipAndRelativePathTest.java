package com.lll.contextshared.server;

import org.junit.Test;

import com.lll.contextshared.util.StorageHelper;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * ZIP 打包命名 与 文件夹上传相对路径清洗 的单元测试（纯逻辑，可在 JVM 上跑）。
 */
public class ZipAndRelativePathTest {

    // ---------------------------------------------------------------- ZIP 条目命名

    @Test
    public void baseNameHandlesFilesDirectoriesAndTrailingSlash() {
        assertEquals("c.txt", AppHttpServer.baseName("/storage/emulated/0/a/b/c.txt"));
        assertEquals("b", AppHttpServer.baseName("/storage/emulated/0/a/b/"));
        assertEquals("b", AppHttpServer.baseName("/storage/emulated/0/a/b"));
        assertEquals("中文目录", AppHttpServer.baseName("/storage/emulated/0/中文目录"));
        assertEquals("download", AppHttpServer.baseName(null));
        assertEquals("download", AppHttpServer.baseName("/"));
    }

    @Test
    public void uniqueEntryNameDeduplicatesWithinSameLevel() {
        Set<String> used = new HashSet<>();
        assertEquals("a.txt", AppHttpServer.uniqueEntryName(used, "a.txt"));
        assertEquals("a (1).txt", AppHttpServer.uniqueEntryName(used, "a.txt"));
        assertEquals("a (2).txt", AppHttpServer.uniqueEntryName(used, "a.txt"));
        // 无扩展名
        assertEquals("plain", AppHttpServer.uniqueEntryName(used, "plain"));
        assertEquals("plain (1)", AppHttpServer.uniqueEntryName(used, "plain"));
        // 不同名字不受影响
        assertEquals("b.txt", AppHttpServer.uniqueEntryName(used, "b.txt"));
        // 同名目录
        assertEquals("dir", AppHttpServer.uniqueEntryName(used, "dir"));
        assertEquals("dir (1)", AppHttpServer.uniqueEntryName(used, "dir"));
    }

    // ---------------------------------------------------------------- 文件夹上传的相对路径

    @Test
    public void sanitizeRelativePathKeepsStructure() {
        assertEquals("sub/dir", StorageHelper.sanitizeRelativePath("sub/dir"));
        assertEquals("sub/dir", StorageHelper.sanitizeRelativePath("sub\\dir"));
        assertEquals("sub/dir", StorageHelper.sanitizeRelativePath("/sub/dir/"));
        assertEquals("sub/dir", StorageHelper.sanitizeRelativePath("sub//dir"));
        assertEquals("中文 目录/子目录", StorageHelper.sanitizeRelativePath("中文 目录/子目录"));
    }

    @Test
    public void sanitizeRelativePathCannotEscapeTargetDirectory() {
        // 全是 .. 的路径被清空
        assertNull(StorageHelper.sanitizeRelativePath(".."));
        assertNull(StorageHelper.sanitizeRelativePath("../.."));
        assertNull(StorageHelper.sanitizeRelativePath("..\\..\\.."));
        assertNull(StorageHelper.sanitizeRelativePath("/.."));
        assertNull(StorageHelper.sanitizeRelativePath(""));
        assertNull(StorageHelper.sanitizeRelativePath(null));
        // 混合情况下 .. 段被丢弃，结果仍在目标目录内
        assertEquals("a/b", StorageHelper.sanitizeRelativePath("a/../b"));
        assertEquals("a/b", StorageHelper.sanitizeRelativePath("a/../../b"));
        assertEquals("a/b", StorageHelper.sanitizeRelativePath("../a/b"));
    }

    @Test
    public void sanitizeRelativePathStripsControlCharsAndKeepsNamesReadable() {
        assertEquals("a/b", StorageHelper.sanitizeRelativePath("a\u0000/b"));
        assertEquals("a b/c", StorageHelper.sanitizeRelativePath("a b/c"));
        // 长度上限（防超长路径）
        String longPath = StorageHelper.sanitizeRelativePath(("x".repeat(50) + "/").repeat(20));
        assertNotNull(longPath);
        assertTrue(longPath.length() <= 420);
    }

    @Test
    public void zipEntryNameForChildKeepsParentPrefix() {
        // 回归：子文件必须带上父目录前缀，否则会被放到 zip 根目录
        assertEquals("sub/b.bin", AppHttpServer.zipEntryNameForChild("sub", "b.bin"));
        assertEquals("sub/deep/c.txt", AppHttpServer.zipEntryNameForChild("sub/deep", "c.txt"));
        assertEquals("a.txt", AppHttpServer.zipEntryNameForChild("", "a.txt"));
        assertEquals("b.bin", AppHttpServer.zipEntryNameForChild(null, "b.bin"));
        assertEquals("sub/b.bin", AppHttpServer.zipEntryNameForChild("sub/", "b.bin"));
        // 顶层目录里重名时，子层各自去重互不影响
        Set<String> level1 = new HashSet<>();
        assertEquals("d1/x.txt", AppHttpServer.zipEntryNameForChild("d1", AppHttpServer.uniqueEntryName(level1, "x.txt")));
        assertEquals("d2/x.txt", AppHttpServer.zipEntryNameForChild("d2", AppHttpServer.uniqueEntryName(new HashSet<String>(), "x.txt")));
        // 同一层重复名仍会加后缀，且带上父前缀
        assertEquals("d1/x (1).txt", AppHttpServer.zipEntryNameForChild("d1", AppHttpServer.uniqueEntryName(level1, "x.txt")));
    }
}
