package com.lll.contextshared.server;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 下载文件名编码测试。
 *
 * <p>HTTP 头只能是 ASCII，中文文件名必须走 RFC 6266/5987 的 {@code filename*=UTF-8''...}，
 * 否则浏览器拿到的是 {@code filename="??????.txt"}（修复前实测如此）。
 */
public class DownloadFilenameTest {

    private static void assertAsciiOnly(String header) {
        for (int i = 0; i < header.length(); i++) {
            assertTrue("响应头出现非 ASCII 字符(0x" + Integer.toHexString(header.charAt(i)) + "): " + header,
                    header.charAt(i) < 128);
        }
    }

    @Test
    public void asciiNameKeepsSameNameInBothForms() {
        String header = AppHttpServer.buildContentDisposition("report.txt");
        assertEquals("attachment; filename=\"report.txt\"; filename*=UTF-8''report.txt", header);
        assertAsciiOnly(header);
    }

    @Test
    public void chineseNameIsPercentEncodedForModernBrowsers() {
        String header = AppHttpServer.buildContentDisposition("中文测试文件.txt");

        assertTrue("必须带 UTF-8 扩展文件名: " + header,
                header.contains("filename*=UTF-8''%E4%B8%AD%E6%96%87%E6%B5%8B%E8%AF%95%E6%96%87%E4%BB%B6.txt"));
        // 回退名里每个非 ASCII 字符变成一个下划线，仍能看出扩展名
        assertTrue("回退名应保留扩展名: " + header, header.contains("filename=\"______.txt\""));
        assertAsciiOnly(header);
        assertFalse("不能残留问号: " + header, header.contains("?"));
    }

    @Test
    public void spaceBecomesPercent20NotPlus() {
        String header = AppHttpServer.buildContentDisposition("我的 报告 v2.pdf");
        assertTrue(header, header.contains("filename*=UTF-8''%E6%88%91%E7%9A%84%20%E6%8A%A5%E5%91%8A%20v2.pdf"));
        assertFalse("RFC5987 里空格必须编码成 %20，不能是 +", header.contains("+"));
        assertAsciiOnly(header);
    }

    @Test
    public void quotesAndBackslashesCannotBreakOutOfHeader() {
        String header = AppHttpServer.buildContentDisposition("a\"b\\c;d.txt");
        // 回退名里危险字符被替换
        assertTrue(header, header.contains("filename=\"a_b_c_d.txt\""));
        // 扩展名里被百分号编码
        assertTrue(header, header.contains("filename*=UTF-8''a%22b%5Cc%3Bd.txt"));
        // 除了包住回退名的两个引号，不能再出现裸引号
        assertEquals("回退名之外的引号必须被转义掉", 2, countChar(header, '"'));
        assertAsciiOnly(header);
    }

    @Test
    public void crlfInjectionIsNeutralized() {
        String header = AppHttpServer.buildContentDisposition("evil\r\nSet-Cookie: x=1.txt");
        assertFalse("不能出现裸 CR", header.contains("\r"));
        assertFalse("不能出现裸 LF", header.contains("\n"));
        assertTrue(header, header.contains("%0D%0A"));
        assertAsciiOnly(header);
    }

    @Test
    public void percentAndEmojiAreEncoded() {
        String header = AppHttpServer.buildContentDisposition("100%_😀.txt");
        assertTrue(header, header.contains("%25"));            // 字面 % 必须转义
        assertTrue(header, header.contains("_%F0%9F%98%80.txt")); // 4 字节 emoji
        assertAsciiOnly(header);
    }

    @Test
    public void nullAndBlankNamesFallBackSafely() {
        String header = AppHttpServer.buildContentDisposition(null);
        assertEquals("attachment; filename=\"download\"; filename*=UTF-8''download", header);

        String blank = AppHttpServer.buildContentDisposition("中文");
        assertTrue(blank, blank.contains("filename=\"__\""));
        assertAsciiOnly(blank);
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }
}
