package com.lll.contextshared;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.lll.contextshared.server.ServerManager;
import com.lll.contextshared.service.WebService;
import com.lll.contextshared.util.NetworkUtils;
import com.lll.contextshared.util.QrCodeGenerator;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements ServerManager.ServerStateListener {
    private static final int PERMISSION_REQUEST_CODE = 101;

    private SwitchMaterial switchServer;
    private TextView tvServerStatus;
    private MaterialCardView cardConnection;
    private TextView tvServerUrl;
    private ImageView ivQrCode;
    private TextView tvPinCode;
    private Button btnRefreshPin;
    private TextView tvLogs;

    private WebService webService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            WebService.LocalBinder localBinder = (WebService.LocalBinder) binder;
            webService = localBinder.getService();
            isBound = true;
            webService.getServerManager().setStateListener(MainActivity.this);
            updateUiState();
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
        switchServer = findViewById(R.id.switchServer);
        tvServerStatus = findViewById(R.id.tvServerStatus);
        cardConnection = findViewById(R.id.cardConnection);
        tvServerUrl = findViewById(R.id.tvServerUrl);
        ivQrCode = findViewById(R.id.ivQrCode);
        tvPinCode = findViewById(R.id.tvPinCode);
        btnRefreshPin = findViewById(R.id.btnRefreshPin);
        tvLogs = findViewById(R.id.tvLogs);
        tvLogs.setMovementMethod(new ScrollingMovementMethod());

        switchServer.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                startWebService();
            } else {
                stopWebService();
            }
        });

        btnRefreshPin.setOnClickListener(v -> {
            if (webService != null) {
                String newPin = webService.getServerManager().getSessionManager().refreshPinCode();
                tvPinCode.setText(newPin);
                Toast.makeText(this, "PIN 码已刷新: " + newPin, Toast.LENGTH_SHORT).show();
            }
        });
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
        updateUiState();
    }

    private void updateUiState() {
        boolean running = webService != null && webService.getServerManager() != null && webService.getServerManager().isRunning();
        switchServer.setChecked(running);
        if (running) {
            String ip = NetworkUtils.getLocalIpAddress(this);
            int port = webService.getServerManager().getHttpPort();
            onServerStarted(ip, port, port + 1);
        } else {
            onServerStopped();
        }
    }

    @Override
    public void onServerStarted(String ip, int httpPort, int wsPort) {
        runOnUiThread(() -> {
            String url = "http://" + ip + ":" + httpPort;
            tvServerStatus.setText("🟢 局域网服务运行中");
            cardConnection.setVisibility(View.VISIBLE);
            tvServerUrl.setText(url);

            if (webService != null && webService.getServerManager() != null) {
                tvPinCode.setText(webService.getServerManager().getSessionManager().getPinCode());
            }

            Bitmap qr = QrCodeGenerator.generateQrCodeBitmap(url, 400, 400);
            if (qr != null) {
                ivQrCode.setImageBitmap(qr);
            }
        });
    }

    @Override
    public void onServerStopped() {
        runOnUiThread(() -> {
            tvServerStatus.setText("⚪ 服务已停止");
            cardConnection.setVisibility(View.GONE);
        });
    }

    @Override
    public void onLog(String log) {
        runOnUiThread(() -> {
            String current = tvLogs.getText().toString();
            tvLogs.setText(log + "\n" + current);
        });
    }

    @Override
    protected void onDestroy() {
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
