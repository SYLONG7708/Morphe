package com.sylong.bluem.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.Context;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

/** MicroG setup must never terminate YouTube or throw when an OEM omits a settings page. */
public final class StartupSupport {
    private StartupSupport() {}
    static boolean hasService(Context context) {
        try { return context.getPackageManager().getApplicationInfo(UpdatePolicy.MICROG, 0).enabled; }
        catch (android.content.pm.PackageManager.NameNotFoundException absent) { return false; }
    }
    static void ensureService(Activity activity) {
        if (!(activity instanceof SetupActivity) && !hasService(activity)) {
            Log.i("BlueMUpdates", "FIRST_INSTALL_SERVICE_SETUP");
            activity.startActivity(new Intent(activity, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
    }
    static void openAppSettings(Activity activity) {
        try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + UpdatePolicy.MICROG))); }
        catch (RuntimeException ignored) { android.widget.Toast.makeText(activity,"請從系統應用程式設定啟用 MicroG。",android.widget.Toast.LENGTH_LONG).show(); }
    }
    public static void open(Activity activity, String link) {
        if (link != null && link.toLowerCase(java.util.Locale.ROOT).contains("microg")) {
            Log.i("BlueMUpdates", "MICROG_IN_APP_REQUEST");
            StandaloneUpdates.openMicroGUpdate(activity, link);
            return;
        }
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;
            try {
                new AlertDialog.Builder(activity).setTitle("MicroG 服務設定")
                    .setMessage("請確認 MicroG 已安裝並允許在背景執行。您可以先關閉此提示，稍後再設定。")
                    .setPositiveButton("服務設定", (d,w) -> openBatterySettings(activity))
                    .setNegativeButton("稍後", null).show();
            } catch (RuntimeException e) { Log.w("BlueMUpdates", "STARTUP_DIALOG_DEFERRED"); }
        });
    }
    public static void openBatterySettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + UpdatePolicy.MICROG));
            activity.startActivityForResult(intent, 0);
        } catch (RuntimeException unavailable) {
            try {
                openAppSettings(activity);
            } catch (RuntimeException ignored) {
                android.widget.Toast.makeText(activity, "請到系統的應用程式設定，允許 MicroG 在背景執行。", android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }
}
