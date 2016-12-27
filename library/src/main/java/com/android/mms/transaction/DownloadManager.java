
package com.android.mms.transaction;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SqliteWrapper;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Telephony;
import android.telephony.SmsManager;
import android.text.TextUtils;

import com.android.mms.MmsConfig;
import com.klinker.android.logger.Log;
import com.klinker.android.send_message.MmsReceivedReceiver;

import java.io.File;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In order to avoid downloading duplicate MMS.
 * We should manage to call SMSManager.downloadMultimediaMessage().
 */
public class DownloadManager {
    private static final String TAG = "DownloadManager";
    private static DownloadManager ourInstance = new DownloadManager();
    private static final ConcurrentHashMap<String, MmsDownloadReceiver> mMap = new ConcurrentHashMap<>();
    private static final AtomicInteger sMaxConnection = new AtomicInteger(5);

    public static DownloadManager getInstance() {
        return ourInstance;
    }

    private DownloadManager() {

    }

    void downloadMultimediaMessage(final Context context, final String location, Uri uri, boolean byPush) {
        if (location == null) {
            Log.v(TAG, "location is null");
            return;
        }

        dumpCurrentConnection();
        if (mMap.get(location) != null) {
            Log.v(TAG, "mMap.get(" + location + ") not null");
            return;
        } else if (mMap.size() >= sMaxConnection.get()) {
            Log.v(TAG, "mMap.size() " + mMap.size() + " >= sMaxConnection.get() " + sMaxConnection.get());
            return;
        }

        // TransactionService can keep uri and location in memory while SmsManager download Mms.
        if (!isNotificationExist(context, location)) {
            Log.v(TAG, "location " + location + " is already received.");
            return;
        }

        MmsDownloadReceiver receiver = new MmsDownloadReceiver();
        mMap.put(location, receiver);

        // Use unique action in order to avoid cancellation of notifying download result.
        context.getApplicationContext().registerReceiver(receiver, new IntentFilter(receiver.mAction));

        Log.v(TAG, "receiving with system method");
        final String fileName = "download." + String.valueOf(Math.abs(new Random().nextLong())) + ".dat";
        File mDownloadFile = new File(context.getCacheDir(), fileName);
        Uri contentUri = (new Uri.Builder())
                .authority(context.getPackageName() + ".MmsFileProvider")
                .path(fileName)
                .scheme(ContentResolver.SCHEME_CONTENT)
                .build();
        Intent download = new Intent(receiver.mAction);
        download.putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, mDownloadFile.getPath());
        download.putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location);
        download.putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, byPush);
        download.putExtra(MmsReceivedReceiver.EXTRA_URI, uri);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 0, download, PendingIntent.FLAG_CANCEL_CURRENT);

        Bundle configOverrides = new Bundle();
        String httpParams = MmsConfig.getHttpParams();
        if (!TextUtils.isEmpty(httpParams)) {
            configOverrides.putString(SmsManager.MMS_CONFIG_HTTP_PARAMS, httpParams);
        }

        grantUriPermission(context, contentUri);
        SmsManager.getDefault().downloadMultimediaMessage(context,
                location, contentUri, configOverrides, pendingIntent);
    }

    private void grantUriPermission(Context context, Uri contentUri) {
        context.grantUriPermission(context.getPackageName() + ".MmsFileProvider",
                contentUri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private static class MmsDownloadReceiver extends BroadcastReceiver {
        private static final String ACTION_PREFIX = "com.android.mms.transaction.DownloadManager$MmsDownloadReceiver.";
        private final String mAction;

        MmsDownloadReceiver() {
            mAction = ACTION_PREFIX + UUID.randomUUID().toString();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            context.unregisterReceiver(this);

            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMS DownloadReceiver");
            wakeLock.acquire(60 * 1000);

            Intent newIntent = (Intent) intent.clone();
            newIntent.setAction(MmsReceivedReceiver.MMS_RECEIVED);
            context.sendBroadcast(newIntent);
        }
    }

    public static void finishDownload(String location) {
        Log.v(TAG, "finishDownload( " + location + ")");

        if (location != null) {
            mMap.remove(location);
        }
        dumpCurrentConnection();
    }

    private static boolean isNotificationExist(Context context, String location) {
        String selection = Telephony.Mms.CONTENT_LOCATION + " = ?";
        String[] selectionArgs = new String[] { location };
        Cursor c = SqliteWrapper.query(
                context, context.getContentResolver(),
                Telephony.Mms.CONTENT_URI, new String[] { Telephony.Mms._ID },
                selection, selectionArgs, null);
        if (c != null) {
            try {
                if (c.getCount() == 1) {
                    return true;
                }
            } finally {
                c.close();
            }
        }

        return false;
    }

    public static void setMaxConnection(int max) {
        sMaxConnection.set(max);
    }

    private static void dumpCurrentConnection() {
        Log.v(TAG, "dumpCurrentConnection() start");

        for (String key: mMap.keySet()) {
            Log.v(TAG, "key: " + key);
        }
        Log.v(TAG, "dumpCurrentConnection() end");
    }
}
