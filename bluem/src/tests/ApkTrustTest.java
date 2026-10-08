import com.sylong.bluem.update.ApkTrust;
import java.nio.file.*;
import java.io.*;

public class ApkTrustTest {
    public static void main(String[] args) throws Exception {
        File original = new File(args[0]);
        String expected="0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7";
        if (!ApkTrust.signedBy(original,expected)) throw new AssertionError("Official APK rejected");
        if (ApkTrust.signedBy(original,"0000000000000000000000000000000000000000000000000000000000000000")) throw new AssertionError("Wrong signer accepted");
        Path corrupt=Path.of(args[1]);
        Files.copy(original.toPath(),corrupt,StandardCopyOption.REPLACE_EXISTING);
        try (RandomAccessFile f=new RandomAccessFile(corrupt.toFile(),"rw")) {
            f.seek(12345);int b=f.read();f.seek(12345);f.write(b^1);
        }
        boolean accepted=false;
        try { accepted=ApkTrust.signedBy(corrupt.toFile(),expected); } catch (Exception rejected) {}
        if (accepted) throw new AssertionError("Tampered signed APK accepted");
        System.out.println("PASS 3: real official APK, wrong signer, tampered signing block coverage");
    }
}
