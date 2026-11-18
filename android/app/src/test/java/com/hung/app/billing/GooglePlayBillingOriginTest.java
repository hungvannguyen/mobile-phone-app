package com.hung.app.billing;

import static org.junit.Assert.*;
import org.junit.Test;

public class GooglePlayBillingOriginTest {
    private static final String PROD = "https://invidim.com";
    private static final String DEV = "https://billing-test.example";

    @Test public void acceptsOnlyCanonicalHttpsOrigins() {
        assertTrue(GooglePlayBillingOrigin.isCanonical(PROD));
        assertTrue(GooglePlayBillingOrigin.isCanonical(DEV));
        for (String value : new String[] {null, "", "http://invidim.com", "HTTPS://invidim.com",
                "https://INVIDIM.com", "https://*.invidim.com", "https://user@invidim.com",
                "https://invidim.com:443", "https://invidim.com:8443", "https://invidim.com/",
                "https://invidim.com/path", "https://invidim.com?x=1", "https://invidim.com#x",
                " https://invidim.com", "https://invidim.com.", "https://bad_host.example"}) {
            assertFalse(String.valueOf(value), GooglePlayBillingOrigin.isCanonical(value));
        }
    }

    @Test public void productionIsLiteralAndWorksWithoutDebugging() {
        assertTrue(allowed("production", PROD, PROD, PROD + "/login", false, true));
        assertTrue(allowed("production", PROD, PROD, PROD, true, true));
        assertFalse(allowed("production", DEV, DEV, DEV, false, true));
        assertFalse(allowed("production", DEV, DEV, DEV, true, true));
    }

    @Test public void developmentRequiresDebuggableAndExactConfiguration() {
        assertTrue(allowed("development", DEV, DEV, DEV + "/login?next=home", true, true));
        assertFalse(allowed("development", DEV, DEV, DEV, false, true));
        assertFalse(allowed("development", DEV, PROD, DEV, true, true));
        assertFalse(allowed("development", DEV, DEV, PROD, true, true));
        assertFalse(allowed("development", DEV, DEV, DEV + ".evil.example", true, true));
        assertFalse(allowed("development", DEV, DEV, DEV + ":8443/", true, true));
        assertFalse(allowed("development", "https://null", "https://null", "https:/missing", true, true));
    }

    @Test public void missingInvalidProfilesAndResourcesDisableBilling() {
        for (String profile : new String[] {null, "", "other", "Production"})
            assertFalse(allowed(profile, PROD, PROD, PROD, true, true));
        for (String value : new String[] {null, "", "http://invidim.com", PROD + "/"}) {
            assertFalse(allowed("production", value, PROD, PROD, true, true));
            assertFalse(allowed("production", PROD, value, PROD, true, true));
        }
        assertFalse(allowed("production", PROD, PROD, null, true, true));
        assertFalse(allowed("production", PROD, PROD, "https://user@invidim.com/", true, true));
    }

    @Test public void unsupportedMessagingCannotEnableBilling() {
        assertFalse(allowed("production", PROD, PROD, PROD, false, false));
        assertFalse(allowed("development", DEV, DEV, DEV, true, false));
    }

    private boolean allowed(String profile, String origin, String server, String app, boolean debug, boolean secure) {
        return GooglePlayBillingOrigin.mayInstall(profile, origin, server, app, debug, secure);
    }
}
