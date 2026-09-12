package me.ksyz.accountmanager.utils;

import javax.net.ssl.SSLContext;

/*
 * This file is derived from https://github.com/ksyzov/AccountManager.
 * Originally licensed under the GNU LGPL.
 *
 * This modified version is licensed under the GNU GPL v3.
 *
 * NOTE (RottenApple): the previous version of this class loaded a bundled
 * custom truststore (/ssl.jks) and installed it globally via
 * HttpsURLConnection.setDefaultSSLSocketFactory(). That is intentionally
 * gone. This class now returns the JVM's default SSLContext (system CA
 * store) so auth traffic to Microsoft/Xbox/Minecraft is validated against
 * the platform trust anchors instead of a binary blob in resources.
 * Kept only for API compatibility with existing callers.
 */
public class SSLUtils {
    public static SSLContext getSSLContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception e) {
            throw new RuntimeException("Failed to get default SSLContext", e);
        }
    }
}
