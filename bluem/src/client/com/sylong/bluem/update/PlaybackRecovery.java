package com.sylong.bluem.update;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;

/** Uses Morphe's own player/controller API. Never reads credentials or clears application data. */
public final class PlaybackRecovery {
    private static final String TAG = "BlueMRecovery";
    private static final String VIDEO = "app.morphe.extension.youtube.patches.VideoInformation";
    private static final String SETTINGS = "app.morphe.extension.youtube.settings.Settings";
    private static final String SHARED = "app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch";
    private static final String CLIENT = "app.morphe.extension.shared.spoof.ClientType";
    private static final String LOAD = "app.morphe.extension.youtube.patches.LoadVideoPatch";
    private static final String STATE = "app.morphe.extension.youtube.shared.VideoState";
    private static final String TYPE = "app.morphe.extension.youtube.shared.PlayerType";
    private static final List<String> ALLOWED = Arrays.asList("TV_SABR", "ANDROID_CREATOR", "TV_SIMPLY");
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final RecoveryPolicy policy = new RecoveryPolicy();
    private static final Map<String, Method> methods = new HashMap<>();
    private static final Set<String> tried = new HashSet<>();
    private static WeakReference<Activity> activity = new WeakReference<>(null);
    private static Context app;
    private static SharedPreferences prefs;
    private static boolean started, available, activePlayback;
    private static String currentVideo = "", chosen = "TV_SABR", healthyVideo = "";
    private static long healthySince, previousPosition = -1, activeUntil;
    private static int failuresInWindow;
    private static long windowStart;
    static volatile boolean qaForceStall;
    private static long diagnosticAt;

