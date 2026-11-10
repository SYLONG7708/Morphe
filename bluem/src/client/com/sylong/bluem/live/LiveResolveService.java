package com.sylong.bluem.live;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import org.json.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** A narrow, same-device interface to Morphe's already-patched public live streams. */
public final class LiveResolveService extends Service {
    private static final String REQUEST = "app.morphe.extension.shared.spoof.requests.StreamingDataRequest";
    private static final String RESPONSE = "app.morphe.extension.shared.innertube.PlayerResponseOuterClass$PlayerResponse";
    private static final String TV_CERT = "b6f8718ac28ae96e1b76bfe7a8c4687e73d2c4f2dcd187a25c6af9427993a9a0";
    private static final String QA_CERT = "b26fca7ea810d7f4c34fcd12814cbe26fdbbab46e5ce6844543efb2f28315203";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long generation;
    private Future<?> pending;
    private int owner = -1;
    private String activeRequest = "";
    private IBinder clientBinder;
    private IBinder.DeathRecipient clientDeath;
    private final Messenger endpoint = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (!trusted(message.sendingUid) || message.replyTo == null) return true;
        final Bundle data = message.getData();
        final String videoId = data.getString("videoId", ""), requestId = data.getString("requestId", "");
        if (message.what == 3) {
            if (owner == message.sendingUid && activeRequest.equals(requestId)) clearRequest();
            return true;
        }
        if (message.what != 1 || !videoId.matches("[A-Za-z0-9_-]{11}") || !requestId.matches("[A-Za-z0-9_-]{1,64}")) return true;
        if (owner != -1 && owner != message.sendingUid) { respond(message.replyTo, requestId, error("busy")); return true; }
        clearRequest();
        owner = message.sendingUid;
        activeRequest = requestId;
        final long token = ++generation;
        final Messenger reply = message.replyTo;
        clientBinder = reply.getBinder();
        clientDeath = () -> main.post(() -> { if (generation == token) clearRequest(); });
        try { clientBinder.linkToDeath(clientDeath, 0); }
        catch (RemoteException disconnected) { clearRequest(); return true; }
        pending = worker.submit(() -> {
            JSONObject result;
            try { result = resolve(videoId); }
            catch (Throwable failure) {
                Throwable reason = failure instanceof InvocationTargetException && failure.getCause() != null ? failure.getCause() : failure;
                Log.w("BlueMLiveBridge", "resolve_failed " + reason.getClass().getSimpleName());
                result = error("upstream_unavailable");
            }
            final JSONObject value = result;
            main.post(() -> { if (generation == token) { clearRequest(); respond(reply, requestId, value); } });
        });
        return true;
    }));

    private void clearRequest() {
        ++generation; owner = -1; activeRequest = "";
        if (pending != null) pending.cancel(true);
        pending = null;
        if (clientBinder != null && clientDeath != null) clientBinder.unlinkToDeath(clientDeath, 0);
        clientBinder = null; clientDeath = null;
    }

    private boolean trusted(int uid) {
        if (uid < 0) return false;
        PackageManager pm = getPackageManager();
        String[] packages = pm.getPackagesForUid(uid);
        if (packages == null) return false;
        for (String pkg : packages) {
            boolean production = "tw.com.sylong.tvcar".equals(pkg);
            boolean qa = BridgeBuildConfig.QA && "tw.com.sylong.tvcar.direct".equals(pkg);
            if (!production && !qa) continue;
            try {
                PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] signers = info.signingInfo.getApkContentsSigners();
                if (signers.length == 1 && hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())).equals(qa ? QA_CERT : TV_CERT)) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }
    private static String hex(byte[] bytes) { StringBuilder s = new StringBuilder(); for (byte b : bytes) s.append(String.format(java.util.Locale.ROOT,"%02x",b)); return s.toString(); }
    private static JSONObject error(String code) { JSONObject value = new JSONObject(); try { value.put("schema",1).put("ok",false).put("error",code); } catch (JSONException ignored) {} return value; }
    private static void respond(Messenger messenger, String id, JSONObject value) {
        Message reply = Message.obtain(null,2); Bundle data = new Bundle(); data.putString("requestId",id); data.putString("result",value.toString()); reply.setData(data);
        try { messenger.send(reply); } catch (RemoteException ignored) { }
    }
    private static Object call(Object target, String name) throws Exception { return target.getClass().getMethod(name).invoke(target); }
    private static boolean publicMedia(String url) {
        Uri uri = Uri.parse(url); String host = uri.getHost();
        return "https".equals(uri.getScheme()) && host != null && host.endsWith(".googlevideo.com")
            && uri.getUserInfo() == null && "1".equals(uri.getQueryParameter("live"));
    }
    private static JSONObject format(Object row) throws Exception {
        int itag = ((Number)call(row,"getItag")).intValue();
        String url = String.valueOf(call(row,"getUrl")), mime = String.valueOf(call(row,"getMimeType"));
        if (!publicMedia(url)) return null;
        int width=0,height=0;
        switch(itag) { case 160:width=256;height=144;break;case 133:width=426;height=240;break;case 134:width=640;height=360;break;case 135:width=854;height=480;break;case 136:width=1280;height=720;break;case 137:width=1920;height=1080;break;default:break; }
        if (height > 0 && mime.startsWith("video/mp4") && mime.contains("avc1")) {
            return new JSONObject().put("itag",itag).put("url",url).put("mime",mime).put("width",width).put("height",height)
                .put("bitrate",((Number)call(row,"getBitrate")).intValue()).put("duration",5);
        }
        if ((itag==139 || itag==140) && mime.startsWith("audio/mp4") && mime.contains("mp4a"))
            return new JSONObject().put("itag",itag).put("url",url).put("mime",mime).put("bitrate",((Number)call(row,"getBitrate")).intValue()).put("duration",5);
        return null;
    }
    private static JSONObject resolve(String videoId) throws Exception {
        Class<?> requestType = Class.forName(REQUEST);
        // Use Morphe's viewer lookup for public live channels. No account headers
        // are supplied or exported through this interface.
        requestType.getMethod("fetchRequest",String.class,Map.class).invoke(null,videoId,Collections.emptyMap());
        Object request = requestType.getMethod("getRequestForVideoId",String.class).invoke(null,videoId);
        Object stream = request == null ? null : call(request,"getStream");
        if (stream == null) return error("upstream_unavailable");
        byte[] bytes = (byte[])call(stream,"streamingData");
        Object response = Class.forName(RESPONSE).getMethod("parseFrom",byte[].class).invoke(null,(Object)bytes);
        String actual = String.valueOf(call(call(response,"getVideoDetails"),"getVideoId"));
        if (!actual.isEmpty() && !actual.equals(videoId)) return error("video_mismatch");
        Object streaming = call(response,"getStreamingData");
        JSONArray video = new JSONArray(); JSONObject audio = null;
        for (Object row : (List<?>)call(streaming,"getAdaptiveFormatsList")) {
            JSONObject item = format(row); if (item == null) continue;
            if (item.has("height")) { if (item.getInt("height")<=720) video.put(item); }
            else if (audio == null || item.getInt("itag")==140) audio=item;
        }
        if (video.length()==0 || audio==null) return error("unsupported_live_formats");
        Object client = call(stream,"client");
        String userAgent = String.valueOf(client.getClass().getField("userAgent").get(client));
        if (userAgent.length()>512 || userAgent.indexOf('\r')>=0 || userAgent.indexOf('\n')>=0) return error("invalid_client");
        Log.i("BlueMLiveBridge","resolved client="+client+" formats="+video.length());
        return new JSONObject().put("schema",1).put("ok",true).put("backend","morphe").put("client",String.valueOf(client))
            .put("videoId",videoId).put("userAgent",userAgent).put("video",video).put("audio",audio);
    }
    @Override public IBinder onBind(Intent intent) { return endpoint.getBinder(); }
    @Override public void onCreate() {
        super.onCreate();
        try {
            Class<?> recovery = Class.forName("com.sylong.bluem.update.PlaybackRecovery");
            Method prepare = recovery.getDeclaredMethod("prepare",android.content.Context.class);
            prepare.setAccessible(true); prepare.invoke(null,getApplicationContext());
        } catch (Exception failure) { Log.w("BlueMLiveBridge","capability_prepare_deferred"); }
    }
    @Override public boolean onUnbind(Intent intent) { clearRequest(); return false; }
    @Override public void onDestroy() { clearRequest(); worker.shutdownNow(); super.onDestroy(); }
}
