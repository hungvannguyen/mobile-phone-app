package com.hung.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.getcapacitor.CapConfig;
import com.hung.app.billing.GooglePlayBillingOrigin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Configuration tests only: no Activity launch, network request or BillingClient. */
@RunWith(AndroidJUnit4.class)
public class GooglePlayStartupConfigTest {
    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test public void generatedAssetAndResourcesRoundTripThroughCapacitor() throws Exception {
        String raw;
        try (InputStream input = context().getAssets().open("invidim-runtime/capacitor.config.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            raw = new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
        String url = MainActivity.validateStartupJson(raw);
        CapConfig selected = CapConfig.loadFromAssets(context(), "invidim-runtime");
        assertEquals(url, selected.getServerUrl());
        assertEquals(url, context().getString(R.string.invidim_google_play_trusted_origin));
        boolean debug = (context().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        assertTrue(GooglePlayBillingOrigin.mayInstall(
                context().getString(R.string.invidim_google_play_origin_profile), url,
                selected.getServerUrl(), url + "/login", debug, true));
        JSONObject config = new JSONObject(raw);
        assertEquals("com.invidim.app", config.getString("appId"));
        for (String platform : new String[] {"android", "ios"}) {
            JSONObject settings = config.optJSONObject(platform);
            assertTrue(settings == null || !settings.has("buildOptions"));
        }
    }

    @Test public void malformedOrMissingStartupConfigurationFailsWithFixedClassification() {
        for (String raw : new String[] {null, "", "{", "[]", "{}", "{\"server\":{}}",
                "{\"server\":{\"url\":null}}", "{\"server\":{\"url\":42}}",
                "{\"server\":{\"url\":\"http://invalid.example\"}}"}) {
            IllegalStateException error = assertThrows(IllegalStateException.class,
                    () -> MainActivity.validateStartupJson(raw));
            assertEquals("APP_STARTUP_CONFIG_INVALID", error.getMessage());
            assertNull(error.getCause());
        }
    }

    @Test public void validStartupConfigurationIsIndependentOfBillingMismatch() {
        String url = MainActivity.validateStartupJson("{\"server\":{\"url\":\"https://other.example\"}}");
        assertEquals("https://other.example", url);
        assertFalse(GooglePlayBillingOrigin.mayInstall("production", "https://invidim.com",
                url, url, false, true));
    }

    @Test public void unsupportedMessagingDoesNotInvalidateStartupConfiguration() {
        String url = MainActivity.validateStartupJson("{\"server\":{\"url\":\"https://invidim.com\"}}");
        assertFalse(GooglePlayBillingOrigin.mayInstall("production", url, url, url, false, false));
        assertEquals("https://invidim.com", url);
    }
}
