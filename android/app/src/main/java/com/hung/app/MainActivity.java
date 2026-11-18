package com.hung.app;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.CapConfig;
import android.os.Bundle;
import com.hung.app.billing.GooglePlayBillingPlugin;
import com.hung.app.billing.GooglePlayBillingOrigin;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(GooglePlayBillingPlugin.class);
        super.onCreate(savedInstanceState);
    }

    @Override protected void load() {
        try {
            String expectedUrl;
            try (InputStream input = getAssets().open("invidim-runtime/capacitor.config.json")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                expectedUrl = validateStartupJson(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
            CapConfig selected = CapConfig.loadFromAssets(this, "invidim-runtime");
            if (!expectedUrl.equals(selected.getServerUrl())) throw new IllegalArgumentException();
            config = selected;
        } catch (Exception ignored) {
            throw new IllegalStateException("APP_STARTUP_CONFIG_INVALID");
        }
        super.load();
    }

    static String validateStartupJson(String raw) {
        try {
            Object value = new JSONObject(raw).getJSONObject("server").get("url");
            if (!(value instanceof String) || !GooglePlayBillingOrigin.isCanonical((String) value)) {
                throw new IllegalArgumentException();
            }
            return (String) value;
        } catch (Exception ignored) {
            throw new IllegalStateException("APP_STARTUP_CONFIG_INVALID");
        }
    }
}
