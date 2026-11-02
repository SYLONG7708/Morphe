package com.sylong.bluem.update;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;

/** Keeps a first installation usable while the required MicroG service is missing. */
public final class SetupActivity extends Activity {
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean active, entering;
    private final Runnable watchInstall = new Runnable() {
        @Override public void run() {
            if (!active || isFinishing() || isDestroyed()) return;
            openWhenReady();
            if (!entering) main.postDelayed(this, 500);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        StandaloneUpdates.attach(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        page.setPadding(pad,pad,pad,pad); page.setBackgroundColor(Color.rgb(20,23,30));
        TextView title = new TextView(this); title.setText("藍色 M 初次設定"); title.setTextSize(26); title.setTextColor(Color.WHITE);
        page.addView(title);
        TextView message = new TextView(this); message.setText("YouTube 需要 MicroG 服務才能啟動。\n\n請在這裡下載並安裝 MicroG，完成後即可開啟 YouTube。");
        message.setTextSize(18); message.setTextColor(Color.LTGRAY); message.setPadding(0,pad,0,pad); page.addView(message);
        Button download = new Button(this); download.setText("下載 MicroG");
        download.setOnClickListener(v -> StandaloneUpdates.openMicroGUpdate(this,"microg")); page.addView(download);
        Button settings = new Button(this); settings.setText("已安裝但被停用：開啟服務設定");
        settings.setOnClickListener(v -> StartupSupport.openAppSettings(this));page.addView(settings);
        setContentView(page);
    }
    @Override public void onResume() {
        super.onResume();
        active = true; main.post(watchInstall);
    }
    @Override public void onPause() {
        active = false; main.removeCallbacks(watchInstall); super.onPause();
    }
    private void openWhenReady() {
        // PackageManager can expose the APK before PackageInstaller has completed its transaction.
        if (getSharedPreferences("bluem_updates", MODE_PRIVATE).getInt("install_session", -1) >= 0) return;
        if (!entering && StartupSupport.hasService(this)) {
            entering = true;
            try {
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(getPackageName(),"com.google.android.apps.youtube.app.watchwhile.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(home);
                finish();
            } catch (RuntimeException ignored) { entering = false; }
        }
    }
}
