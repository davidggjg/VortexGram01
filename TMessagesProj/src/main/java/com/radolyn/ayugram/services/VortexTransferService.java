/*
 * This is the source code of VortexGram for Android.
 *
 * Keeps downloads and uploads alive when the app is not in the foreground.
 */

package com.radolyn.ayugram.services;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A foreground service that exists only while a file transfer is in flight.
 *
 * Without it, Android is free to freeze or kill the process once the app goes
 * to the background, which is why a large download or upload fails as soon as
 * you leave Telegram. Holding a foreground service for the duration tells the
 * system the work is user-visible and must keep running.
 *
 * The service starts itself when the first transfer begins and stops as soon as
 * the last one finishes, so it never lingers.
 */
public class VortexTransferService extends Service {

    private static final int NOTIFICATION_ID = 38001;

    /** Names of the files currently downloading. */
    private static final Set<String> activeDownloads =
            ConcurrentHashMap.newKeySet();
    /** Names of the files currently uploading. */
    private static final Set<String> activeUploads =
            ConcurrentHashMap.newKeySet();

    private static volatile boolean running;

    private NotificationCompat.Builder builder;

    public static void onDownloadStarted(String fileName) {
        if (fileName == null) {
            return;
        }
        activeDownloads.add(fileName);
        sync();
    }

    public static void onDownloadFinished(String fileName) {
        if (fileName == null) {
            return;
        }
        activeDownloads.remove(fileName);
        sync();
    }

    /** Whether this file is still in the download queue. */
    public static boolean isDownloading(String fileName) {
        return fileName != null && activeDownloads.contains(fileName);
    }

    public static void onUploadStarted(String fileName) {
        if (fileName == null) {
            return;
        }
        activeUploads.add(fileName);
        sync();
    }

    public static void onUploadFinished(String fileName) {
        if (fileName == null) {
            return;
        }
        activeUploads.remove(fileName);
        sync();
    }

    /** Starts the service when there is work, stops it when there is none. */
    private static void sync() {
        boolean shouldRun = !activeDownloads.isEmpty() || !activeUploads.isEmpty();
        if (shouldRun == running) {
            if (shouldRun) {
                // Already running - just refresh the notification text.
                AndroidUtilities.runOnUIThread(VortexTransferService::requestUpdate);
            }
            return;
        }
        running = shouldRun;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Intent intent = new Intent(ApplicationLoader.applicationContext, VortexTransferService.class);
                if (shouldRun) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ApplicationLoader.applicationContext.startForegroundService(intent);
                    } else {
                        ApplicationLoader.applicationContext.startService(intent);
                    }
                } else {
                    ApplicationLoader.applicationContext.stopService(intent);
                }
            } catch (Throwable e) {
                // Starting a foreground service can be refused, for instance from
                // the background on newer Android. Transfers then behave as before
                // rather than taking the app down.
                running = false;
                FileLog.e("VortexTransferService.sync", new Exception(e));
            }
        });
    }

    private static void requestUpdate() {
        VortexTransferService instance = current;
        if (instance != null) {
            instance.updateNotification();
        }
    }

    private static volatile VortexTransferService current;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        current = this;
        if (activeDownloads.isEmpty() && activeUploads.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        current = null;
        running = false;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext).cancel(NOTIFICATION_ID);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void updateNotification() {
        try {
            NotificationManagerCompat.from(ApplicationLoader.applicationContext)
                    .notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable e) {
            FileLog.e("VortexTransferService.updateNotification", new Exception(e));
        }
    }

    private android.app.Notification buildNotification() {
        NotificationsController.checkOtherNotificationsChannel();
        if (builder == null) {
            builder = new NotificationCompat.Builder(ApplicationLoader.applicationContext);
            builder.setSmallIcon(android.R.drawable.stat_sys_download);
            builder.setWhen(System.currentTimeMillis());
            builder.setOngoing(true);
            builder.setChannelId(NotificationsController.OTHER_NOTIFICATIONS_CHANNEL);
            builder.setContentTitle(LocaleController.getString("AppName", R.string.AppName));
        }
        builder.setContentText(describe());
        builder.setProgress(0, 0, true);
        return builder.build();
    }

    private String describe() {
        int downloads = activeDownloads.size();
        int uploads = activeUploads.size();
        if (downloads > 0 && uploads > 0) {
            return LocaleController.formatString("VortexTransferBoth",
                    R.string.VortexTransferBoth, downloads, uploads);
        }
        if (uploads > 0) {
            return LocaleController.formatString("VortexTransferUploading",
                    R.string.VortexTransferUploading, uploads);
        }
        return LocaleController.formatString("VortexTransferDownloading",
                R.string.VortexTransferDownloading, downloads);
    }
}
