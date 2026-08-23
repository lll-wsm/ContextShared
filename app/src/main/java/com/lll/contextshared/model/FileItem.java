package com.lll.contextshared.model;

public class FileItem {
    private String name;
    private String path;
    private long size;
    private long lastModified;
    private boolean isDirectory;
    private String mimeType;

    public FileItem() {}

    public FileItem(String name, String path, long size, long lastModified, boolean isDirectory, String mimeType) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.lastModified = lastModified;
        this.isDirectory = isDirectory;
        this.mimeType = mimeType;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public long getLastModified() { return lastModified; }
    public void setLastModified(long lastModified) { this.lastModified = lastModified; }
    public boolean isDirectory() { return isDirectory; }
    public void setDirectory(boolean directory) { isDirectory = directory; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
}
