package com.limelight.binding.video;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.limelight.LimeLog;
import com.limelight.R;

/**
 * Foreground Service required on Android 10+ / Android 14+ to host MediaProjection
 * for system-level screen recording simulation and real recording.
 */
public class MediaProjectionService extends Service {
    private static final String CHANNEL_ID = "yami_screen_projection_channel";
    private static final int NOTIFICATION_ID = 9922;

    public static final String EXTRA_RESULT_CODE = "extra_result_code";
    public static final String EXTRA_RESULT_DATA = "extra_result_data";

    private static MediaProjection activeMediaProjection;
    private static MediaProjectionCallback projectionCallback;

    public interface MediaProjectionCallback {
        void onProjectionReady(MediaProjection projection);
        void onProjectionStopped();
    }

    public static synchronized void setCallback(MediaProjectionCallback callback) {
        projectionCallback = callback;
        if (activeMediaProjection != null && projectionCallback != null) {
            projectionCallback.onProjectionReady(activeMediaProjection);
        }
    }

    public static synchronized MediaProjection getActiveMediaProjection() {
        return activeMediaProjection;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_RESULT_DATA);

        try {
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            LimeLog.severe("MediaProjectionService: startForeground failed: " + t.getMessage());
            t.printStackTrace();
            return START_NOT_STICKY;
        }

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            // Post with a 100ms delay to ensure ActivityManagerService has committed the foreground service type on Android 14+
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    if (mpm == null) return;

                    if (activeMediaProjection != null) {
                        try {
                            activeMediaProjection.stop();
                        } catch (Exception ignored) {}
                    }

                    activeMediaProjection = mpm.getMediaProjection(resultCode, resultData);
                    if (activeMediaProjection != null) {
                        LimeLog.info("MediaProjectionService: MediaProjection initialized successfully");
                        activeMediaProjection.registerCallback(new MediaProjection.Callback() {
                            @Override
                            public void onStop() {
                                LimeLog.info("MediaProjectionService: MediaProjection callback onStop");
                                synchronized (MediaProjectionService.class) {
                                    activeMediaProjection = null;
                                    if (projectionCallback != null) {
                                        projectionCallback.onProjectionStopped();
                                    }
                                }
                            }
                        }, new Handler(Looper.getMainLooper()));

                        synchronized (MediaProjectionService.class) {
                            if (projectionCallback != null) {
                                projectionCallback.onProjectionReady(activeMediaProjection);
                            }
                        }
                    } else {
                        LimeLog.severe("MediaProjectionService: getMediaProjection returned null");
                    }
                } catch (Throwable t) {
                    LimeLog.severe("MediaProjectionService: Error in getMediaProjection: " + t.getMessage());
                    t.printStackTrace();
                }
            }, 100);
        }

        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Yami Screen Recorder",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Active while Yami simulated screen recorder is running");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Yami")
                .setContentText("Giả lập ghi hình đang hoạt động")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        return builder.build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        synchronized (MediaProjectionService.class) {
            if (activeMediaProjection != null) {
                try {
                    activeMediaProjection.stop();
                } catch (Exception ignored) {}
                activeMediaProjection = null;
            }
            if (projectionCallback != null) {
                projectionCallback.onProjectionStopped();
            }
        }
        LimeLog.info("MediaProjectionService destroyed");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
