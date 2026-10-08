package com.sylong.bluem.update;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import java.io.*;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Self-contained updater; no Manager, overlay permission, service or patching on the car. */
public final class StandaloneUpdates implements Application.ActivityLifecycleCallbacks {
    private static final String TAG = "BlueMUpdates";
    private static StandaloneUpdates instance;
    private static final Map<String, Boolean> installedSignerCache = new ConcurrentHashMap<>();
    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private WeakReference<Activity> current = new WeakReference<>(null);
    private boolean resumed, busy, downloading;
    private long lastCheck, dismissedUntil;
    private long nextCheck;
    private int checkFailures;
    private boolean checkFailureShown;
    private AlertDialog dialog;
    private ProgressDialog progress;
    private int progressValue;
    private String progressLabel;
    private UpdatePolicy.Item ready;
    private UpdatePolicy.Release latest;

    public static synchronized void attach(Activity activity) {
        // The hook runs before onCreate's super call; only application-level registration is done here.
        try {
            if (instance == null) {
                instance = new StandaloneUpdates(activity.getApplicationContext());
                activity.getApplication().registerActivityLifecycleCallbacks(instance);
            }
        } catch (Throwable e) { Log.w(TAG, "Updater initialization unavailable: " + e.getClass().getSimpleName()); }
    }
    private StandaloneUpdates(Context context) {
        app = context;
        prefs = app.getSharedPreferences("bluem_updates", Context.MODE_PRIVATE);
        main.postDelayed(new Runnable() { public void run() { check(); main.postDelayed(this, 30000); } }, 30000);
    }
    private Activity activity() {
        Activity a = current.get();
        return resumed && a != null && !a.isFinishing() && !a.isDestroyed() ? a : null;
    }
    private File directory() throws IOException {
        File dir = new File(app.getFilesDir(), "bluem-updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create update directory");
        return dir;
    }
    private File apkFile(UpdatePolicy.Item item) throws IOException { return new File(directory(), item.sha256 + ".apk"); }
    private byte[] certificate() throws IOException {
        try (InputStream in = app.getAssets().open("bluem-updates/release-cert.der")) { return UpdatePolicy.readLimited(in, 16384); }
    }
    private UpdatePolicy.Release cached() throws Exception {
        byte[] bytes, signature;
        try (InputStream in = new FileInputStream(new File(directory(), "release.json"))) { bytes = UpdatePolicy.readLimited(in, UpdatePolicy.MAX_MANIFEST); }
        try (InputStream in = new FileInputStream(new File(directory(), "release.sig"))) { signature = UpdatePolicy.readLimited(in, 8192); }
        return UpdatePolicy.verify(bytes, signature, certificate(), prefs.getLong("sequence", 0), BuildConfig.QA);
    }
    private void writePrivate(String name, byte[] value) throws IOException {
        File finalFile = new File(directory(), name);
        File tmp = new File(directory(), name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(value); out.getFD().sync(); }
        if (!tmp.renameTo(finalFile)) throw new IOException("Cannot save update state");
    }
    private PackageInfo installed(String pkg) {
        try { return app.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES); }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }
    private static boolean signedBy(PackageInfo info, String cert) throws Exception {
        if (info == null) return false;
        if (info.signingInfo != null) {
            android.content.pm.Signature[] signers = info.signingInfo.getApkContentsSigners();
            if (signers.length == 1 && cert.equals(UpdatePolicy.hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())))) return true;
        }
        if (info.applicationInfo == null || info.applicationInfo.sourceDir == null) return false;
        String key = info.packageName + "/" + info.getLongVersionCode() + "/" + info.lastUpdateTime + "/" + info.applicationInfo.sourceDir + "/" + cert;
        Boolean cached = installedSignerCache.get(key);
        if (cached != null) return cached;
        boolean verified = ApkTrust.signedBy(new File(info.applicationInfo.sourceDir), cert);
        installedSignerCache.put(key, verified);
        return verified;
    }
    private void verifyArchive(File file, UpdatePolicy.Item item) throws Exception {
        UpdatePolicy.require(file.length() == item.size && UpdatePolicy.hash(file).equals(item.sha256), "APK hash or size mismatch");
        PackageInfo archive = app.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        UpdatePolicy.require(archive != null && item.pkg.equals(archive.packageName) && archive.getLongVersionCode() == item.code && item.versionName.equals(archive.versionName),
            "Wrong APK package or version");
        UpdatePolicy.require(ApkTrust.signedBy(file, item.cert), "Invalid APK signing block or untrusted certificate");
        Log.i(TAG, "APK_SIGNATURE_VERIFIED " + item.pkg);
        UpdatePolicy.require(archive.applicationInfo != null && archive.applicationInfo.minSdkVersion <= Build.VERSION.SDK_INT,
            "Incompatible APK SDK");
        UpdatePolicy.require(item.compatible(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS), "Incompatible APK ABI");
        // PackageManager parses APK signing details; the platform installer verifies all signature blocks again.
        PackageInfo existing = installed(item.pkg);
        UpdatePolicy.require(existing == null || signedBy(existing, item.cert), "Installed package uses another signer");
        UpdatePolicy.require(existing == null || existing.getLongVersionCode() < item.code, "No upgrade available");
    }
    private void check() {
        if (busy || downloading || activity() == null || (dialog != null && dialog.isShowing())) return;
        if (!PlaybackRecovery.online(app) || SystemClock.elapsedRealtime() < nextCheck) return;
        lastCheck = SystemClock.elapsedRealtime();
        nextCheck = lastCheck + 6 * 3600000L;
        busy = true;
        worker.execute(() -> {
            try {
                Log.i(TAG, "CHECK_STARTED " + (BuildConfig.QA ? "QA" : "PRODUCTION"));
                // A verified pending APK must remain usable after permission denial, cancellation or offline relaunch.
                String pending = prefs.getString("ready_package", "");
                UpdatePolicy.Release pendingRelease = null;
                if (!pending.isEmpty()) {
                    try { pendingRelease = cached(); }
                    catch (Exception invalidCache) {
                        // A corrupt/restored cache (or an explicit QA-to-production transition) must not block future checks.
                        prefs.edit().remove("ready_file").remove("ready_package").remove("waiting_permission")
                            .remove("installer_launched").remove("accepted_sequence").commit();
                        Log.w(TAG, "PENDING_METADATA_REJECTED " + invalidCache.getClass().getSimpleName());
                    }
                }
                if (pendingRelease != null) {
                    UpdatePolicy.Release release = pendingRelease;
                    UpdatePolicy.Item item = null;
                    for (UpdatePolicy.Item it : release.items) if (it.pkg.equals(pending)) item = it;
                    UpdatePolicy.require(item != null, "Pending package missing from signed release");
                    PackageInfo present = installed(item.pkg);
                    if (present != null && present.getLongVersionCode() >= item.code && signedBy(present, item.cert)) {
                        if (present.getLongVersionCode() == item.code) {
                            UpdatePolicy.require(UpdatePolicy.hash(new File(present.applicationInfo.sourceDir)).equals(item.sha256), "Installed APK differs from release");
                        }
                        prefs.edit().remove("ready_file").remove("ready_package").remove("waiting_permission").remove("installer_launched").commit();
                        apkFile(item).delete();
                        Log.i(TAG, "INSTALL_VERIFIED " + item.pkg + " " + present.getLongVersionCode());
                        evaluate(release);
                    } else {
                        try { verifyArchive(apkFile(item), item); }
                        catch (Exception invalidApk) {
                            apkFile(item).delete();
                            prefs.edit().remove("ready_file").remove("ready_package").commit();
                            throw invalidApk;
                        }
                        UpdatePolicy.Item result = item;
                        main.post(() -> {
                            busy = false; latest = release; ready = result;
                            if (prefs.getBoolean("waiting_permission", false) && app.getPackageManager().canRequestPackageInstalls()) {
                                prefs.edit().remove("waiting_permission").commit();
                                installReady();
                            } else showReady();
                        });
                    }
                    return;
                }
                byte[] bytes = UpdatePolicy.fetch(BuildConfig.CHANNEL_URL, UpdatePolicy.MAX_MANIFEST, BuildConfig.QA);
                byte[] signature = UpdatePolicy.fetch(BuildConfig.CHANNEL_URL + ".sig", 8192, BuildConfig.QA);
                UpdatePolicy.Release release = UpdatePolicy.verify(bytes, signature, certificate(), prefs.getLong("sequence", 0), BuildConfig.QA);
                applyPlaybackPolicy(bytes);
                try {
                    UpdatePolicy.Recommendation official = UpdatePolicy.recommended(
                        UpdatePolicy.fetch(BuildConfig.UPSTREAM_URL, UpdatePolicy.MAX_UPSTREAM, BuildConfig.QA), Build.VERSION.SDK_INT);
                    Log.i(TAG, "OFFICIAL_RECOMMENDED " + official.youtubeVersion + " PATCHES " + official.patchesVersion);
                    if (!release.recommendation.matches(official)) {
                        prefs.edit().putString("upstream_status", "new_stable_build_pending").apply();
                    }
                } catch (Exception unavailable) {
                    Log.i(TAG, "UPSTREAM_METADATA_UNAVAILABLE_USING_SIGNED_RELEASE");
                }
                checkFailures = 0;
                writePrivate("release.json", bytes);
                writePrivate("release.sig", signature);
                prefs.edit().putLong("sequence", release.sequence).commit();
                evaluate(release);
            } catch (Exception e) {
                Log.w(TAG, "CHECK_UNAVAILABLE " + e.getClass().getSimpleName() + ": " + e.getMessage());
                main.post(() -> {
                    busy = false;
                    long[] retry = {60000, 300000, 900000, 3600000};
                    nextCheck = SystemClock.elapsedRealtime() + retry[Math.min(checkFailures++, retry.length - 1)];
                    prefs.edit().putString("update_status", "offline_or_retry_later").apply();
                });
            }
        });
    }
    public static void requestCheck() {
        StandaloneUpdates value = instance;
        if (value != null) value.main.post(() -> {
            value.nextCheck = Math.min(value.nextCheck, SystemClock.elapsedRealtime() + 3000);
            value.main.postDelayed(value::check, 3100);
        });
    }
    private void applyPlaybackPolicy(byte[] signedBytes) throws Exception {
        JSONObject recovery = new JSONObject(new String(signedBytes, "UTF-8")).optJSONObject("playback");
        if (recovery == null || recovery.optInt("schema") != 1) return;
        long timeout = recovery.optLong("buffering_ms", 6000);
        UpdatePolicy.require(timeout >= 4000 && timeout <= 30000, "Invalid recovery timeout");
        org.json.JSONArray list = recovery.getJSONArray("clients");
        List<String> clients = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            String item = list.getString(i);
            UpdatePolicy.require(Arrays.asList("TV_SABR", "ANDROID_CREATOR", "TV_SIMPLY").contains(item)
                && !clients.contains(item), "Invalid recovery client");
            clients.add(item);
        }
        UpdatePolicy.require(clients.size() >= 2 && clients.size() <= 3, "Recovery clients missing");
        prefs.edit().putLong("buffering_ms", timeout).putString("playback_clients", String.join(",", clients)).apply();
        Log.i(TAG, "SIGNED_RECOVERY_POLICY_APPLIED");
    }
    private void evaluate(UpdatePolicy.Release release) throws Exception {
        List<UpdatePolicy.Item> updates = new ArrayList<>();
        for (UpdatePolicy.Item item : release.items) {
            PackageInfo present = installed(item.pkg);
            if (present != null && !signedBy(present, item.cert)) {
                Log.w(TAG, "SKIP_DIFFERENT_SIGNER " + item.pkg);
                continue;
            }
            long code = present == null ? 0 : present.getLongVersionCode();
            if (code < item.code && item.compatible(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)) updates.add(item);
        }
        main.post(() -> {
            busy = false; latest = release; ready = null;
            if (updates.isEmpty()) {
                prefs.edit().remove("accepted_sequence").commit();
                Log.i(TAG, "UP_TO_DATE " + release.sequence);
                return;
            }
            if (activity() == null) return;
            if (PlaybackRecovery.isPlaybackActive()) {
                Log.i(TAG, "UPDATE_DEFERRED_ACTIVE_PLAYBACK");
                nextCheck = SystemClock.elapsedRealtime() + 60000;
                prefs.edit().putString("update_status", "waiting_for_playback_idle").apply();
                return;
            }
            prefs.edit().putLong("accepted_sequence", release.sequence).apply();
            download(updates.get(0));
        });
    }
    private void snooze() {
        dialog = null; dismissedUntil = SystemClock.elapsedRealtime() + 3600000;
        prefs.edit().putLong("install_retry_after", System.currentTimeMillis() + 3600000).apply();
    }
    private void showProgress() { /* Verified updates download quietly while playback is idle. */ }
    private void download(UpdatePolicy.Item item) {
        if (downloading || activity() == null) return;
        downloading = true; cancelled.set(false); progressLabel = item.label; progressValue = 0;
        showProgress();
        worker.execute(() -> {
            try {
                File target = apkFile(item);
                if (!target.isFile() || target.length() != item.size || !UpdatePolicy.hash(target).equals(item.sha256)) {
                    File temp = new File(directory(), item.sha256 + ".part");
                    UpdatePolicy.require(directory().getUsableSpace() > item.size + 64L * 1024 * 1024, "Insufficient storage");
                    HttpURLConnection c = UpdatePolicy.connect(item.url, BuildConfig.QA);
                    try {
                        long advertised = c.getContentLengthLong();
                        UpdatePolicy.require(advertised == -1 || advertised == item.size, "Wrong download size");
                        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temp)) {
                            byte[] buffer = new byte[131072]; long total = 0; int last = -1;
                            for (int n; (n = in.read(buffer)) != -1;) {
                                if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                                total += n; UpdatePolicy.require(total <= item.size, "Oversized APK");
                                out.write(buffer, 0, n);
                                int percent = (int) (total * 100 / item.size);
                                if (percent != last) { last = percent; main.post(() -> { progressValue = percent; if (progress != null) progress.setProgress(percent); }); }
                            }
                            out.getFD().sync();
                        }
                        verifyArchive(temp, item);
                        if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                        if (!temp.renameTo(target)) throw new IOException("Cannot save APK");
                    } finally { c.disconnect(); temp.delete(); }
                }
                verifyArchive(target, item);
                prefs.edit().putString("ready_package", item.pkg).putString("ready_file", target.getName())
                    .remove("installer_launched").remove("waiting_permission").commit();
                Log.i(TAG, "APK_VERIFIED " + item.pkg + " " + item.code);
                main.post(() -> { finishProgress(); ready = item; installReady(); });
            } catch (Exception e) {
                Log.w(TAG, "DOWNLOAD_FAILED " + e.getClass().getSimpleName() + ": " + e.getMessage());
                prefs.edit().remove("accepted_sequence").commit();
                main.post(() -> {
                    finishProgress();
                    nextCheck = SystemClock.elapsedRealtime() + 300000;
                    prefs.edit().putString("update_status", "download_retry_later").apply();
                });
            }
        });
    }
    private void finishProgress() {
        downloading = false;
        if (progress != null) { progress.dismiss(); progress = null; }
    }
    private void showReady() { installReady(); }
    private void installReady() {
        Activity a = activity();
        if (a == null || ready == null || busy) return;
        if (PlaybackRecovery.isPlaybackActive() || System.currentTimeMillis() < prefs.getLong("install_retry_after", 0)) {
            nextCheck = SystemClock.elapsedRealtime() + 60000;
            return;
        }
        if (InstallResultReceiver.pendingAction != null) {
            Intent confirmation = InstallResultReceiver.pendingAction;
            InstallResultReceiver.pendingAction = null;
            try {
                a.startActivity(confirmation);
                prefs.edit().putLong("install_retry_after", System.currentTimeMillis() + 3600000).apply();
            } catch (RuntimeException e) { Log.w(TAG, "SYSTEM_CONFIRMATION_DEFERRED"); }
            return;
        }
        int prior = prefs.getInt("install_session", -1);
        if (prior >= 0) {
            PackageInstaller.SessionInfo info = app.getPackageManager().getPackageInstaller().getSessionInfo(prior);
            if (info != null && System.currentTimeMillis() - prefs.getLong("install_started", 0) < 3600000) {
                nextCheck = SystemClock.elapsedRealtime() + 60000; return;
            }
            try { app.getPackageManager().getPackageInstaller().abandonSession(prior); }
            catch (RuntimeException ignored) { }
            prefs.edit().remove("install_session").apply();
        }
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            if (System.currentTimeMillis() < prefs.getLong("permission_prompt_after", 0)) return;
            prefs.edit().putLong("permission_prompt_after", System.currentTimeMillis() + 86400000).apply();
            dialog = new AlertDialog.Builder(a).setTitle("允許自動更新")
                .setMessage("請允許藍色 M 安裝更新。系統只需設定一次，之後會在播放閒置時套用已驗證的版本。")
                .setPositiveButton("前往設定", (d, w) -> {
                    dialog = null;
                    prefs.edit().putBoolean("waiting_permission", true).apply();
                    a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName())));
                }).setNegativeButton("稍後", (d, w) -> snooze()).setOnCancelListener(d -> snooze()).create();
            dialog.show();
            return;
        }
        UpdatePolicy.Item item = ready;
        busy = true;
        worker.execute(() -> {
            int sessionId = -1;
            try {
                verifyArchive(apkFile(item), item);
                PackageInstaller installer = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(item.pkg); params.setSize(item.size);
                if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                sessionId = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(sessionId);
                     InputStream in = new FileInputStream(apkFile(item));
                     OutputStream out = session.openWrite("base.apk", 0, item.size)) {
                    byte[] buffer = new byte[131072];
                    for (int n; (n = in.read(buffer)) != -1;) out.write(buffer, 0, n);
                    session.fsync(out);
                }
                int committedId = sessionId;
                main.post(() -> {
                    busy = false;
                    if (activity() == null || PlaybackRecovery.isPlaybackActive()) {
                        installer.abandonSession(committedId);
                        nextCheck = SystemClock.elapsedRealtime() + 60000;
                        return;
                    }
                    try (PackageInstaller.Session session = installer.openSession(committedId)) {
                        Intent result = new Intent(app, InstallResultReceiver.class).setAction("com.sylong.bluem.INSTALL_RESULT");
                        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                        PendingIntent callback = PendingIntent.getBroadcast(app, committedId, result, flags);
                        prefs.edit().putInt("install_session", committedId).putBoolean("installer_launched", true)
                            .putLong("install_started", System.currentTimeMillis()).putString("update_status", "installing").commit();
                        session.commit(callback.getIntentSender());
                        Log.i(TAG, "INSTALL_SESSION_COMMITTED " + item.pkg + " " + item.code);
                        nextCheck = SystemClock.elapsedRealtime() + 60000;
                    } catch (Exception e) {
                        try { installer.abandonSession(committedId); } catch (RuntimeException ignored) { }
                        prefs.edit().remove("install_session").putString("update_status", "install_retry_later").apply();
                        Log.w(TAG, "INSTALL_COMMIT_RETRY " + e.getClass().getSimpleName());
                    }
                });
            } catch (Exception e) {
                if (sessionId >= 0) try { app.getPackageManager().getPackageInstaller().abandonSession(sessionId); } catch (RuntimeException ignored) { }
                Log.w(TAG, "INSTALL_REJECTED " + e.getClass().getSimpleName());
                main.post(() -> { busy = false; nextCheck = SystemClock.elapsedRealtime() + 3600000; });
            }
        });
    }
    private void showError(String title, String message) {
        Activity a = activity();
        if (a == null) return;
        dialog = new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("確定", (d, w) -> snooze())
            .setOnCancelListener(d -> snooze()).create();
        dialog.show();
    }
    @Override public void onActivityResumed(Activity a) {
        if (!PlaybackSignals.watchActivity(a.getClass().getName())) return;
        current = new WeakReference<>(a); resumed = true;
        Log.i(TAG, "WATCH_ACTIVITY_RESUMED " + a.getClass().getSimpleName());
        PlaybackRecovery.resume(a);
        if (prefs.getBoolean("waiting_permission", false)) nextCheck = 0;
        if (downloading) showProgress();
        else main.postDelayed(() -> { if (activity() == a) check(); }, 1100);
    }
    @Override public void onActivityPaused(Activity a) { if (current.get() == a) resumed = false; PlaybackRecovery.pause(a); }
    @Override public void onActivityDestroyed(Activity a) {
        if (current.get() == a) {
            if (dialog != null) { dialog.dismiss(); dialog = null; }
            if (progress != null) { progress.dismiss(); progress = null; }
            current.clear(); resumed = false;
        }
    }
    @Override public void onActivityCreated(Activity a, Bundle b) {}
    @Override public void onActivityStarted(Activity a) {}
    @Override public void onActivityStopped(Activity a) {}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
}
