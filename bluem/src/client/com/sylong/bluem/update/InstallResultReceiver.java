package com.sylong.bluem.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.util.Log;

/** Explicit, non-exported receiver, addressed by a mutable OS callback PendingIntent. */
public final class InstallResultReceiver extends BroadcastReceiver {
    static volatile Intent pendingAction;
    @Override public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences("bluem_updates", Context.MODE_PRIVATE);
        int expected = p.getInt("install_session", -1);
        int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -2);
        if (id != expected || expected < 0) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        Log.i("BlueMUpdates", "INSTALL_RESULT session=" + id + " status=" + status);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            pendingAction = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            p.edit().putString("update_status", "system_confirmation_required").apply();
        } else {
            pendingAction = null;
            p.edit().remove("install_session").remove("installer_launched")
                .putString("update_status", status == PackageInstaller.STATUS_SUCCESS ? "installed" : "install_retry_later")
                .putLong("install_retry_after", status == PackageInstaller.STATUS_SUCCESS ? 0 : System.currentTimeMillis() + 3600000)
                .apply();
        }
        StandaloneUpdates.requestCheck();
    }
}
