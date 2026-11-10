import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Reject an upstream APK before publication if a reflected playback API changed. */
public final class CheckRecoveryContract {
    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(args[0])) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                for (ClassDef type : DexBackedDexFile.fromInputStream(null,
                        new BufferedInputStream(zip.getInputStream(entry))).getClasses())
                    if (type.getType().startsWith("Lapp/morphe/extension/")) classes.put(type.getType(), type);
            }
            String video = "youtube/patches/VideoInformation";
            method(classes, video, "getVideoTime", "J");
            method(classes, video, "getVideoLength", "J");
            method(classes, video, "getVideoId", "Ljava/lang/String;");
            method(classes, video, "getPlayerResponseVideoId", "Ljava/lang/String;");
            method(classes, video, "getPlaylistId", "Ljava/lang/String;");
            method(classes, video, "lastVideoIdIsShort", "Z");
            method(classes, "youtube/patches/LoadVideoPatch", "initializeReloadVideo", "V");
            method(classes, "youtube/patches/LoadVideoPatch", "openVideoIntent", "V", "Ljava/lang/String;", "Z");
            method(classes, "youtube/patches/LoadVideoPatch", "openVideoIntentWithInternalContext", "V", "Ljava/lang/String;");
            method(classes, "youtube/patches/playback/speed/RememberPlaybackSpeedPatch", "preservePlaybackParametersForReload", "V");
            method(classes, "youtube/shared/VideoState", "getCurrent", "Lapp/morphe/extension/youtube/shared/VideoState;");
            method(classes, "youtube/shared/PlayerType", "getCurrent", "Lapp/morphe/extension/youtube/shared/PlayerType;");
            method(classes, "shared/spoof/SpoofVideoStreamsPatch", "setClientsToUse", "V",
                   "Ljava/util/List;", "Lapp/morphe/extension/shared/spoof/ClientType;");
            method(classes, "shared/spoof/SpoofVideoStreamsPatch", "getPreferredClient", "Lapp/morphe/extension/shared/spoof/ClientType;");
            method(classes, "shared/spoof/js/JavaScriptEngineSupport", "supportsJavaScriptEngine", "Z");
            method(classes, "shared/spoof/js/JavaScriptEngineSupport", "setDeviceSupportsJsEngine", "V", "Z");
            method(classes, "shared/spoof/requests/StreamingDataRequest", "getLastSpoofedClientName", "Ljava/lang/String;");
            String request = "shared/spoof/requests/StreamingDataRequest";
            String proto = "shared/innertube/PlayerResponseOuterClass$";
            method(classes, request, "fetchRequest", "V", "Ljava/lang/String;", "Ljava/util/Map;");
            method(classes, request, "getRequestForVideoId", "Lapp/morphe/extension/"+request+";", "Ljava/lang/String;");
            instanceMethod(classes, request, "getStream", "Lapp/morphe/extension/"+request+"$StreamData;");
            instanceMethod(classes, request+"$StreamData", "streamingData", "[B");
            instanceMethod(classes, request+"$StreamData", "client", "Lapp/morphe/extension/shared/spoof/ClientType;");
            method(classes, proto+"PlayerResponse", "parseFrom", "Lapp/morphe/extension/"+proto+"PlayerResponse;", "[B");
            instanceMethod(classes, proto+"PlayerResponse", "getStreamingData", "Lapp/morphe/extension/"+proto+"StreamingData;");
            instanceMethod(classes, proto+"PlayerResponse", "getVideoDetails", "Lapp/morphe/extension/"+proto+"VideoDetails;");
            instanceMethod(classes, proto+"VideoDetails", "getVideoId", "Ljava/lang/String;");
            instanceMethod(classes, proto+"StreamingData", "getAdaptiveFormatsList", "Ljava/util/List;");
            instanceMethod(classes, proto+"Format", "getItag", "I");
            instanceMethod(classes, proto+"Format", "getBitrate", "I");
            instanceMethod(classes, proto+"Format", "getUrl", "Ljava/lang/String;");
            instanceMethod(classes, proto+"Format", "getMimeType", "Ljava/lang/String;");
            field(classes, "shared/spoof/ClientType", "userAgent", 1);
            for (String field : List.of("TV_SABR", "ANDROID_CREATOR", "TV_SIMPLY", "VISIONOS_1_02"))
                field(classes, "shared/spoof/ClientType", field);
            field(classes, "youtube/settings/Settings", "SPOOF_VIDEO_STREAMS");
            field(classes, "youtube/settings/Settings", "SPOOF_VIDEO_STREAMS_CLIENT_TYPE");
        }
        System.out.println("Recovery API contract: PASS");
    }
    static void method(Map<String, ClassDef> classes, String cls, String name, String returns, String... params) {
        publicMethod(classes, cls, name, returns, 9, params);
    }
    static void instanceMethod(Map<String, ClassDef> classes, String cls, String name, String returns, String... params) {
        publicMethod(classes, cls, name, returns, 1, params);
    }
    static void publicMethod(Map<String, ClassDef> classes, String cls, String name, String returns, int flags, String... params) {
        ClassDef type = classes.get("Lapp/morphe/extension/" + cls + ";");
        if (type != null) for (Method m : type.getMethods())
            if (m.getName().equals(name) && m.getReturnType().equals(returns)
                    && m.getParameterTypes().toString().equals(Arrays.asList(params).toString())
                    && (m.getAccessFlags() & 9) == flags) return;
        throw new IllegalStateException("Upstream API changed: " + cls + "/" + name);
    }
    static void field(Map<String, ClassDef> classes, String cls, String name) {
        field(classes, cls, name, 9);
    }
    static void field(Map<String, ClassDef> classes, String cls, String name, int flags) {
        ClassDef type = classes.get("Lapp/morphe/extension/" + cls + ";");
        while (type != null) {
            for (Field f : type.getFields()) if (f.getName().equals(name) && (f.getAccessFlags() & 9) == flags) return;
            type = classes.get(type.getSuperclass());
        }
        throw new IllegalStateException("Upstream field changed: " + cls + "/" + name);
    }
}
