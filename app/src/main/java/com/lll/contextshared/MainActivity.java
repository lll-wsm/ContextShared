package com.lll.contextshared;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.lll.contextshared.server.ServerManager;
import com.lll.contextshared.service.WebService;
import com.lll.contextshared.util.NetworkUtils;
import com.lll.contextshared.util.QrCodeGenerator;
import com.lll.contextshared.util.StorageHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ServerManager.ServerStateListener {
    private static final int PERMISSION_REQUEST_CODE = 101;

    private ViewGroup rootContainer;
    private SwitchMaterial switchServer;
    private TextView tvServerStatus;
    private TextView tvServerHint;
    private ImageView ivStatusDot;
    private TextView tvNetworkInfo;
    private Button btnOpenHotspot;
    private MaterialCardView cardConnection;
    private TextView tvServerUrl;
    private Button btnCopyUrl;
    private Button btnShareUrl;
    private ImageView ivQrCode;
    private View layoutQrCode;
    private TextView tvPinCode;
    private Button btnRefreshPin;
    private TextView tvLogs;
    private Button btnClearLogs;
    private View layoutFileAccessBar;
    private TextView tvFileAccess;
    private Button btnGrantFileAccess;

    private WebService webService;
    private boolean isBound = false;
    /** 窗口是否已获得焦点：只有此时系统才允许读取剪贴板 */
    private boolean windowFocused = false;
    /**
     * 窗口焦点与系统内部的 isUidFocused 之间存在毫秒级时间差，单次读取经常被判"未聚焦"而拒绝；
     * 所以拿到焦点后错开时间重试几次（内容相同会被去重，不会重复推送）。
     */
    private static final int[] CLIPBOARD_POLL_DELAYS_MS = {0, 400, 1200, 2500};
    private final android.os.Handler uiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private ObjectAnimator pulseAnimator;
    private Bitmap currentQrBitmap;
    private String currentQrUrl;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            WebService.LocalBinder localBinder = (WebService.LocalBinder) binder;
            webService = localBinder.getService();
            isBound = true;
            webService.getServerManager().setStateListener(MainActivity.this);
            updateUiState(false);
            // 服务刚连上时如果窗口已经聚焦，也同步一次剪贴板
            pollClipboardIfFocused();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            webService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        bindWebService();
    }

    private void initViews() {
        rootContainer = findViewById(R.id.rootContainer);
        switchServer = findViewById(R.id.switchServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        tvServerHint = findViewById(R.id.tvServerHint);
        ivStatusDot = findViewById(R.id.ivStatusDot);
        tvNetworkInfo = findViewById(R.id.tvNetworkInfo);
        btnOpenHotspot = findViewById(R.id.btnOpenHotspot);

        cardConnection = findViewById(R.id.cardConnection);
        tvServerUrl = findViewById(R.id.tvServerUrl);
        btnCopyUrl = findViewById(R.id.btnCopyUrl);
        btnShareUrl = findViewById(R.id.btnShareUrl);

        layoutQrCode = findViewById(R.id.layoutQrCode);
        ivQrCode = findViewById(R.id.ivQrCode);
        tvPinCode = findViewById(R.id.tvPinCode);
        btnRefreshPin = findViewById(R.id.btnRefreshPin);

        tvLogs = findViewById(R.id.tvLogs);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        layoutFileAccessBar = findViewById(R.id.layoutFileAccessBar);
        tvFileAccess = findViewById(R.id.tvFileAccess);
        btnGrantFileAccess = findViewById(R.id.btnGrantFileAccess);
        btnGrantFileAccess.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            openAllFilesAccessSettings();
        });

        // 服务开关监听
        switchServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            buttonView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (isChecked) {
                startWebService();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !StorageHelper.hasAllFilesAccess()) {
                    Toast.makeText(this, getString(R.string.file_access_hint), Toast.LENGTH_LONG).show();
                }
            } else {
                stopWebService();
            }
        });

        // 复制网址
        btnCopyUrl.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            String url = tvServerUrl.getText().toString();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("ContextShared URL", url));
                Toast.makeText(this, getString(R.string.url_copied), Toast.LENGTH_SHORT).show();
            }
        });

        // 分享网址
        btnShareUrl.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            String url = tvServerUrl.getText().toString();
            Intent sendIntent = new Intent();
            sendIntent.setAction(Intent.ACTION_SEND);
            sendIntent.putExtra(Intent.EXTRA_TEXT, url);
            sendIntent.setType("text/plain");
            Intent shareIntent = Intent.createChooser(sendIntent, getString(R.string.share_title));
            startActivity(shareIntent);
        });

        // 点击放大二维码
        View.OnClickListener qrClickListener = v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            showEnlargedQrDialog();
        };
        layoutQrCode.setOnClickListener(qrClickListener);
        ivQrCode.setOnClickListener(qrClickListener);

        // 刷新 PIN 码
        btnRefreshPin.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (webService != null && webService.getServerManager() != null) {
                String newPin = webService.getServerManager().getSessionManager().refreshPinCode();
                tvPinCode.setText(newPin);
                Toast.makeText(this, getString(R.string.pin_refreshed, newPin), Toast.LENGTH_SHORT).show();
            }
        });

        // 热点快捷入口
        btnOpenHotspot.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            try {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                startActivity(intent);
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception ex) {
                    Toast.makeText(this, "请在系统设置中打开热点", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 清空传输日志
        btnClearLogs.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            tvLogs.setText(getString(R.string.waiting_connection));
        });

        updateNetworkInfo();
        updateFileAccessState();
    }

    /** 刷新「所有文件访问权限」状态（从设置页返回时也会重新检查）。 */
    private void updateFileAccessState() {
        if (layoutFileAccessBar == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            layoutFileAccessBar.setVisibility(View.GONE);
            return;
        }
        boolean granted = StorageHelper.hasAllFilesAccess();
        layoutFileAccessBar.setVisibility(View.VISIBLE);
        tvFileAccess.setText(granted ? getString(R.string.file_access_granted) : getString(R.string.file_access_denied));
        tvFileAccess.setTextColor(granted ? Color.parseColor("#30C978") : Color.parseColor("#E8A33D"));
        btnGrantFileAccess.setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ex) {
                Toast.makeText(this, getString(R.string.file_access_hint), Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateFileAccessState();
        pollClipboardIfFocused();
    }

    /**
     * 窗口获得焦点时才读剪贴板。
     *
     * <p>Android 10+ 规定：应用不在前台/没有焦点时读剪贴板会被系统拒绝
     * （日志：{@code ClipboardService: Denying clipboard access to <pkg>, application is not in focus}）。
     * 实测 {@code onResume} 会早于窗口获得焦点，在那里读仍会被拒，因此改用此回调：
     * 用户在其他应用里复制内容后切回本应用 → 窗口获得焦点瞬间同步一次到网页。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        windowFocused = hasFocus;
        if (hasFocus) {
            pollClipboardIfFocused();
        }
    }

    private void pollClipboardIfFocused() {
        for (int delay : CLIPBOARD_POLL_DELAYS_MS) {
            uiHandler.postDelayed(this::pollClipboardOnce, delay);
        }
    }

    private void pollClipboardOnce() {
        if (!windowFocused) return;
        if (webService == null || webService.getServerManager() == null) return;
        webService.getServerManager().pollClipboardNow();
    }

    private void updateNetworkInfo() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.isWifiEnabled()) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                String ssid = wifiInfo != null ? wifiInfo.getSSID() : "";
                if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                if (!ssid.isEmpty() && !ssid.equals("<unknown ssid>")) {
                    tvNetworkInfo.setText("📶 Wi-Fi: " + ssid);
                    return;
                }
            }
        } catch (Exception ignored) {}
        tvNetworkInfo.setText("🌐 " + getString(R.string.hotspot_hint));
    }

    private void showEnlargedQrDialog() {
        if (currentQrBitmap == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(currentQrBitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setPadding(32, 32, 32, 32);

        builder.setView(imageView);
        builder.setPositiveButton("关闭", null);
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void startPulseAnimation() {
        if (pulseAnimator == null) {
            PropertyValuesHolder scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.4f);
            PropertyValuesHolder scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.4f);
            PropertyValuesHolder alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.4f);
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(ivStatusDot, scaleX, scaleY, alpha);
            pulseAnimator.setDuration(1000);
            pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
            pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        }
        if (!pulseAnimator.isRunning()) {
            pulseAnimator.start();
        }
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
        }
        ivStatusDot.setScaleX(1.0f);
        ivStatusDot.setScaleY(1.0f);
        ivStatusDot.setAlpha(1.0f);
    }

    private void checkPermissions() {
        List<String> neededPermissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        if (!neededPermissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted && grantResults.length > 0) {
                Toast.makeText(this, getString(R.string.permissions_granted), Toast.LENGTH_SHORT).show();
            } else if (grantResults.length > 0) {
                Toast.makeText(this, getString(R.string.permissions_partially_denied), Toast.LENGTH_SHORT).show();
            }
            updateUiState(true);
        }
    }

    private void bindWebService() {
        Intent intent = new Intent(this, WebService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void startWebService() {
        Intent intent = new Intent(this, WebService.class);
        ContextCompat.startForegroundService(this, intent);
    }

    private void stopWebService() {
        Intent intent = new Intent(this, WebService.class);
        intent.setAction(WebService.ACTION_STOP_SERVICE);
        startService(intent);
        updateUiState(true);
    }

    private void updateUiState(boolean animate) {
        boolean running = webService != null && webService.getServerManager() != null && webService.getServerManager().isRunning();
        if (switchServer.isChecked() != running) {
            switchServer.setChecked(running);
        }

        if (animate) {
            AutoTransition transition = new AutoTransition();
            transition.setDuration(250);
            TransitionManager.beginDelayedTransition(rootContainer, transition);
        }

        if (running) {
            String ip = NetworkUtils.getLocalIpAddress(this);
            int port = webService.getServerManager().getHttpPort();
            onServerStarted(ip, port, port);
        } else {
            onServerStopped();
        }
    }

    @Override
    public void onServerStarted(String ip, int httpPort, int wsPort) {
        runOnUiThread(() -> {
            String url = "http://" + ip + ":" + httpPort;
            tvServerStatus.setText(getString(R.string.server_running));
            tvServerHint.setText(getString(R.string.server_status_hint_on));
            ivStatusDot.setBackgroundColor(Color.parseColor("#30C978"));
            startPulseAnimation();

            cardConnection.setVisibility(View.VISIBLE);
            tvServerUrl.setText(url);

            if (webService != null && webService.getServerManager() != null) {
                tvPinCode.setText(webService.getServerManager().getSessionManager().getPinCode());
            }

            Bitmap logo = BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher);
            // 只在地址变化时重建二维码：onServerStarted 会在每次绑定服务时被调用
            if (!url.equals(currentQrUrl) || currentQrBitmap == null) {
                currentQrBitmap = QrCodeGenerator.generateBrandedQrCodeBitmap(url, 450, 450, logo);
                currentQrUrl = url;
            }
            if (currentQrBitmap != null) {
                ivQrCode.setImageBitmap(currentQrBitmap);
            }
            updateNetworkInfo();
        });
    }

    @Override
    public void onServerStopped() {
        runOnUiThread(() -> {
            tvServerStatus.setText(getString(R.string.server_stopped));
            tvServerHint.setText(getString(R.string.server_status_hint_off));
            ivStatusDot.setBackgroundColor(Color.parseColor("#94A3B8"));
            stopPulseAnimation();

            cardConnection.setVisibility(View.GONE);
            updateNetworkInfo();
        });
    }

    @Override
    public void onLog(String log) {
        runOnUiThread(() -> {
            String time = timeFormat.format(new Date());
            String logLine = "[" + time + "] " + log;
            String current = tvLogs.getText().toString();
            if (current.equals(getString(R.string.waiting_connection))) {
                tvLogs.setText(logLine);
            } else {
                tvLogs.setText(logLine + "\n" + current);
            }
        });
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        stopPulseAnimation();
        if (isBound) {
            if (webService != null && webService.getServerManager() != null) {
                webService.getServerManager().setStateListener(null);
            }
            unbindService(serviceConnection);
            isBound = false;
        }
        super.onDestroy();
    }
}
