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
    private boolean resumed;
    private volatile boolean busy, downloading;
    private boolean manualCheck;
    private volatile HttpURLConnection activeConnection;
    private long lastCheck, dismissedUntil;
    private long nextCheck;
    private int checkFailures;
    private boolean checkFailureShown;
    private AlertDialog dialog;
    private ProgressDialog progress;
    private int progressValue;
    private String progressLabel;
    private String progressStage;
    private long progressBytes, progressTotal;
    private UpdatePolicy.Item ready;
    private UpdatePolicy.Release latest;

    public static synchronized void attach(Activity activity) {
        // The hook runs before onCreate's super call; only application-level registration is done here.
        try {
            if (instance == null) {
                instance = new StandaloneUpdates(activity.getApplicationContext());
                PlaybackRecovery.prepare(activity.getApplicationContext());
                activity.getApplication().registerActivityLifecycleCallbacks(instance);
            }
            StartupSupport.ensureService(activity);
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
        PackageInfo archive = app.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES | PackageManager.GET_META_DATA);
        UpdatePolicy.require(archive != null && item.pkg.equals(archive.packageName) && archive.getLongVersionCode() == item.code && item.versionName.equals(archive.versionName),
            "Wrong APK package or version");
        UpdatePolicy.require(ApkTrust.signedBy(file, item.cert), "Invalid APK signing block or untrusted certificate");
        Log.i(TAG, "APK_SIGNATURE_VERIFIED " + item.pkg);
        UpdatePolicy.require(archive.applicationInfo != null && archive.applicationInfo.minSdkVersion <= Build.VERSION.SDK_INT,
            "Incompatible APK SDK");
        if (UpdatePolicy.YOUTUBE.equals(item.pkg)) {
            Bundle metadata = archive.applicationInfo.metaData;
            Object value = metadata == null ? null : metadata.get("com.sylong.bluem.UPDATE_CLIENT_REVISION");
            int revision = value == null ? 0 : Integer.parseInt(String.valueOf(value));
            UpdatePolicy.require(UpdatePolicy.preservesRecovery(item.pkg, revision), "Update would remove playback compatibility repair");
        }
        UpdatePolicy.require(item.compatible(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS), "Incompatible APK ABI");
        // PackageManager parses APK signing details; the platform installer verifies all signature blocks again.
        PackageInfo existing = installed(item.pkg);
        UpdatePolicy.require(existing == null || signedBy(existing, item.cert), "Installed package uses another signer");
        UpdatePolicy.require(existing == null || existing.getLongVersionCode() < item.code, "No upgrade available");
    }
    private void check() {
        if (busy || downloading || activity() == null || (dialog != null && dialog.isShowing())) return;
        if (SystemClock.elapsedRealtime() < nextCheck) return;
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
                            } else if (InstallResultReceiver.pendingAction != null) installReady();
                            else showReady();
                        });
                    }
                    return;
                }
                if (!PlaybackRecovery.online(app)) throw new IOException("Network unavailable");
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
                Log.w(TAG, "CHECK_UNAVAILABLE " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                main.post(() -> {
                    busy = false;
                    long[] retry = {60000, 300000, 900000, 3600000};
                    nextCheck = SystemClock.elapsedRealtime() + retry[Math.min(checkFailures++, retry.length - 1)];
                    prefs.edit().putString("update_status", "offline_or_retry_later").apply();
                    if (manualCheck) {
                        manualCheck = false;
                        showRetry("目前無法檢查更新", "請確認網路連線後重試。目前的版本與資料會保留。", this::manualCheck);
                    }
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
    /** Replaces the upstream browser-and-exit download action. */
    public static void openMicroGUpdate(Activity a, String ignoredUrl) {
        attach(a);
        StandaloneUpdates value = instance;
        if (value == null) return;
        value.main.post(() -> {
            if (!a.isFinishing() && !a.isDestroyed()) {
                value.manualCheck();
            }
        });
    }
    /** One signed update feed owns both packages; do not show a second upstream prompt. */
    public static void checkMicroG(Activity a) { attach(a); requestCheck(); }
    static void qaOpenMicroG() {
        StandaloneUpdates value = instance;
        if (BuildConfig.QA && value != null && value.activity() != null)
            StartupSupport.open(value.activity(), "https://github.com/MorpheApp/MicroG-RE/releases/latest");
    }
    private void manualCheck() {
        if (downloading) { showProgress(); return; }
        manualCheck = true; dismissedUntil = 0;
        prefs.edit().remove("install_retry_after").remove("permission_prompt_after").apply();
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        nextCheck = 0; check();
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
            UpdatePolicy.require(ClientPolicy.ALLOWED.contains(item)
                && !clients.contains(item), "Invalid recovery client");
            clients.add(item);
        }
        UpdatePolicy.require(clients.size() >= 2 && clients.size() <= ClientPolicy.ALLOWED.size(), "Recovery clients missing");
        prefs.edit().putLong("buffering_ms", timeout).putString("playback_clients", String.join(",", clients)).apply();
        Log.i(TAG, "SIGNED_RECOVERY_POLICY_APPLIED");
    }
    private void evaluate(UpdatePolicy.Release release) throws Exception {
        List<UpdatePolicy.Item> updates = new ArrayList<>();
        boolean incompatibleRecovery = false;
        for (UpdatePolicy.Item item : release.items) {
            PackageInfo present = installed(item.pkg);
            if (present != null && !signedBy(present, item.cert)) {
                Log.w(TAG, "SKIP_DIFFERENT_SIGNER " + item.pkg);
                continue;
            }
            long code = present == null ? 0 : present.getLongVersionCode();
            if (code < item.code && !UpdatePolicy.preservesRecovery(item.pkg, release.clientRevision)) {
                incompatibleRecovery = true;
                Log.w(TAG, "UPDATE_REQUIRES_RECOVERY_REVISION " + UpdatePolicy.MIN_RECOVERY_REVISION);
                continue;
            }
            if (code < item.code && item.compatible(Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS)) updates.add(item);
        }
        final boolean waitingCompatible = incompatibleRecovery;
        main.post(() -> {
            busy = false; latest = release; ready = null;
            if (updates.isEmpty()) {
                prefs.edit().remove("accepted_sequence").commit();
                prefs.edit().putString("update_status", waitingCompatible ? "waiting_for_compatible_recovery_update" : "up_to_date").apply();
                Log.i(TAG, (waitingCompatible ? "UPDATE_HELD_TO_PRESERVE_RECOVERY " : "UP_TO_DATE ") + release.sequence);
                if (manualCheck) {
                    manualCheck = false;
                    showError("更新檢查完成", waitingCompatible ? "相容的新版仍在準備中，會保留目前版本。" : "藍色 M 與 MicroG 已是此更新頻道的最新版本。");
                }
                return;
            }
            if (activity() == null) return;
            if (PlaybackRecovery.isPlaybackActive()) {
                Log.i(TAG, "UPDATE_DEFERRED_ACTIVE_PLAYBACK");
                nextCheck = SystemClock.elapsedRealtime() + 60000;
                prefs.edit().putString("update_status", "waiting_for_playback_idle").apply();
                return;
            }
            if (!manualCheck && SystemClock.elapsedRealtime() < dismissedUntil) return;
            manualCheck = false;
            UpdatePolicy.Item item = updates.get(0);
            Activity a = activity();
            if (a == null) return;
            StringBuilder message = new StringBuilder("可用更新：\n");
            for (UpdatePolicy.Item update : updates) message.append(update.label).append(" ").append(update.versionName).append('\n');
            message.append("\n下載會留在目前畫面並顯示進度，完成後由您選擇安裝。");
            dialog = new AlertDialog.Builder(a).setTitle("藍色 M 更新")
                .setMessage(message.toString())
                .setPositiveButton("立即下載", (d, w) -> {
                    dialog = null;
                    prefs.edit().putLong("accepted_sequence", release.sequence).apply();
                    download(item);
                }).setNegativeButton("稍後", (d, w) -> snooze()).setOnCancelListener(d -> snooze()).create();
            showDialog(dialog);
        });
    }
    private void snooze() {
        dialog = null; dismissedUntil = SystemClock.elapsedRealtime() + 3600000;
        prefs.edit().putLong("install_retry_after", System.currentTimeMillis() + 3600000).apply();
    }
    private void showDialog(AlertDialog value) {
        try { if (activity() != null) value.show(); }
        catch (RuntimeException e) { Log.w(TAG, "DIALOG_DEFERRED " + e.getClass().getSimpleName()); dialog = null; }
    }
    private void showProgress() {
        Activity a = activity();
        if (a == null || !downloading) return;
        String message = progressStage != null ? progressStage : String.format(Locale.TAIWAN,
            "正在下載 %s\n%.1f / %.1f MB", progressLabel, progressBytes / 1048576.0, progressTotal / 1048576.0);
        if (progress == null) {
            progress = new ProgressDialog(a);
            progress.setTitle("下載 " + progressLabel + " 更新");
            progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progress.setMax(100);
            progress.setProgressNumberFormat("%1$d%%");
            progress.setProgressPercentFormat(null);
            progress.setMessage(message);
            progress.setCancelable(false);
            progress.setButton(ProgressDialog.BUTTON_NEGATIVE, "取消下載", (d, w) -> cancelDownload());
            try { progress.show(); }
            catch (RuntimeException e) { progress = null; return; }
        }
        progress.setProgress(progressValue);
        progress.setMessage(message);
    }
    private void cancelDownload() {
        cancelled.set(true);
        HttpURLConnection connection = activeConnection;
        if (connection != null) workerCancel.execute(connection::disconnect);
    }
    private final ExecutorService workerCancel = Executors.newSingleThreadExecutor();
    private void download(UpdatePolicy.Item item) {
        if (downloading || activity() == null) return;
        downloading = true; cancelled.set(false); progressLabel = item.label; progressValue = 0;
        progressStage = null; progressBytes = 0; progressTotal = item.size;
        showProgress();
        worker.execute(() -> {
            try {
                File target = apkFile(item);
                if (!target.isFile() || target.length() != item.size || !UpdatePolicy.hash(target).equals(item.sha256)) {
                    File temp = new File(directory(), item.sha256 + ".part");
                    if (temp.exists() && !temp.delete()) throw new IOException("Cannot reset incomplete download");
                    UpdatePolicy.require(directory().getUsableSpace() > item.size + 64L * 1024 * 1024, "Insufficient storage");
                    HttpURLConnection c = UpdatePolicy.connect(item.url, BuildConfig.QA);
                    activeConnection = c;
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
                                if (percent != last) {
                                    last = percent; long downloadedBytes = total;
                                    main.post(() -> { progressValue = percent; progressBytes = downloadedBytes; showProgress(); });
                                }
                            }
                            out.getFD().sync();
                        }
                        main.post(() -> { progressStage = "下載完成，正在驗證檔案與簽章…"; showProgress(); });
                        verifyArchive(temp, item);
                        if (cancelled.get()) throw new InterruptedIOException("Cancelled");
                        if (!temp.renameTo(target)) throw new IOException("Cannot save APK");
                    } finally { activeConnection = null; c.disconnect(); temp.delete(); }
                }
                verifyArchive(target, item);
                prefs.edit().putString("ready_package", item.pkg).putString("ready_file", target.getName())
                    .remove("installer_launched").remove("waiting_permission").commit();
                Log.i(TAG, "APK_VERIFIED " + item.pkg + " " + item.code);
                main.post(() -> { finishProgress(); ready = item; showReady(); });
            } catch (Exception e) {
                Log.w(TAG, "DOWNLOAD_FAILED " + e.getClass().getSimpleName() + ": " + e.getMessage());
                prefs.edit().remove("accepted_sequence").commit();
                main.post(() -> {
                    finishProgress();
                    nextCheck = SystemClock.elapsedRealtime() + 300000;
                    prefs.edit().putString("update_status", "download_retry_later").apply();
                    if (cancelled.get()) { snooze(); return; }
                    String reason = !PlaybackRecovery.online(app) ? "網路已中斷，連線恢復後可重新下載。" :
                        e.getMessage() != null && e.getMessage().contains("Insufficient storage") ? "儲存空間不足，請騰出空間後重試。" :
                        e instanceof SecurityException ? "下載檔案未通過安全驗證，未安裝。請重新下載。" : "下載未完成，請檢查網路後重試。";
                    showRetry("更新下載未完成", reason, () -> download(item));
                });
            }
        });
    }
    private void finishProgress() {
        downloading = false;
        if (progress != null) { progress.dismiss(); progress = null; }
    }
    private void showReady() {
        Activity a = activity();
        if (a == null || ready == null || dialog != null && dialog.isShowing()) return;
        if (System.currentTimeMillis() < prefs.getLong("install_retry_after", 0)) return;
        UpdatePolicy.Item item = ready;
        dialog = new AlertDialog.Builder(a).setTitle("下載完成：" + item.label + " " + item.versionName)
            .setMessage("檔案與簽章驗證通過。\n\n安裝時 Android 可能暫時關閉藍色 M；完成後重新開啟即可接續。帳號與設定會保留。")
            .setPositiveButton("安裝更新", (d, w) -> {
                dialog = null;
                prefs.edit().remove("install_retry_after").remove("permission_prompt_after").apply();
                Log.i(TAG, "USER_INSTALL_REQUEST " + item.pkg);
                installReady();
            }).setNegativeButton("稍後安裝", (d, w) -> snooze()).setOnCancelListener(d -> snooze()).create();
        showDialog(dialog);
        prefs.edit().putString("update_status", "downloaded_waiting_for_install").apply();
    }
    private void showRetry(String title, String message, Runnable retry) {
        Activity a = activity(); if (a == null) return;
        dialog = new AlertDialog.Builder(a).setTitle(title).setMessage(message)
            .setPositiveButton("重試", (d,w) -> { dialog = null; retry.run(); })
            .setNegativeButton("稍後", (d,w) -> snooze()).setOnCancelListener(d -> snooze()).create();
        showDialog(dialog);
    }
    private void installReady() {
        Activity a = activity();
        Log.i(TAG, "INSTALL_GATE foreground=" + (a != null) + " ready=" + (ready != null)
            + " busy=" + busy + " playback=" + PlaybackRecovery.isPlaybackActive());
        if (a == null || ready == null || busy) return;
        if (PlaybackRecovery.isPlaybackActive()) {
            nextCheck = SystemClock.elapsedRealtime() + 60000;
            showRetry("等待安裝更新", "請先暫停影片或關閉小播放器，等待約 30 秒再按重試。已下載的更新會保留。", this::installReady);
            return;
        }
        if (System.currentTimeMillis() < prefs.getLong("install_retry_after", 0)) {
            nextCheck = SystemClock.elapsedRealtime() + 60000;
            showReady(); return;
        }
        if (InstallResultReceiver.pendingAction != null) {
            Intent confirmation = InstallResultReceiver.pendingAction;
            InstallResultReceiver.pendingAction = null;
            try {
                a.startActivity(confirmation);
                prefs.edit().putLong("install_retry_after", System.currentTimeMillis() + 3600000).apply();
            } catch (RuntimeException e) {
                InstallResultReceiver.pendingAction = confirmation;
                Log.w(TAG, "SYSTEM_CONFIRMATION_DEFERRED");
                showRetry("繼續安裝更新", "系統確認畫面尚未開啟，請按重試。已驗證的下載會保留。", this::installReady);
            }
            return;
        }
        int prior = prefs.getInt("install_session", -1);
        if (prior >= 0) {
            PackageInstaller.SessionInfo info = app.getPackageManager().getPackageInstaller().getSessionInfo(prior);
            if (info != null && System.currentTimeMillis() - prefs.getLong("install_started", 0) < 3600000) {
                nextCheck = SystemClock.elapsedRealtime() + 60000;
                if (info.isActive() && !prefs.getBoolean("install_confirmation_pending", false)) {
                    showRetry("系統正在處理安裝", "請等候系統完成；若確認畫面被關閉，可稍後按重試。", this::installReady);
                    return;
                }
                // A process restart loses the in-memory confirmation Intent, but
                // the sealed OS session survives. Recommit that same owned session
                // to obtain a fresh OS callback; do not discard the staged APK.
                try (PackageInstaller.Session session = app.getPackageManager().getPackageInstaller().openSession(prior)) {
                    session.commit(installCallback(prior).getIntentSender());
                    Log.i(TAG, "INSTALL_SESSION_RESUMED " + prior);
                } catch (Exception e) {
                    Log.w(TAG, "INSTALL_RESUME_RETRY " + e.getClass().getSimpleName());
                    showRetry("繼續安裝更新", "系統暫時無法繼續安裝，請稍後重試。下載檔案已保留。", this::installReady);
                }
                return;
            }
            try { app.getPackageManager().getPackageInstaller().abandonSession(prior); }
            catch (RuntimeException ignored) { }
            prefs.edit().remove("install_session").remove("install_confirmation_pending").apply();
        }
        if (!app.getPackageManager().canRequestPackageInstalls()) {
            if (System.currentTimeMillis() < prefs.getLong("permission_prompt_after", 0)) return;
            prefs.edit().putLong("permission_prompt_after", System.currentTimeMillis() + 86400000).apply();
            dialog = new AlertDialog.Builder(a).setTitle("允許自動更新")
                .setMessage("請允許藍色 M 安裝更新。系統只需設定一次，完成後請返回這裡繼續安裝。")
                .setPositiveButton("前往設定", (d, w) -> {
                    dialog = null;
                    prefs.edit().putBoolean("waiting_permission", true).apply();
                    try { a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.getPackageName()))); }
                    catch (RuntimeException e) { showError("請允許安裝更新", "請從系統設定允許藍色 M 安裝應用程式，再回到此畫面重試。"); }
                }).setNegativeButton("稍後", (d, w) -> snooze()).setOnCancelListener(d -> snooze()).create();
            showDialog(dialog);
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
                        prefs.edit().putInt("install_session", committedId).putBoolean("installer_launched", true)
                            .putBoolean("install_confirmation_pending", false)
                            .putLong("install_started", System.currentTimeMillis()).putString("update_status", "installing").commit();
                        session.commit(installCallback(committedId).getIntentSender());
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
                main.post(() -> { busy = false; nextCheck = SystemClock.elapsedRealtime() + 3600000;
                    showRetry("尚未完成安裝", "下載檔案已保留，請確認系統安裝權限與可用空間後重試。", this::installReady); });
            }
        });
    }
    private PendingIntent installCallback(int sessionId) {
        Intent result = new Intent(app, InstallResultReceiver.class).setAction("com.sylong.bluem.INSTALL_RESULT");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(app, sessionId, result, flags);
    }
    private void showError(String title, String message) {
        Activity a = activity();
        if (a == null) return;
        dialog = new AlertDialog.Builder(a).setTitle(title).setMessage(message).setPositiveButton("確定", (d, w) -> snooze())
            .setOnCancelListener(d -> snooze()).create();
        showDialog(dialog);
    }
    @Override public void onActivityResumed(Activity a) {
        if (!PlaybackSignals.watchActivity(a.getClass().getName()) && !(a instanceof SetupActivity)) return;
        current = new WeakReference<>(a); resumed = true;
        Log.i(TAG, "WATCH_ACTIVITY_RESUMED " + a.getClass().getSimpleName());
        if (!(a instanceof SetupActivity)) PlaybackRecovery.resume(a);
        if (prefs.getBoolean("waiting_permission", false) || ready != null) nextCheck = 0;
        if (downloading) showProgress();
        else main.postDelayed(() -> { if (activity() == a) check(); }, 1100);
    }
    @Override public void onActivityPaused(Activity a) {
        if (current.get() == a) {
            resumed = false;
            if (dialog != null) { nextCheck = 0; dialog.dismiss(); dialog = null; }
            if (progress != null) { progress.dismiss(); progress = null; }
        }
        PlaybackRecovery.pause(a);
    }
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
