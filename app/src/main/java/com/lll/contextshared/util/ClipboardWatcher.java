package com.lll.contextshared.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class ClipboardWatcher {
    private static final String TAG = "ContextShared";
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
            deliver(text);
        }
    };

    /** 先投递成功再记为"已见"，避免投递失败（如主线程网络限制）后内容被永久吞掉。 */
    private void deliver(String text) {
        if (listener != null) {
            listener.onClipboardChanged(text);
        }
        lastCopiedText = text;
    }

    /**
     * 立即读一次剪贴板并回调（应用回到前台时调用）。
     *
     * <p>Android 10 起，应用不在前台/没有焦点时读剪贴板会被系统拒绝，日志形如：
     * {@code ClipboardService: Denying clipboard access to <pkg>, application is not in focus}，
     * 此时 {@code getPrimaryClip()} 返回 null。前台服务并不等于"有焦点"，所以
     * <b>手机→电脑的剪贴板同步必须在应用回到前台时才能读到</b>：
     * 用户在其他应用里复制后切回本应用，onResume 会走到这里，此时读得到，再广播给网页。
     *
     * <p>读不到（仍在后台）就静默跳过，不抛异常。
     */
    public void pollNow() {
        try {
            if (clipboardManager == null) {
                Log.d(TAG, "clipboard poll: 无 ClipboardManager");
                return;
            }
            boolean hasClip = clipboardManager.hasPrimaryClip();
            String text = getPrimaryClipText();
            Log.d(TAG, "clipboard poll: hasPrimaryClip=" + hasClip
                    + " textLength=" + (text == null ? -1 : text.length())
                    + " 与上次相同=" + (text != null && text.equals(lastCopiedText)));
            if (text != null && !text.isEmpty() && !text.equals(lastCopiedText)) {
                deliver(text);
            }
        } catch (Throwable t) {
            Log.d(TAG, "clipboard poll: 读取被拒或出错 " + t);
        }
    }

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
