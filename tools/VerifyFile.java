import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.util.Base64;

/** CI/local counterpart of the Android pinned-certificate verifier. */
public final class VerifyFile {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: VerifyFile <certificate.der> <input> <signature>");
        }
        var certificate = CertificateFactory.getInstance("X.509").generateCertificate(
            new ByteArrayInputStream(Files.readAllBytes(Path.of(args[0])))
        );
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(certificate.getPublicKey());
        try (var input = Files.newInputStream(Path.of(args[1]))) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                verifier.update(buffer, 0, read);
            }
        }
        byte[] detached = Base64.getMimeDecoder().decode(
            Files.readString(Path.of(args[2]), StandardCharsets.US_ASCII).trim()
        );
        if (!verifier.verify(detached)) {
            throw new SecurityException("Detached signature verification failed: " + args[1]);
        }
        System.out.println("Verified: " + args[1]);
    }
}
