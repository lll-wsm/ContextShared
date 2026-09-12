package com.lll.contextshared.model;

public class DeviceInfo {
    private String deviceName;
    private int batteryLevel;
    private boolean isCharging;
    private long freeStorageBytes;
    private long totalStorageBytes;
    private boolean authRequired;
    /** Android 11+ 是否已获得「所有文件访问权限」；未获得时网页端只能看到部分目录。 */
    private boolean hasAllFilesAccess;
    /** 文件浏览根目录（共享存储根）。 */
    private String browseRoot;

    public DeviceInfo() {}

    public DeviceInfo(String deviceName, int batteryLevel, boolean isCharging,
                      long freeStorageBytes, long totalStorageBytes, boolean authRequired) {
        this(deviceName, batteryLevel, isCharging, freeStorageBytes, totalStorageBytes, authRequired, false, null);
    }

    public DeviceInfo(String deviceName, int batteryLevel, boolean isCharging,
                      long freeStorageBytes, long totalStorageBytes, boolean authRequired,
                      boolean hasAllFilesAccess, String browseRoot) {
        this.deviceName = deviceName;
        this.batteryLevel = batteryLevel;
        this.isCharging = isCharging;
        this.freeStorageBytes = freeStorageBytes;
        this.totalStorageBytes = totalStorageBytes;
        this.authRequired = authRequired;
        this.hasAllFilesAccess = hasAllFilesAccess;
        this.browseRoot = browseRoot;
    }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public int getBatteryLevel() { return batteryLevel; }
    public void setBatteryLevel(int batteryLevel) { this.batteryLevel = batteryLevel; }
    public boolean isCharging() { return isCharging; }
    public void setCharging(boolean charging) { isCharging = charging; }
    public long getFreeStorageBytes() { return freeStorageBytes; }
    public void setFreeStorageBytes(long freeStorageBytes) { this.freeStorageBytes = freeStorageBytes; }
    public long getTotalStorageBytes() { return totalStorageBytes; }
    public void setTotalStorageBytes(long totalStorageBytes) { this.totalStorageBytes = totalStorageBytes; }
    public boolean isAuthRequired() { return authRequired; }
    public void setAuthRequired(boolean authRequired) { this.authRequired = authRequired; }
    public boolean isHasAllFilesAccess() { return hasAllFilesAccess; }
    public void setHasAllFilesAccess(boolean hasAllFilesAccess) { this.hasAllFilesAccess = hasAllFilesAccess; }
    public String getBrowseRoot() { return browseRoot; }
    public void setBrowseRoot(String browseRoot) { this.browseRoot = browseRoot; }
}
