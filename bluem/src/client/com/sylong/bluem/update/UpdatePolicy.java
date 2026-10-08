package com.sylong.bluem.update;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.util.*;

/** Signed release policy. No Android UI or installer dependencies. */
public final class UpdatePolicy {
    public static final String YOUTUBE = "app.morphe.android.youtube";
    public static final String MICROG = "app.revanced.android.gms";
    public static final String YOUTUBE_CERT = "7cee829c140e3ba32767e541e98d99a87214b80c7b3095692549131a2d6ebf02";
    public static final String MICROG_CERT = "0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7";
    public static final String CHANNEL = "blue-m-stable";
    public static final int MAX_MANIFEST = 131072;
    public static final int MAX_UPSTREAM = 2 * 1024 * 1024;
    public static final String POLICY = "morphe-recommended-stable";

    public static final class Recommendation {
        public final String patchesVersion, youtubeVersion;
        public Recommendation(String patches, String youtube) {
            require(stableVersion(patches) && stableVersion(youtube), "Stable versions required");
            patchesVersion = patches; youtubeVersion = youtube;
        }
        public boolean matches(Recommendation other) {
            return patchesVersion.equals(other.patchesVersion) && youtubeVersion.equals(other.youtubeVersion);
        }
    }
    private static boolean stableVersion(String value) {
        return value != null && value.matches("[0-9]+(?:\\.[0-9]+){1,3}");
    }
    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            long l = i < x.length ? Long.parseLong(x[i]) : 0;
            long r = i < y.length ? Long.parseLong(y[i]) : 0;
            if (l != r) return Long.compare(l, r);
        }
        return 0;
    }
    public static Recommendation recommended(byte[] bytes, int sdk) throws Exception {
        require(bytes.length > 0 && bytes.length <= MAX_UPSTREAM, "Upstream metadata size");
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        String patchesVersion = root.getString("version");
        require(stableVersion(patchesVersion), "Pre-release patches excluded");
        Map<String, Integer> counts = new HashMap<>();
        JSONArray patches = root.getJSONArray("patches");
        for (int i = 0; i < patches.length(); i++) {
            JSONObject patch = patches.getJSONObject(i);
            if (!Boolean.TRUE.equals(patch.opt("default"))) continue;
            JSONArray packages = patch.optJSONArray("compatiblePackages");
            if (packages == null) continue;
            for (int j = 0; j < packages.length(); j++) {
                JSONObject pkg = packages.getJSONObject(j);
                if (!"com.google.android.youtube".equals(pkg.optString("packageName"))) continue;
                JSONArray targets = pkg.optJSONArray("targets");
                if (targets == null) continue;
                Set<String> counted = new HashSet<>();
                for (int k = 0; k < targets.length(); k++) {
                    JSONObject target = targets.getJSONObject(k);
                    if (!Boolean.FALSE.equals(target.opt("isExperimental"))) continue;
                    String v = target.optString("version");
                    if (!stableVersion(v) || target.optInt("minSdk", Integer.MAX_VALUE) > sdk) continue;
                    if (counted.add(v)) counts.put(v, counts.getOrDefault(v, 0) + 1);
                }
            }
        }
        String best = null; int count = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > count || (entry.getValue() == count && (best == null || compareVersions(entry.getKey(), best) > 0))) {
                best = entry.getKey(); count = entry.getValue();
            }
        }
        require(best != null, "No stable recommended YouTube target");
        return new Recommendation(patchesVersion, best);
    }

    public static final class Item {
        public final String pkg, versionName, url, sha256, cert, label;
        public final long code, size;
        public final int minSdk;
        public final Set<String> abis = new HashSet<>();
        Item(JSONObject j, boolean qa) throws Exception {
            pkg = j.getString("package");
            cert = j.getString("signer_sha256");
            require((YOUTUBE.equals(pkg) && YOUTUBE_CERT.equals(cert)) ||
                (MICROG.equals(pkg) && MICROG_CERT.equals(cert)), "Untrusted package or signer");
            label = YOUTUBE.equals(pkg) ? "藍色 M" : "MicroG";
            code = j.getLong("version_code");
            require(code > 0 && code <= Integer.MAX_VALUE, "Bad version code");
            versionName = j.getString("version_name");
            require(versionName.length() > 0 && versionName.length() <= 100, "Bad version name");
            size = j.getLong("size");
            require(size > 0 && size <= 600L * 1024 * 1024, "Bad APK size");
            sha256 = j.getString("sha256");
            require(sha256.matches("[0-9a-f]{64}"), "Bad hash");
            minSdk = j.getInt("min_sdk");
            require(minSdk >= 21 && minSdk < 100, "Bad minSdk");
            JSONArray arr = j.getJSONArray("abis");
            for (int i = 0; i < arr.length(); i++) {
                String abi = arr.getString(i);
                require(Arrays.asList("arm64-v8a", "armeabi-v7a", "x86", "x86_64").contains(abi), "Bad ABI");
                abis.add(abi);
            }
            require(!abis.isEmpty(), "No supported ABI");
            url = j.getString("url");
            URL parsed = allowedUrl(url, qa);
            require(isQaUrl(parsed, qa) || (parsed.getHost().equals("github.com") &&
                parsed.getPath().startsWith("/SYLONG7708/Morphe/releases/download/")), "Unexpected APK origin");
        }
        public boolean compatible(int sdk, String[] deviceAbis) {
            if (sdk < minSdk) return false;
            for (String abi : deviceAbis) if (abis.contains(abi)) return true;
            return false;
        }
    }
    public static final class Release {
        public final long sequence;
        public final List<Item> items;
        public final Recommendation recommendation;
        Release(long sequence, List<Item> items, Recommendation recommendation) {
            this.sequence = sequence; this.items = items; this.recommendation = recommendation;
        }
    }
    public static Release verify(byte[] bytes, byte[] signature, byte[] certificate, long minimumSequence, boolean qa) throws Exception {
        require(bytes.length > 0 && bytes.length <= MAX_MANIFEST, "Manifest size");
        require(signature.length <= 8192, "Signature size");
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
        verifier.update(bytes);
        require(verifier.verify(Base64.getDecoder().decode(new String(signature, StandardCharsets.US_ASCII).trim())), "Invalid release signature");
        JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        require(root.getInt("schema") == 1 && CHANNEL.equals(root.getString("channel")), "Unknown channel");
        JSONObject upstream = root.getJSONObject("upstream");
        require(POLICY.equals(upstream.getString("policy")) &&
            "MorpheApp/morphe-patches".equals(upstream.getString("source")), "Recommended stable policy required");
        require(Boolean.FALSE.equals(upstream.opt("prerelease")) &&
            Boolean.FALSE.equals(upstream.opt("experimental")), "Experimental releases excluded");
        Recommendation recommendation = new Recommendation(upstream.getString("patches_version"), upstream.getString("youtube_version"));
        long seq = root.getLong("sequence");
        require(seq > 0 && seq >= minimumSequence, "Release rollback");
        JSONArray packages = root.getJSONArray("packages");
        require(packages.length() == 2, "Expected the YouTube and MicroG pair");
        Set<String> names = new HashSet<>();
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < packages.length(); i++) {
            Item item = new Item(packages.getJSONObject(i), qa);
            if (YOUTUBE.equals(item.pkg)) require(item.versionName.equals(recommendation.youtubeVersion), "Unexpected YouTube base");
            else require(stableVersion(item.versionName), "Pre-release MicroG excluded");
            require(names.add(item.pkg), "Duplicate package");
            items.add(item);
        }
        // Install the companion first, so replacing YouTube never loses a queued companion update.
        items.sort((a, b) -> MICROG.equals(a.pkg) ? -1 : 1);
        return new Release(seq, Collections.unmodifiableList(items), recommendation);
    }
    public static URL allowedUrl(String value, boolean qa) throws Exception {
        URI uri = new URI(value);
        require(uri.getUserInfo() == null && uri.getFragment() == null, "URL credentials or fragment");
        URL url = uri.toURL();
        if (isQaUrl(url, qa)) return url;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        require("https".equals(url.getProtocol()) && (url.getPort() == -1 || url.getPort() == 443), "HTTPS required");
        require(host.equals("github.com") || host.equals("raw.githubusercontent.com") ||
            host.equals("release-assets.githubusercontent.com") || host.equals("objects.githubusercontent.com"), "Unexpected update host");
        return url;
    }
    static boolean isQaUrl(URL url, boolean qa) {
        return qa && "http".equals(url.getProtocol()) && "127.0.0.1".equals(url.getHost()) && url.getPort() == 8873;
    }
    public static HttpURLConnection connect(String address, boolean qa) throws Exception {
        URL url = allowedUrl(address, qa);
        for (int redirects = 0; redirects < 6; redirects++) {
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(12000);
            c.setReadTimeout(20000);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "BlueM-StandaloneUpdater/1");
            c.setRequestProperty("Accept-Encoding", "identity");
            int status = c.getResponseCode();
            if (status >= 300 && status <= 399) {
                String next = c.getHeaderField("Location");
                c.disconnect();
                require(next != null, "Missing redirect");
                url = allowedUrl(new URL(url, next).toString(), qa);
                continue;
            }
            if (status != 200) { c.disconnect(); throw new IOException("HTTP " + status); }
            return c;
        }
        throw new IOException("Too many redirects");
    }
    public static byte[] fetch(String url, int maximum, boolean qa) throws Exception {
        HttpURLConnection c = connect(url, qa);
        try (InputStream in = c.getInputStream()) { return readLimited(in, maximum); }
        finally { c.disconnect(); }
    }
    public static byte[] readLimited(InputStream in, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) {
            if (out.size() + n > maximum) throw new IOException("Response too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    public static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[131072];
            for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    public static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02x", b & 255));
        return s.toString();
    }
    public static void require(boolean ok, String message) {
        if (!ok) throw new SecurityException(message);
    }
    private UpdatePolicy() {}
}
