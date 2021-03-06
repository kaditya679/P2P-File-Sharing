package com.tambapps.p2p.peer_transfer.android.service;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.PersistableBundle;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;
import android.util.Log;

import com.crashlytics.android.Crashlytics;
import com.google.firebase.analytics.FirebaseAnalytics;

import com.tambapps.p2p.fandem.Peer;

import com.tambapps.p2p.fandem.util.FileUtils;
import com.tambapps.p2p.peer_transfer.android.R;
import com.tambapps.p2p.peer_transfer.android.analytics.AnalyticsValues;
import com.tambapps.p2p.peer_transfer.android.service.event.TaskEventHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.channels.AsynchronousCloseException;

/**
 * Created by fonkoua on 13/05/18.
 */

public class FileReceivingJobService extends FileJobService {

    interface FileIntentProvider {
        PendingIntent ofFile(File file);
    }
    @Override
    FileTask startTask(NotificationCompat.Builder notifBuilder,
                       NotificationManager notificationManager,
                       final int notifId,
                       PersistableBundle bundle,
                       PendingIntent cancelIntent, FirebaseAnalytics analytics) {
        return new ReceivingTask(this, notifBuilder, notificationManager, notifId, cancelIntent,
                new FileIntentProvider() {
                    @Override
                    public PendingIntent ofFile(File file) {
                        Intent fileIntent = new Intent(Intent.ACTION_VIEW);
                        fileIntent.setData(FileProvider.getUriForFile(FileReceivingJobService.this,
                                getApplicationContext().getPackageName() + ".io", file));
                        fileIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        return PendingIntent.getActivity(FileReceivingJobService.this, notifId, fileIntent, PendingIntent.FLAG_UPDATE_CURRENT);                    }
                }, analytics)
                .execute(bundle.getString("downloadPath"), bundle.getString("peer"));
    }

    @Override
    int largeIcon() {
        return R.drawable.download2;
    }

    @Override
    int smallIcon() {
        return R.drawable.download;
    }

    static class ReceivingTask extends FileTask {

        private com.tambapps.p2p.fandem.task.ReceivingTask fileReceiver;
        private String fileName;
        private FileIntentProvider fileIntentProvider;

        ReceivingTask(TaskEventHandler taskEventHandler, NotificationCompat.Builder notifBuilder,
                      NotificationManager notificationManager,
                      int notifId,
                      PendingIntent cancelIntent,
                      FileIntentProvider fileIntentProvider, FirebaseAnalytics analytics) {
            super(taskEventHandler, notifBuilder, notificationManager, notifId, cancelIntent, analytics);
            this.fileIntentProvider = fileIntentProvider;
        }

        @Override
        protected void run(String... params) { //downloadPath, peer
            Bundle bundle = new Bundle();
            bundle.putString(FirebaseAnalytics.Param.CONTENT_TYPE, AnalyticsValues.SERVICE);
            bundle.putString(FirebaseAnalytics.Param.METHOD, "RECEIVE");

            final String dirPath = params[0];
            fileReceiver = new com.tambapps.p2p.fandem.task.ReceivingTask(this, new com.tambapps.p2p.fandem.task.FileProvider() {
                @Override
                public File newFile(String name) throws IOException {
                    return FileUtils.newAvailableFile(dirPath, name);
                }
            });

            getNotifBuilder().setContentTitle(getString(R.string.connecting))
                    .setContentText(getString(R.string.connecting_to, params[1]));
            updateNotification();

            try {
                fileReceiver.receiveFrom(Peer.fromHexString(params[1]));
                final File file = fileReceiver.getOutputFile();
                completeNotification(file);
            } catch (SocketTimeoutException e) {
                Crashlytics.logException(e);
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_canceled))
                        .setContentText(getString(R.string.connection_timeout));
            } catch (AsynchronousCloseException e) {
                Crashlytics.logException(e);
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_canceled));
            } catch (IOException e) {
                Log.e("FileReceivingJobService", "error while receiving", e);
                Crashlytics.logException(e);
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_aborted))
                        .setStyle(notifStyle.bigText(getString(R.string.error_occured, e.getMessage())));
                File file = fileReceiver.getOutputFile();
                if (file != null && file.exists() && !file.delete()) {
                    getNotifBuilder().setStyle(notifStyle.bigText(getString(R.string.error_incomplete)));
                }
            }
            getAnalytics().logEvent(FirebaseAnalytics.Event.SHARE, bundle);
        }

        private void completeNotification(File file) {
            if (fileReceiver.isCanceled()) {
                NotificationCompat.Builder builder = finishNotification()
                        .setContentTitle(getString(R.string.transfer_canceled));
                if (file.exists() && !file.delete()) {
                    builder.setStyle(notifStyle.bigText(getString(R.string.incomplete_transfer)));
                }
            } else {
                finishNotification()
                        .setContentTitle(getString(R.string.transfer_complete))
                        .setContentIntent(fileIntentProvider.ofFile(file));

                Bitmap image = null;
                if (isImage(file)) {
                    try (InputStream inputStream = new FileInputStream(file)) {
                        image = BitmapFactory.decodeStream(inputStream);
                    } catch (IOException e) {
                        Crashlytics.log("Couldn't decode img");
                        Crashlytics.logException(e);
                    }
                }
                if (image != null) {
                    getNotifBuilder().setStyle(new NotificationCompat.BigPictureStyle()
                            .bigPicture(image).setSummaryText(fileName));
                } else {
                    getNotifBuilder().setStyle(notifStyle.bigText(getString(R.string.success_received, fileName)));
                }
            }
        }

        private boolean isImage(File file) {
            String fileName  = file.getName();
            int extensionIndex = fileName.lastIndexOf('.');
            if (extensionIndex > 0) {
                String extension = fileName.substring(extensionIndex + 1);
                for (String imageExtension : new String[]{"jpg", "png", "gif", "bmp"}) {
                    if (imageExtension.equalsIgnoreCase(extension)) {
                        return true;
                    }
                }
            }
            return false;
        }
        @Override
        public void cancel() {
            if (!fileReceiver.isCanceled()) {
                fileReceiver.cancel();
            }
        }

        @Override
        void dispose() {
            super.dispose();
            fileIntentProvider = null;
        }

        @Override
        public String onConnected(String remoteAddress, String fileName) {
            this.fileName = fileName;
            return getString(R.string.receveiving_connected, fileName);
        }
    }
}
