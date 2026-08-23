package com.lll.contextshared.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.lll.contextshared.MainActivity;
import com.lll.contextshared.R;
import com.lll.contextshared.server.ServerManager;
import com.lll.contextshared.util.NetworkUtils;

public class WebService extends Service {
    private static final String CHANNEL_ID = "context_shared_service";
    private static final int NOTIFICATION_ID = 1001;
    public static final String ACTION_STOP_SERVICE = "com.lll.contextshared.ACTION_STOP";

    private final IBinder binder = new LocalBinder();
    private ServerManager serverManager;

    public class LocalBinder extends Binder {
        public WebService getService() {
            return WebService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        serverManager = new ServerManager(this);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_SERVICE.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startServer();
        return START_STICKY;
    }

    public ServerManager getServerManager() {
        return serverManager;
    }

    public void startServer() {
        try {
            if (!serverManager.isRunning()) {
                serverManager.startServer();
            }
            startForegroundWithNotification();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void stopServer() {
        if (serverManager != null) {
            serverManager.stopServer();
        }
    }

    private void startForegroundWithNotification() {
        String ip = NetworkUtils.getLocalIpAddress(this);
        int port = serverManager.getHttpPort();
        String contentText = getString(R.string.service_notification_content, "http://" + ip + ":" + port);

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPendingIntent = PendingIntent.getActivity(
                this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stopIntent = new Intent(this, WebService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.service_notification_title))
                .setContentText(contentText)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(mainPendingIntent)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.service_stop_action), stopPendingIntent)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.service_channel_desc));
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }
}
