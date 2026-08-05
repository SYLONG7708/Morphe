import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.Base64;

/** Produces the Base64 detached SHA256withRSA format verified by SyMorphe. */
public final class SignFile {
    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            throw new IllegalArgumentException(
                "Usage: SignFile <p12> <storePass> <alias> <keyPass> <input> <output>"
            );
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream input = new FileInputStream(args[0])) {
            store.load(input, args[1].toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey(args[2], args[3].toCharArray());
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        try (var input = Files.newInputStream(Path.of(args[4]))) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                signer.update(buffer, 0, read);
            }
        }
        String encoded = Base64.getEncoder().encodeToString(signer.sign()) + "\n";
        Files.writeString(Path.of(args[5]), encoded, StandardCharsets.US_ASCII);
    }
}
