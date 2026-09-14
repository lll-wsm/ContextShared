package com.lll.contextshared.util;

import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * SAF（Android/data、Android/obb 受限目录）路径映射与授权匹配的单元测试。
 *
 * <p>只测纯逻辑（路径 ↔ documentId、受限目录判定、授权匹配），不依赖真机；
 * {@code Grant.treeUri} 传 null（映射逻辑不需要它）。
 */
public class SafPathMappingTest {

    private static final File PRIMARY = new File("/storage/emulated/0");
    private static final File SD = new File("/storage/1234-5678");

    private static SafDocuments.Grant primaryDataGrant() {
        return new SafDocuments.Grant(null, "primary:Android/data", "primary", "Android/data",
                PRIMARY, new File(PRIMARY, "Android/data"));
    }

    @After
    public void tearDown() {
        SafDocuments.setGrantsForTesting(null);
    }

    // ---------------------------------------------------------------- 受限目录判定

    @Test
    public void safTargetDetectsRestrictedDirectories() {
        assertEquals("data", SafDocuments.safTarget("/storage/emulated/0/Android/data", PRIMARY));
        assertEquals("data", SafDocuments.safTarget("/storage/emulated/0/Android/data/com.tencent.mm/files", PRIMARY));
        assertEquals("obb", SafDocuments.safTarget("/storage/emulated/0/Android/obb/com.game", PRIMARY));
        assertEquals("data", SafDocuments.safTarget("Android/data", PRIMARY));

        // 其它目录不受限
        assertNull(SafDocuments.safTarget("/storage/emulated/0/Android", PRIMARY));
        assertNull(SafDocuments.safTarget("/storage/emulated/0/Android/media", PRIMARY));
        assertNull(SafDocuments.safTarget("/storage/emulated/0/Download", PRIMARY));
        assertNull(SafDocuments.safTarget("/storage/emulated/0", PRIMARY));
        // 不属于该卷
        assertNull(SafDocuments.safTarget("/storage/1234-5678/Android/data", PRIMARY));
        // 只是名字里含 data 的普通目录不受限
        assertNull(SafDocuments.safTarget("/storage/emulated/0/Download/data", PRIMARY));
        assertNull(SafDocuments.safTarget("/storage/emulated/0/Android/database", PRIMARY));
    }

    @Test
    public void safTargetWorksPerVolume() {
        assertEquals("data", SafDocuments.safTarget("/storage/1234-5678/Android/data/app", SD));
        assertNull(SafDocuments.safTarget("/storage/emulated/0/Android/data", SD));
    }

    // ---------------------------------------------------------------- documentId 映射

    @Test
    public void documentIdForMapsMountPointAndChildren() {
        SafDocuments.Grant grant = primaryDataGrant();
        assertEquals("primary:Android/data",
                SafDocuments.documentIdFor(grant, "/storage/emulated/0/Android/data"));
        assertEquals("primary:Android/data/com.tencent.mm",
                SafDocuments.documentIdFor(grant, "/storage/emulated/0/Android/data/com.tencent.mm"));
        assertEquals("primary:Android/data/com.tencent.mm/files",
                SafDocuments.documentIdFor(grant, "/storage/emulated/0/Android/data/com.tencent.mm/files"));
        // 挂载点之外
        assertNull(SafDocuments.documentIdFor(grant, "/storage/emulated/0/Android/obb"));
        assertNull(SafDocuments.documentIdFor(grant, "/storage/emulated/0/Download"));
        assertNull(SafDocuments.documentIdFor(null, "/storage/emulated/0/Android/data"));
    }

    @Test
    public void childPathOfJoinsSegments() {
        assertEquals("/storage/emulated/0/Android/data/a",
                SafDocuments.childPathOf("/storage/emulated/0/Android/data", "a"));
        assertEquals("/storage/emulated/0/Android/data/a/b",
                SafDocuments.childPathOf("/storage/emulated/0/Android/data/a", "b"));
        assertEquals("/x/y", SafDocuments.childPathOf("/x/", "y"));
    }

    // ---------------------------------------------------------------- 授权匹配

    @Test
    public void findGrantMatchesSubtreeAndPrefersLongest() {
        SafDocuments.Grant data = primaryDataGrant();
        SafDocuments.Grant dataSub = new SafDocuments.Grant(null, "primary:Android/data/com.tencent.mm",
                "primary", "Android/data/com.tencent.mm", PRIMARY,
                new File(PRIMARY, "Android/data/com.tencent.mm"));
        SafDocuments.setGrantsForTesting(Arrays.asList(data, dataSub));

        // 同一子树上的更长授权优先
        SafDocuments.Grant found = SafDocuments.findGrant("/storage/emulated/0/Android/data/com.tencent.mm/files/x.db");
        assertNotNull(found);
        assertEquals("Android/data/com.tencent.mm", found.relativePath);
        assertEquals("primary:Android/data/com.tencent.mm/files/x.db",
                SafDocuments.documentIdFor(found, "/storage/emulated/0/Android/data/com.tencent.mm/files/x.db"));

        // 其它子目录回落到较短的那个授权
        SafDocuments.Grant other = SafDocuments.findGrant("/storage/emulated/0/Android/data/org.telegram.messenger");
        assertNotNull(other);
        assertEquals("Android/data", other.relativePath);

        // 授权范围之外
        assertNull(SafDocuments.findGrant("/storage/emulated/0/Android/obb/x"));
        assertNull(SafDocuments.findGrant("/storage/emulated/0/Download"));
    }

    @Test
    public void hasGrantForDistinguishesDataAndObb() {
        SafDocuments.setGrantsForTesting(Collections.singletonList(primaryDataGrant()));
        assertTrue(SafDocuments.hasGrantFor("data"));
        assertFalse(SafDocuments.hasGrantFor("obb"));

        SafDocuments.setGrantsForTesting(Collections.singletonList(new SafDocuments.Grant(
                null, "primary:Android/obb", "primary", "Android/obb", PRIMARY, new File(PRIMARY, "Android/obb"))));
        assertTrue(SafDocuments.hasGrantFor("obb"));
        assertFalse(SafDocuments.hasGrantFor("data"));

        SafDocuments.setGrantsForTesting(null);
        assertFalse(SafDocuments.hasGrantFor("data"));
        assertFalse(SafDocuments.hasGrantFor("obb"));
    }

    @Test
    public void secondaryVolumeGrantIsResolved() {
        SafDocuments.Grant sdGrant = new SafDocuments.Grant(null, "1234-5678:Android/data",
                "1234-5678", "Android/data", SD, new File(SD, "Android/data"));
        SafDocuments.setGrantsForTesting(Collections.singletonList(sdGrant));

        SafDocuments.Grant found = SafDocuments.findGrant("/storage/1234-5678/Android/data/com.foo");
        assertNotNull(found);
        assertEquals("1234-5678:Android/data/com.foo",
                SafDocuments.documentIdFor(found, "/storage/1234-5678/Android/data/com.foo"));
        // 内部存储的同名目录不在该授权范围内
        assertNull(SafDocuments.findGrant("/storage/emulated/0/Android/data/com.foo"));
    }
}
