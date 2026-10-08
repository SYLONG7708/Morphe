import java.nio.file.*;
import java.security.*;
import java.util.Base64;

/** Reads credentials only from the child environment; never command arguments or logs. */
public final class SignManifest {
    public static void main(String[] args) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        char[] password = System.getenv("UPDATE_SIGNING_STORE_PASSWORD").toCharArray();
        try (var in = Files.newInputStream(Path.of(System.getenv("UPDATE_SIGNING_P12_FILE")))) {
            store.load(in, password);
        } finally { java.util.Arrays.fill(password, '\0'); }
        char[] keyPassword = System.getenv("UPDATE_SIGNING_KEY_PASSWORD").toCharArray();
        PrivateKey key;
        try { key = (PrivateKey) store.getKey(System.getenv("UPDATE_SIGNING_KEY_ALIAS"), keyPassword); }
        finally { java.util.Arrays.fill(keyPassword, '\0'); }
        Signature s = Signature.getInstance("SHA256withRSA");
        if (args.length != 3) throw new IllegalArgumentException("Manifest, signature and pinned public certificate required");
        byte[] expected = Files.readAllBytes(Path.of(args[2]));
        if (!MessageDigest.isEqual(expected, store.getCertificate(System.getenv("UPDATE_SIGNING_KEY_ALIAS")).getEncoded()))
            throw new SecurityException("Manifest signing certificate differs from installed trust anchor");
        s.initSign(key);
        s.update(Files.readAllBytes(Path.of(args[0])));
        byte[] signature = s.sign();
        s.initVerify(store.getCertificate(System.getenv("UPDATE_SIGNING_KEY_ALIAS")));
        s.update(Files.readAllBytes(Path.of(args[0])));
        if (!s.verify(signature)) throw new SecurityException("Manifest signature did not verify");
        Files.writeString(Path.of(args[1]), Base64.getEncoder().encodeToString(signature) + "\n");
    }
}
