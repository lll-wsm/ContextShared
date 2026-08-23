package com.lll.contextshared.model;

import com.google.gson.Gson;

public class WsMessage {
    private static final Gson GSON = new Gson();

    private String type;
    private Object payload;
    private long timestamp;

    public WsMessage() {}

    public WsMessage(String type, Object payload) {
        this.type = type;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Object getPayload() { return payload; }
    public void setPayload(Object payload) { this.payload = payload; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String toJson() {
        return GSON.toJson(this);
    }

    public static WsMessage fromJson(String json) {
        return GSON.fromJson(json, WsMessage.class);
    }
}
