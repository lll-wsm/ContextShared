package com.lll.contextshared.model;

public class DeviceInfo {
    private String deviceName;
    private int batteryLevel;
    private boolean isCharging;
    private long freeStorageBytes;
    private long totalStorageBytes;
    private boolean authRequired;

    public DeviceInfo() {}

    public DeviceInfo(String deviceName, int batteryLevel, boolean isCharging, 
                      long freeStorageBytes, long totalStorageBytes, boolean authRequired) {
        this.deviceName = deviceName;
        this.batteryLevel = batteryLevel;
        this.isCharging = isCharging;
        this.freeStorageBytes = freeStorageBytes;
        this.totalStorageBytes = totalStorageBytes;
        this.authRequired = authRequired;
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
}
