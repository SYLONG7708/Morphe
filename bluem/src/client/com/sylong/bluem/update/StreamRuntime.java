package com.sylong.bluem.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.webkit.WebView;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** A sandbox can exist without supporting the multi-megabyte player script. */
final class StreamRuntime {
    private static final String ENGINE = "app.morphe.extension.shared.spoof.js.JavaScriptEngineSupport";
    private static final String SANDBOX = "androidx.javascriptengine.JavaScriptSandbox";
    private static boolean prepared, largeScripts;

    static synchronized boolean supportsScripts(Context context) throws Exception {
        Class<?> engine = Class.forName(ENGINE);
        if (!prepared) {
            prepared = true;
            boolean basic = Boolean.TRUE.equals(engine.getMethod("supportsJavaScriptEngine").invoke(null));
            PackageInfo webview = WebView.getCurrentWebViewPackage();
            String runtime = webview == null ? "unknown" : webview.packageName + "/" + webview.getLongVersionCode();
            SharedPreferences prefs = context.getSharedPreferences("bluem_updates", Context.MODE_PRIVATE);
            boolean verified = runtime.equals(prefs.getString("js_feature_runtime", ""));
            largeScripts = basic && verified && prefs.getBoolean("js_large_scripts", false);
            if (!largeScripts) {
                // Do this before MainActivity.onCreate starts the first player request.
                engine.getMethod("setDeviceSupportsJsEngine", boolean.class).invoke(null, false);
            }
            if (basic && !verified) probe(context.getApplicationContext(), prefs, runtime);
            Log.i("BlueMRecovery", "SCRIPT_RUNTIME basic=" + basic + " verified=" + verified
                + " large_scripts=" + largeScripts);
        }
        // Upstream can disable the engine after an actual runtime failure.
        return largeScripts && Boolean.TRUE.equals(engine.getMethod("supportsJavaScriptEngine").invoke(null));
    }

    private static void probe(Context context, SharedPreferences prefs, String runtime) {
        Thread worker = new Thread(() -> {
            Object sandbox = null;
            Future<?> pending = null;
            try {
                Class<?> type = Class.forName(SANDBOX);
                pending = (Future<?>) type.getMethod("createConnectedInstanceAsync", Context.class).invoke(null, context);
                sandbox = pending.get(8, TimeUnit.SECONDS);
                // This public constant is inlined and removed by the upstream APK shrinker.
                String feature = "JS_FEATURE_EVALUATE_WITHOUT_TRANSACTION_LIMIT";
                boolean supported = Boolean.TRUE.equals(type.getMethod("isFeatureSupported", String.class).invoke(sandbox, feature));
                prefs.edit().putString("js_feature_runtime", runtime).putBoolean("js_large_scripts", supported).apply();
                // Apply on the next process start; do not replace a working stream mid-playback.
                Log.i("BlueMRecovery", "SCRIPT_FEATURE_VERIFIED large_scripts=" + supported);
            } catch (Exception error) {
                // A transient bind/timeout is not cached as a permanent incompatibility.
                Log.w("BlueMRecovery", "SCRIPT_FEATURE_CHECK_DEFERRED " + error.getClass().getSimpleName());
            } finally {
                if (sandbox != null) {
                    try { sandbox.getClass().getMethod("close").invoke(sandbox); } catch (Exception ignored) {}
                } else if (pending != null) pending.cancel(true);
            }
        }, "BlueM-script-capability");
        worker.setDaemon(true);
        worker.start();
    }
    private StreamRuntime() {}
}
