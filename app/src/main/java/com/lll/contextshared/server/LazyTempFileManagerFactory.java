package com.lll.contextshared.server;

import android.content.Context;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 惰性临时文件管理器工厂。
 *
 * <p>背景：NanoHTTPD 默认的 {@code DefaultTempFileManager} 会在构造函数里就解析并访问
 * {@code java.io.tmpdir}（在 Android 上被框架设置为应用的 cacheDir，属于 CE 凭据加密存储）：
 *
 * <pre>
 * this.tmpdir = new File(System.getProperty("java.io.tmpdir"));
 * if (!tmpdir.exists()) { tmpdir.mkdirs(); }
 * </pre>
 *
 * <p>而 {@code ClientHandler.run()} 对<b>每一个新建的 TCP 连接</b>都会执行一次
 * {@code tempFileManagerFactory.create()}（keep-alive 复用的连接不会重复执行）。因此在设备
 * 息屏/锁屏、CE 存储访问被系统挂起的场景下，每个新连接的第一个请求都会被阻塞数十秒，
 * 表现为"页面要等几十秒才整个出现、之后同一连接内的请求又瞬间返回"。
 *
 * <p>本实现把目录解析与文件创建推迟到真正需要临时文件时——只有 {@code parseBody} 处理
 * 体积超过 {@code MEMORY_STORE_LIMIT}(1024 字节) 的上传请求才会用到，普通 GET/API 请求
 * 不会再触发任何文件系统访问。
 */
public class LazyTempFileManagerFactory implements NanoHTTPD.TempFileManagerFactory {

    private final File tempDir;

    public LazyTempFileManagerFactory(Context context) {
        this.tempDir = context != null ? context.getCacheDir() : null;
    }

    @Override
    public NanoHTTPD.TempFileManager create() {
        return new LazyTempFileManager(tempDir);
    }

    private static final class LazyTempFileManager implements NanoHTTPD.TempFileManager {
        private final File dir;
        private final List<NanoHTTPD.TempFile> tempFiles = new ArrayList<>();

        LazyTempFileManager(File dir) {
            this.dir = dir;
        }

        @Override
        public void clear() {
            for (NanoHTTPD.TempFile tempFile : tempFiles) {
                try {
                    tempFile.delete();
                } catch (Exception ignored) {
                    // 临时文件清理失败不应影响请求处理
                }
            }
            tempFiles.clear();
        }

        @Override
        public NanoHTTPD.TempFile createTempFile(String filenameHint) throws Exception {
            File target = File.createTempFile("cs_tmp_", ".tmp", dir);
            LazyTempFile tempFile = new LazyTempFile(target);
            tempFiles.add(tempFile);
            return tempFile;
        }
    }

    private static final class LazyTempFile implements NanoHTTPD.TempFile {
        private final File file;

        LazyTempFile(File file) {
            this.file = file;
        }

        @Override
        public void delete() throws Exception {
            if (file.exists() && !file.delete()) {
                throw new Exception("cannot delete " + file.getAbsolutePath());
            }
        }

        @Override
        public String getName() {
            return file.getAbsolutePath();
        }

        @Override
        public OutputStream open() throws Exception {
            return new FileOutputStream(file);
        }
    }
}
