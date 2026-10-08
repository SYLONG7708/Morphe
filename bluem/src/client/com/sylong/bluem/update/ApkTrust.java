package com.sylong.bluem.update;

import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;

/** Verify the actual APK signing blocks, independent of OEM archive metadata. */
public final class ApkTrust {
    public static boolean signedBy(File apk, String expected) throws Exception {
        ApkVerifier.Result verified = new ApkVerifier.Builder(apk).build().verify();
        if (!verified.isVerified()) return false;
        List<X509Certificate> certificates = verified.getSignerCertificates();
        return certificates.size() == 1 && expected.equals(UpdatePolicy.hex(
            MessageDigest.getInstance("SHA-256").digest(certificates.get(0).getEncoded())));
    }
    private ApkTrust() {}
}
