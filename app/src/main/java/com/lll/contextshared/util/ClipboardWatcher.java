package com.lll.contextshared.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public class ClipboardWatcher {
    public interface OnClipboardChangeListener {
        void onClipboardChanged(String text);
    }

    private final Context context;
    private final ClipboardManager clipboardManager;
    private final Handler mainHandler;
    private OnClipboardChangeListener listener;
    private String lastCopiedText = "";

    public ClipboardWatcher(Context context) {
        this.context = context.getApplicationContext();
        this.clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(OnClipboardChangeListener listener) {
        this.listener = listener;
    }

    public void startWatching() {
        if (clipboardManager != null) {
            clipboardManager.addPrimaryClipChangedListener(clipListener);
        }
    }

    public void stopWatching() {
        if (clipboardManager != null) {
            clipboardManager.removePrimaryClipChangedListener(clipListener);
        }
    }

    private final ClipboardManager.OnPrimaryClipChangedListener clipListener = () -> {
        String text = getPrimaryClipText();
        if (text != null && !text.isEmpty() && !text.equals(lastCopiedText)) {
            lastCopiedText = text;
            if (listener != null) {
                listener.onClipboardChanged(text);
            }
        }
    };

    public String getPrimaryClipText() {
        if (clipboardManager != null && clipboardManager.hasPrimaryClip()) {
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData != null && clipData.getItemCount() > 0) {
                CharSequence text = clipData.getItemAt(0).getText();
                return text != null ? text.toString() : null;
            }
        }
        return null;
    }

    public void setPrimaryClipText(String text) {
        if (text == null) return;
        this.lastCopiedText = text;
        mainHandler.post(() -> {
            if (clipboardManager != null) {
                ClipData clip = ClipData.newPlainText("ContextShared", text);
                clipboardManager.setPrimaryClip(clip);
            }
        });
    }
}
