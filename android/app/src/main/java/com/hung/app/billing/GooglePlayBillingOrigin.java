package com.hung.app.billing;

import java.net.URI;
import java.net.URISyntaxException;

/** Build-selected trust policy; navigation rules and JavaScript never supply trust. */
public final class GooglePlayBillingOrigin {
    private static final String PRODUCTION = "https://invidim.com";

    private GooglePlayBillingOrigin() {}

    public static boolean isCanonical(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            String host = uri.getHost();
            return host != null && host.length() <= 253
                    && host.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)*")
                    && value.equals("https://" + host);
        } catch (URISyntaxException ignored) {
            return false;
        }
    }

    public static boolean mayInstall(String profile, String origin, String serverUrl, String appUrl,
            boolean debuggable, boolean secureMessaging) {
        if (!secureMessaging || !isCanonical(origin) || !origin.equals(serverUrl)) return false;
        if ("production".equals(profile)) {
            if (!PRODUCTION.equals(origin)) return false;
        } else if (!"development".equals(profile) || !debuggable) {
            return false;
        }
        try {
            if (appUrl == null) return false;
            URI uri = new URI(appUrl);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && origin.equals("https://" + uri.getHost());
        } catch (URISyntaxException ignored) {
            return false;
        }
    }
}