    private static Object call(String cls, String name) throws Exception {
        String key = cls + "/" + name;
        Method method = methods.get(key);
        if (method == null) { method = Class.forName(cls).getMethod(name); methods.put(key, method); }
        return method.invoke(null);
    }
    private static void saveSetting(String name, Object value) throws Exception {
        Object setting = Class.forName(SETTINGS).getField(name).get(null);
        for (Method m : setting.getClass().getMethods()) {
            if (m.getName().equals("save") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isInstance(value)) { m.invoke(setting, value); return; }
        }
        throw new NoSuchMethodException("Setting.save");
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object client(String name) throws Exception {
        if (!ALLOWED.contains(name)) throw new IllegalArgumentException("Unsupported client");
        return Enum.valueOf((Class) Class.forName(CLIENT), name);
    }
    private static List<String> order() {
        String value = prefs.getString("playback_clients", "TV_SABR,ANDROID_CREATOR,TV_SIMPLY");
        List<String> result = new ArrayList<>();
        for (String s : value.split(",")) if (ALLOWED.contains(s) && !result.contains(s)) result.add(s);
        if (result.size() < 2) return ALLOWED;
        return result;
    }
    private static void applyClient(String name) throws Exception {
        Object preferred = client(name);
        List<Object> availableClients = new ArrayList<>();
        for (String s : order()) if (!tried.contains(s)) availableClients.add(client(s));
        saveSetting("SPOOF_VIDEO_STREAMS_CLIENT_TYPE", preferred);
        Class.forName(SHARED).getMethod("setClientsToUse", List.class, Class.forName(CLIENT))
            .invoke(null, availableClients, preferred);
        chosen = name;
        prefs.edit().putString("active_client", name).apply();
        Log.i(TAG, "CLIENT_SELECTED " + name);
    }
    public static void resume(Activity a) {
        activity = new WeakReference<>(a);
        if (started) return;
        started = true; app = a.getApplicationContext();
        prefs = app.getSharedPreferences("bluem_updates", Context.MODE_PRIVATE);
        handler.postDelayed(() -> {
            try {
                // Resolve required APIs before changing a setting. A future incompatible upstream fails safely.
                call(VIDEO, "getVideoTime"); call(VIDEO, "getVideoId"); call(STATE, "getCurrent");
                Class.forName(LOAD).getMethod("initializeReloadVideo");
                Class.forName(SHARED).getMethod("setClientsToUse", List.class, Class.forName(CLIENT));
                saveSetting("SPOOF_VIDEO_STREAMS", Boolean.TRUE);
                String remembered = prefs.getString("healthy_client", "TV_SABR");
                applyClient(order().contains(remembered) ? remembered : order().get(0));
                available = true;
                Log.i(TAG, "READY interval_ms=1000 buffering_ms=" + timeout());
            } catch (Exception e) {
                prefs.edit().putString("recovery_status", "compatibility_update_required").apply();
                Log.w(TAG, "UNAVAILABLE " + e.getClass().getSimpleName());
            }
            handler.post(tick);
        }, 400);
    }
    public static void pause(Activity a) {
        if (activity.get() == a) activity.clear();
    }
    public static boolean isPlaybackActive() {
        return activePlayback || SystemClock.elapsedRealtime() < activeUntil;
    }
    public static boolean online(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private static long timeout() { return Math.max(4000, Math.min(30000, prefs.getLong("buffering_ms", 6000))); }
    private static boolean loading(Activity a) {
        for (String id : new String[]{"player_loading_view_thin", "player_loading_view"}) {
            int resource = a.getResources().getIdentifier(id, "id", app.getPackageName());
            View view = resource == 0 ? null : a.findViewById(resource);
            if (view != null && view.isShown() && view.getAlpha() > 0 && view.getWidth() > 0) return true;
        }
        return false;
    }
    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            try { sample(); }
            catch (Exception e) { Log.w(TAG, "SAMPLE_SKIPPED " + e.getClass().getSimpleName()); }
            finally { handler.postDelayed(this, 1000); }
        }
    };
    private static void sample() throws Exception {
        if (!available) return;
        Activity a = activity.get();
        long now = SystemClock.elapsedRealtime();
        String id = (String) call(VIDEO, "getVideoId");
        long position = (Long) call(VIDEO, "getVideoTime");
        long length = (Long) call(VIDEO, "getVideoLength");
        String state = String.valueOf(call(STATE, "getCurrent"));
        String type = String.valueOf(call(TYPE, "getCurrent"));
        boolean error = state.equals("RECOVERABLE_ERROR") || state.equals("UNRECOVERABLE_ERROR");
        boolean paused = state.equals("PAUSED") || state.equals("ENDED");
        boolean regular = type.startsWith("WATCH_WHILE_") && !type.contains("DISMISSED")
            && !(Boolean) call(VIDEO, "lastVideoIdIsShort");
        boolean spinner = a != null && loading(a);
        boolean progressing = position > previousPosition && position - previousPosition < 5000;
        // Some upstream builds leave VideoState null until the first pause/resume event.
        // Progress and an open player still block installation, including Shorts/background audio.
        activePlayback = !paused && (progressing || spinner || error || (!id.isEmpty()
            && !type.contains("DISMISSED") && (state.equals("PLAYING") || state.equals("null"))));
        if (activePlayback) activeUntil = now + 30000;
        if (!id.isEmpty() && !id.equals(currentVideo)) {
            currentVideo = id; tried.clear(); healthyVideo = id; healthySince = now;
        }
        if (!progressing || spinner || paused) healthySince = now;
        if (progressing && id.equals(healthyVideo) && now - healthySince >= 90000
                && !chosen.equals(prefs.getString("healthy_client", ""))) {
            prefs.edit().putString("healthy_client", chosen).putString("recovery_status", "healthy").apply();
            Log.i(TAG, "HEALTHY_90_SECONDS " + chosen);
        }
        previousPosition = position;
        boolean eligible = a != null && !a.isFinishing() && regular && !(length > 0 && position >= length - 1500);
        boolean qaStall = BuildConfig.QA && a != null && position >= 15000 && qaForceStall;
        if (BuildConfig.QA && now - diagnosticAt > 10000) {
            diagnosticAt = now;
            Log.i(TAG, "QA_SAMPLE state=" + state + " type=" + type + " position=" + position
                + " loading=" + spinner + " eligible=" + eligible + " active=" + activePlayback + " fault=" + qaStall);
        }
        RecoveryPolicy.Action action = policy.sample(now, id, qaStall ? 15000 : position, eligible, online(app), paused,
            spinner || qaStall, error, timeout());
        if (action == RecoveryPolicy.Action.NONE) return;
        if (windowStart == 0 || now - windowStart > 600000) { windowStart = now; failuresInWindow = 0; }
        if (action == RecoveryPolicy.Action.EXHAUSTED || failuresInWindow >= 6) {
            prefs.edit().putString("recovery_status", "waiting_for_network_or_update").apply();
            StandaloneUpdates.requestCheck();
            Log.w(TAG, "RECOVERY_LIMIT_REACHED");
            return;
        }
        tried.add(chosen);
        String next = null;
        for (String option : order()) if (!tried.contains(option)) { next = option; break; }
        if (next == null) return;
        applyClient(next);
        if (qaStall) qaForceStall = false;
        failuresInWindow++;
        prefs.edit().putLong("last_recovery_time", System.currentTimeMillis())
            .putString("recovery_status", "retrying").putLong("last_recovery_position_ms", position).apply();
        Log.i(TAG, "RECOVER " + next + " position_ms=" + position);
        // The upstream reload API preserves video, playlist, position and playback speed.
        call(LOAD, "initializeReloadVideo");
    }
}
