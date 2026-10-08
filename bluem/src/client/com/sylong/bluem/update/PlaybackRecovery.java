package com.sylong.bluem.update;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.notification.StatusBarNotification;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.view.Gravity;
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
    private static String currentVideo = "", chosen = "TV_SABR", healthyVideo = "", lastFailure = "";
    private static long healthySince, previousPosition = -1, activeUntil, lastGoodPosition, lastProgressAt;
    private static MediaController mediaController;
    private static long mediaLookupAt;
    static volatile boolean qaForceStall;
    static volatile boolean qaOffline;
    private static TextView qaErrorView;
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
        Log.i(TAG, "WATCH_ATTACHED " + a.getClass().getSimpleName());
        if (started) return;
        started = true; app = a.getApplicationContext();
        prefs = app.getSharedPreferences("bluem_updates", Context.MODE_PRIVATE);
        handler.postDelayed(() -> {
            try {
                // Resolve required APIs before changing a setting. A future incompatible upstream fails safely.
                call(VIDEO, "getVideoTime"); call(VIDEO, "getVideoId"); call(STATE, "getCurrent");
                Class.forName(LOAD).getMethod("openVideoIntent", String.class, boolean.class);
                call(VIDEO, "getPlayerResponseVideoId"); call(VIDEO, "getPlaylistId");
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
    private static boolean visible(View view) {
        return view != null && view.isShown() && view.getAlpha() > 0 && view.getWidth() > 0 && view.getHeight() > 0;
    }
    private static View find(Activity a, String name) {
        if (a == null) return null;
        int id = a.getResources().getIdentifier(name, "id", app.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }
    private static PlaybackState mediaState(Activity a, long now) {
        try {
            if (a != null && a.getMediaController() != null) mediaController = a.getMediaController();
            if (now >= mediaLookupAt) {
                mediaLookupAt = now + 15000;
                // Apps may inspect their own media notification without notification-listener access.
                NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
                for (StatusBarNotification notification : manager.getActiveNotifications()) {
                    Object token = notification.getNotification().extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                    if (token instanceof MediaSession.Token) {
                        mediaController = new MediaController(app, (MediaSession.Token) token); break;
                    }
                }
            }
            return mediaController == null ? null : mediaController.getPlaybackState();
        } catch (RuntimeException unavailable) { return null; }
    }
    /** A QA-only real error view exercises the same visibility path as YouTube's error overlay. */
    static void qaError(String message) {
        if (!BuildConfig.QA) return;
        handler.post(() -> {
            clearQaError();
            if (message == null) return;
            Activity a = activity.get();
            if (a == null) { Log.w(TAG, "QA_ERROR_NO_WATCH_ACTIVITY"); return; }
            qaErrorView = new TextView(a);
            qaErrorView.setText(message); qaErrorView.setTextSize(22); qaErrorView.setTextColor(0xffffffff);
            qaErrorView.setBackgroundColor(0xee202020); qaErrorView.setGravity(Gravity.CENTER);
            qaErrorView.setId(a.getResources().getIdentifier("player_error_view", "id", app.getPackageName()));
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(900, 250, Gravity.CENTER);
            a.addContentView(qaErrorView, p);
            Log.i(TAG, "QA_ERROR_VIEW_VISIBLE activity=" + a.getClass().getSimpleName());
        });
    }
    static void qaInternal() {
        if (!BuildConfig.QA) return;
        handler.post(() -> {
            try {
                String id = (String) call(VIDEO, "getVideoId");
                long position = (Long) call(VIDEO, "getVideoTime");
                Class.forName(LOAD).getMethod("openVideoIntentWithInternalContext", String.class)
                    .invoke(null, id);
                handler.postDelayed(() -> {
                    try { Class.forName(VIDEO).getMethod("seekTo", long.class).invoke(null, Math.max(0, position)); }
                    catch (Exception e) { Log.w(TAG, "QA_INTERNAL_SEEK_FAILED " + e.getClass().getSimpleName()); }
                }, 3000);
            } catch (Exception e) { Log.w(TAG, "QA_INTERNAL_FAILED " + e.getClass().getSimpleName()); }
        });
    }
    private static void clearQaError() {
        if (qaErrorView != null) {
            if (qaErrorView.getParent() instanceof ViewGroup) ((ViewGroup) qaErrorView.getParent()).removeView(qaErrorView);
            qaErrorView = null;
        }
    }
    private static void reload(String id, long position) throws Exception {
        Uri.Builder url = new Uri.Builder().scheme("https").authority("www.youtube.com").path("watch")
            .appendQueryParameter("v", id);
        String playlist = (String) call(VIDEO, "getPlaylistId");
        if (playlist != null && !playlist.isEmpty()) url.appendQueryParameter("list", playlist);
        if (position > 0) url.appendQueryParameter("t", (position / 1000) + "s");
        Class.forName(LOAD).getMethod("openVideoIntent", String.class, boolean.class).invoke(null, url.toString(), true);
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
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) id = (String) call(VIDEO, "getPlayerResponseVideoId");
        if (id == null) id = "";
        long position = (Long) call(VIDEO, "getVideoTime");
        long length = (Long) call(VIDEO, "getVideoLength");
        String state = String.valueOf(call(STATE, "getCurrent"));
        String type = String.valueOf(call(TYPE, "getCurrent"));
        PlaybackState media = mediaState(a, now);
        int mediaCode = media == null ? -1 : media.getState();
        View errorView = BuildConfig.QA && qaErrorView != null ? qaErrorView : find(a, "player_error_view");
        boolean errorVisible = visible(errorView);
        String errorText = errorView instanceof TextView ? String.valueOf(((TextView) errorView).getText()) : "";
        boolean stateError = PlaybackSignals.stateError(state, mediaCode,
            position > previousPosition && position - previousPosition < 5000);
        PlaybackSignals.Failure failure = PlaybackSignals.failure(errorVisible, errorText, stateError);
        boolean blocked = failure == PlaybackSignals.Failure.ACCOUNT || failure == PlaybackSignals.Failure.CONTENT;
        boolean error = failure != PlaybackSignals.Failure.NONE && !blocked;
        boolean paused = PlaybackSignals.paused(state, mediaCode, errorVisible || stateError);
        boolean regular = type.startsWith("WATCH_WHILE_") && !type.contains("DISMISSED")
            && !(Boolean) call(VIDEO, "lastVideoIdIsShort");
        boolean spinner = a != null && loading(a);
        if (position < 0 || (error && position == 0)) {
            if (media != null && media.getPosition() > 0) position = media.getPosition();
            else position = PlaybackSignals.resumePosition(position, id.equals(currentVideo) ? lastGoodPosition : 0, error);
        }
        boolean progressing = position > previousPosition && position - previousPosition < 5000;
        if (progressing || Math.abs(position - previousPosition) >= 250 || paused || spinner || lastProgressAt == 0)
            lastProgressAt = now;
        boolean silentStall = mediaCode == PlaybackState.STATE_PLAYING && now - lastProgressAt >= 20000;
        // Some upstream builds leave VideoState null until the first pause/resume event.
        // Progress and an open player still block installation, including Shorts/background audio.
        activePlayback = PlaybackSignals.activePlayback(paused, progressing, blocked, policy.waiting(), error,
            spinner, silentStall, !id.isEmpty() && !type.contains("DISMISSED")
                && (state.equals("PLAYING") || state.equals("null") || mediaCode == PlaybackState.STATE_PLAYING));
        if (activePlayback) activeUntil = now + 30000;
        if (!id.isEmpty() && !id.equals(currentVideo)) {
            currentVideo = id; tried.clear(); healthyVideo = id; healthySince = now; lastGoodPosition = 0;
        }
        boolean rebuildingAtZero = position == 0 && policy.lastSwitch() > 0
            && now - policy.lastSwitch() < 15000 && (spinner || mediaCode == PlaybackState.STATE_BUFFERING
                || mediaCode == PlaybackState.STATE_CONNECTING);
        if (!error && !blocked && position >= 0 && !rebuildingAtZero) lastGoodPosition = position;
        if (!progressing || spinner || paused || error || blocked) healthySince = now;
        if (progressing && id.equals(healthyVideo) && now - healthySince >= 90000
                && (!chosen.equals(prefs.getString("healthy_client", ""))
                    || !"healthy".equals(prefs.getString("recovery_status", "")))) {
            prefs.edit().putString("healthy_client", chosen).putString("recovery_status", "healthy").apply();
            Log.i(TAG, "HEALTHY_90_SECONDS " + chosen);
        }
        previousPosition = position;
        boolean eligible = a != null && !a.isFinishing() && regular && !blocked
            && id.matches("[A-Za-z0-9_-]{11}") && !(length > 0 && position >= length - 1500);
        boolean connected = online(app) && !(BuildConfig.QA && qaOffline);
        String reason = !connected ? "network_unavailable" : blocked ? failure.name().toLowerCase(Locale.ROOT)
            : error ? "player_error" : spinner ? "buffering" : silentStall ? "progress_stopped" : "none";
        if (!reason.equals(lastFailure)) {
            lastFailure = reason;
            prefs.edit().putString("last_playback_condition", reason).apply();
            if (!reason.equals("none")) Log.i(TAG, "CONDITION " + reason + " media_state=" + mediaCode);
        }
        boolean qaStall = BuildConfig.QA && a != null && position >= 15000 && qaForceStall;
        if (now - diagnosticAt > (BuildConfig.QA ? 10000 : 60000)) {
            diagnosticAt = now;
            Log.i(TAG, "SAMPLE state=" + state + " media=" + mediaCode + " type=" + type + " position=" + position
                + " loading=" + spinner + " error_view=" + errorVisible + " eligible=" + eligible
                + " activity=" + (a == null ? "none" : a.getClass().getSimpleName()) + " active=" + activePlayback + " fault=" + qaStall);
        }
        RecoveryPolicy.Action action = policy.sample(now, id, qaStall ? 15000 : position, eligible, connected, paused,
            spinner || qaStall || silentStall, error, timeout());
        if (action == RecoveryPolicy.Action.NONE) return;
        if (action == RecoveryPolicy.Action.EXHAUSTED) {
            prefs.edit().putString("recovery_status", "waiting_for_network_or_update").apply();
            StandaloneUpdates.requestCheck();
            Log.w(TAG, "RECOVERY_LIMIT_REACHED");
            return;
        }
        String next = chosen;
        if (action == RecoveryPolicy.Action.REFRESH) tried.clear();
        else {
            tried.add(chosen); next = null;
            for (String option : order()) if (!tried.contains(option)) { next = option; break; }
            if (next == null) return;
        }
        applyClient(next);
        if (qaStall) qaForceStall = false;
        if (BuildConfig.QA) clearQaError();
        prefs.edit().putLong("last_recovery_time", System.currentTimeMillis())
            .putString("recovery_status", "retrying").putLong("last_recovery_position_ms", position).apply();
        Log.i(TAG, "RECOVER " + action + " " + next + " position_ms=" + position + " reason=" + reason);
        // The upstream reload API preserves video, playlist, position and playback speed.
        reload(id, position);
    }
}
