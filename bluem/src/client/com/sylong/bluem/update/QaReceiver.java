package com.sylong.bluem.update;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Registered only in QA builds and restricted to Android's signature DUMP permission. */
public final class QaReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (BuildConfig.QA) {
            if ("stall".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaForceStall = true;
            if ("check".equals(intent.getStringExtra("action"))) StandaloneUpdates.requestCheck();
        }
    }
}
