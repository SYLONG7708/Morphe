import com.sylong.bluem.update.UpdatePolicy;
import java.nio.file.*;
import java.util.*;

public final class PolicySelfTest {
    static int count;
    static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        count++; System.out.println("PASS " + label);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        byte[] cert = Files.readAllBytes(Path.of(args[1]));
        byte[] valid = Files.readAllBytes(root.resolve("valid.json"));
        byte[] sig = Files.readAllBytes(root.resolve("valid.json.sig"));
        UpdatePolicy.Release r = UpdatePolicy.verify(valid, sig, cert, 0, false);
        check(r.sequence >= 2026092402L && r.items.size() == 2, "signed production release accepted");
        check(r.items.get(0).pkg.equals(UpdatePolicy.MICROG), "MicroG precedes self replacement");
        check(r.items.get(1).compatible(33,new String[]{"arm64-v8a"}), "UIS7870 Android 13 arm64 compatible");
        check(r.items.get(1).compatible(36,new String[]{"x86_64"}), "clean emulator compatible");
        check(!r.items.get(1).compatible(27,new String[]{"arm64-v8a"}), "older Android excluded");
        check(r.recommendation.youtubeVersion.equals("21.16.256"), "recommended base can be lower than experimental base");
        check(r.items.get(1).code > 2026092404L, "stable transition still increases Android version code");
        UpdatePolicy.Recommendation official = UpdatePolicy.recommended(Files.readAllBytes(root.resolve("patches-list.json")),33);
        check(official.matches(r.recommendation), "actual official metadata and signed release agree");
        String metadata = new String(Files.readAllBytes(root.resolve("patches-list.json")),java.nio.charset.StandardCharsets.UTF_8);
        try { UpdatePolicy.recommended(metadata.replace("\"isExperimental\": false","\"isExperimental\": true").getBytes(java.nio.charset.StandardCharsets.UTF_8),33); throw new AssertionError("experimental only accepted"); }
        catch (SecurityException expected) { check(true,"experimental-only upstream cannot become recommended"); }
        try { UpdatePolicy.recommended(metadata.replace("\"isExperimental\": false","\"isExperimental\": \"false\"").getBytes(java.nio.charset.StandardCharsets.UTF_8),33); throw new AssertionError("string false accepted"); }
        catch (SecurityException expected) { check(true,"untyped stable flag excluded"); }
        try { UpdatePolicy.recommended(metadata.replace("\"version\": \"1.46.0\"","\"version\": \"1.47.0-dev.1\"").getBytes(java.nio.charset.StandardCharsets.UTF_8),33); throw new AssertionError("dev accepted"); }
        catch (SecurityException expected) { check(true,"upstream prerelease excluded"); }
        check(!r.items.get(1).compatible(36,new String[]{"riscv64"}), "unsupported ABI excluded");
        try { UpdatePolicy.verify(valid,sig,cert,r.sequence+1,false); throw new AssertionError("rollback accepted"); }
        catch (SecurityException expected) { check(true,"previous channel sequence rejected"); }
        for (String name : Arrays.asList("wrong-signer","wrong-package","duplicate","cleartext","cross-repository","zero-size",
                "oversize","invalid-hash","other-channel","zero-sequence","invalid-code","tampered",
                "experimental","prerelease","untyped-experimental","dev-patches","wrong-base","missing-policy")) {
            try {
                UpdatePolicy.verify(Files.readAllBytes(root.resolve(name+".json")),Files.readAllBytes(root.resolve(name+".json.sig")),cert,0,false);
                throw new AssertionError(name + " accepted");
            } catch (SecurityException | org.json.JSONException expected) { check(true,name+" rejected"); }
        }
        for (String url : Arrays.asList("http://127.0.0.1:8873/test", "https://github.com@evil.test/x", "https://github.com:444/x",
                "file:///data/x", "https://github.com.evil.test/x", "https://github.com/x#secret")) {
            try { UpdatePolicy.allowedUrl(url,false); throw new AssertionError("unsafe URL accepted"); }
            catch (SecurityException expected) { check(true,"unsafe production URL rejected"); }
        }
        check(UpdatePolicy.allowedUrl("http://127.0.0.1:8873/test",true).getHost().equals("127.0.0.1"), "QA loopback explicit only");
        try { UpdatePolicy.readLimited(new java.io.ByteArrayInputStream(new byte[1025]),1024); throw new AssertionError("oversized body"); }
        catch (java.io.IOException expected) { check(true,"bounded network body"); }
        System.out.println("TOTAL " + count + " PASSED");
    }
}
