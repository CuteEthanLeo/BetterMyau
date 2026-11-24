package me.ksyz.accountmanager.utils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.security.KeyStore;

/*
 * This file is derived from https://github.com/ksyzov/AccountManager.
 * Originally licensed under the GNU LGPL.
 *
 * This modified version is licensed under the GNU GPL v3.
 */
public class SSLUtils {
    private static final SSLContext ctx;

    /**
     * SSL context backed by the bundled CA trust store (ssl.jks). It is only used by the
     * account manager's own HTTP clients; the JVM-wide default socket factory is left
     * untouched so other mods and vanilla services keep their own trust configuration.
     */
    public static SSLContext getSSLContext() {
        return ctx;
    }

    static {
        try (InputStream stream = SSLUtils.class.getResourceAsStream("/ssl.jks")) {
            if (stream == null) {
                throw new RuntimeException("Couldn't find ssl.jks in resources");
            }
            KeyStore jks = KeyStore.getInstance("JKS");
            jks.load(stream, "changeit".toCharArray());

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(jks);

            ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize custom SSLContext", e);
        }
    }
}
