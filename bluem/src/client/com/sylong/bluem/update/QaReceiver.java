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
            if ("error".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaError("播放時發生問題\n輕觸以重試");
            if ("account".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaError("請登入以確認你不是機器人");
            if ("offline".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaOffline = true;
            if ("online".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaOffline = false;
            if ("clear".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaError(null);
            if ("internal".equals(intent.getStringExtra("action"))) PlaybackRecovery.qaInternal();
        }
    }
}
