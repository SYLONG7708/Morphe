package com.sylong.bluem.update;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import javax.net.ssl.SSLContext;

/** Resolve the TLS backend without recursively selecting YouTube's waiting guard. */
public final class TlsCompatibility {
    private TlsCompatibility() {}

    static boolean isGuard(Provider provider) {
        return provider.getName().replace("_", "").toLowerCase(java.util.Locale.ROOT).contains("sslguard");
    }

    /** Called only from the guard's existing backend factories, after its initialization callback. */
    public static SSLContext context(String protocol) throws NoSuchAlgorithmException {
        SSLContext selected = SSLContext.getInstance(protocol);
        if (!isGuard(selected.getProvider())) return selected;
        return platformContext(protocol);
    }

    /** A new context for an updater connection; leaves global provider order and defaults untouched. */
    public static SSLContext platformContext(String protocol) throws NoSuchAlgorithmException {
        NoSuchAlgorithmException last = null;
        for (String name : new String[]{"AndroidOpenSSL", "Conscrypt", "SunJSSE"}) {
            Provider provider = Security.getProvider(name);
            if (provider == null || isGuard(provider)) continue;
            try { return SSLContext.getInstance(protocol, provider); }
            catch (NoSuchAlgorithmException absent) { last = absent; }
        }
        // Some OEMs register the platform implementation under another name.
        for (Provider provider : Security.getProviders()) {
            if (isGuard(provider) || provider.getService("SSLContext", protocol) == null) continue;
            try { return SSLContext.getInstance(protocol, provider); }
            catch (NoSuchAlgorithmException unavailable) { last = unavailable; }
        }
        throw new NoSuchAlgorithmException("No non-guard TLS provider for " + protocol, last);
    }
}
