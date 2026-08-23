package com.lll.contextshared.server;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final SecureRandom random = new SecureRandom();
    private String currentPinCode;
    private final Set<String> validTokens = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private boolean authRequired = true;

    public SessionManager() {
        refreshPinCode();
    }

    public synchronized String getPinCode() {
        return currentPinCode;
    }

    public synchronized String refreshPinCode() {
        int pin = 1000 + random.nextInt(9000);
        this.currentPinCode = String.valueOf(pin);
        return this.currentPinCode;
    }

    public synchronized boolean verifyPin(String pin) {
        if (!authRequired) return true;
        return currentPinCode != null && currentPinCode.equals(pin != null ? pin.trim() : null);
    }

    public String createSession() {
        String token = UUID.randomUUID().toString().replace("-", "");
        validTokens.add(token);
        return token;
    }

    public boolean isValidToken(String token) {
        if (!authRequired) return true;
        return token != null && validTokens.contains(token);
    }

    public void invalidateToken(String token) {
        if (token != null) {
            validTokens.remove(token);
        }
    }

    public boolean isAuthRequired() {
        return authRequired;
    }

    public void setAuthRequired(boolean authRequired) {
        this.authRequired = authRequired;
    }
}
