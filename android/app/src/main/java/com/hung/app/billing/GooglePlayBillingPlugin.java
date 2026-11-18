package com.hung.app.billing;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.content.pm.ApplicationInfo;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.getcapacitor.Plugin;
import com.getcapacitor.WebViewListener;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.hung.app.R;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Collections;

/** Secure, origin-bound transport. Deliberately exposes no Capacitor plugin methods. */
@CapacitorPlugin(name = "InvidimPlayBilling")
public final class GooglePlayBillingPlugin extends Plugin {
    private Transport transport;
    private WebViewListener listener;

    @Override public void load() {
        final String origin;
        try {
            origin = getContext().getString(R.string.invidim_google_play_trusted_origin);
            String profile = getContext().getString(R.string.invidim_google_play_origin_profile);
            boolean debug = (getContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (!GooglePlayBillingOrigin.mayInstall(profile, origin, bridge.getConfig().getServerUrl(),
                    bridge.getAppUrl(), debug, true)) return;
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        } catch (RuntimeException ignored) {
            return;
        }
        Transport candidate = new Transport(bridge.getWebView(), origin, new AndroidBillingGateway(getContext()));
        if (!candidate.install()) return;
        transport = candidate;
        listener = new WebViewListener() {
            @Override public void onPageStarted(WebView view) { transport.navigate(); }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                transport.rendererGone();
                view.destroy();
                getActivity().finish();
                return true;
            }
        };
        bridge.addWebViewListener(listener);
    }

    @Override protected void handleOnResume() { if (transport != null) transport.resume(); }
    @Override protected void handleOnPause() { if (transport != null) transport.pause(); }
    @Override protected void handleOnDestroy() {
        if (transport != null) transport.destroy();
        if (listener != null) bridge.removeWebViewListener(listener);
    }

    static final class Transport {
        private static final String OBJECT_NAME = "InvidimPlayBillingNative";
        private final WebView webView;
        private final String trustedOrigin;
        private final URI expected;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final GooglePlayBillingController controller;
        private boolean installed;
        private boolean active;
        private boolean destroyed;
        private long generation;
        private ReplyTarget target;

        Transport(WebView webView, String trustedOrigin, GooglePlayBillingController.Gateway gateway) {
            this.webView = webView;
            this.trustedOrigin = trustedOrigin;
            this.expected = parse(trustedOrigin);
            SecureRandom random = new SecureRandom();
            controller = new GooglePlayBillingController(gateway, new GooglePlayBillingController.Clock() {
                @Override public long monotonicMs() { return SystemClock.elapsedRealtime(); }
                @Override public long wallMs() { return System.currentTimeMillis(); }
            }, (delayMs, task) -> main.postDelayed(task, delayMs), this::serial, () -> {
                byte[] bytes = new byte[16];
                random.nextBytes(bytes);
                char[] hex = "0123456789abcdef".toCharArray();
                char[] output = new char[32];
                for (int i = 0; i < bytes.length; i++) {
                    output[i * 2] = hex[(bytes[i] & 255) >>> 4];
                    output[i * 2 + 1] = hex[bytes[i] & 15];
                }
                return new String(output);
            });
        }

        boolean install() { return install(true); }

        // The argument can only deny installation, never override actual feature support.
        boolean install(boolean featureAllowed) {
            if (Looper.myLooper() != Looper.getMainLooper()) return false;
            if (destroyed || !featureAllowed || expected == null) {
                destroy();
                return false;
            }
            if (installed) return true;
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(webView, OBJECT_NAME, Collections.singleton(trustedOrigin),
                        (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                            long receivedGeneration = generation;
                            if (!isMainFrame || !matches(sourceOrigin.toString())) return;
                            final String json;
                            try { json = message.getData(); } catch (RuntimeException ignored) { return; }
                            serial(() -> {
                                if (!usable(receivedGeneration)) return;
                                if (target == null) target = new ReplyTarget(replyProxy, receivedGeneration);
                                controller.receive(json, target);
                            });
                        });
                } else {
                    destroy();
                    return false;
                }
                installed = true;
                return true;
            } catch (RuntimeException ignored) {
                destroy();
                return false;
            }
        }

        void resume() { serial(() -> {
            if (!destroyed && installed) { active = true; controller.resume(); }
        }); }

        void pause() { serial(() -> {
            active = false;
            invalidate();
            controller.pause();
        }); }

        void navigate() { serial(() -> { invalidate(); controller.navigate(); }); }

        void rendererGone() { serial(() -> {
            active = false;
            invalidate();
            controller.rendererGone();
            remove();
            destroyed = true;
            main.removeCallbacksAndMessages(null);
        }); }

        void destroy() { serial(() -> {
            if (destroyed) return;
            active = false;
            invalidate();
            controller.destroy();
            remove();
            destroyed = true;
            main.removeCallbacksAndMessages(null);
        }); }

        private void remove() {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                try { WebViewCompat.removeWebMessageListener(webView, OBJECT_NAME); }
                catch (RuntimeException ignored) { /* A dead renderer cannot deliver a reply. */ }
            }
            installed = false;
        }

        private void invalidate() { generation++; target = null; }

        private boolean usable(long capturedGeneration) {
            return installed && active && !destroyed && generation == capturedGeneration
                    && matches(webView.getUrl());
        }

        private void serial(Runnable task) {
            if (Looper.myLooper() == Looper.getMainLooper()) task.run();
            else main.post(task);
        }

        private boolean matches(String url) {
            URI actual = parse(url);
            return expected != null && actual != null
                    && expected.getHost().equalsIgnoreCase(actual.getHost())
                    && port(expected) == port(actual);
        }

        private static URI parse(String value) {
            if (value == null) return null;
            try {
                URI uri = new URI(value);
                return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                        && uri.getRawUserInfo() == null && port(uri) == 443 ? uri : null;
            } catch (URISyntaxException ignored) { return null; }
        }

        private static int port(URI uri) { return uri.getPort() == -1 ? 443 : uri.getPort(); }

        private final class ReplyTarget implements GooglePlayBillingController.Reply {
            private final JavaScriptReplyProxy proxy;
            private final long documentGeneration;
            ReplyTarget(JavaScriptReplyProxy proxy, long documentGeneration) {
                this.proxy = proxy;
                this.documentGeneration = documentGeneration;
            }
            @Override public void send(String json) {
                ReplyTarget capturedTarget = this;
                serial(() -> {
                    if (target != capturedTarget || !usable(documentGeneration)) return;
                    try {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                            proxy.postMessage(json);
                        } else {
                            pause();
                        }
                    }
                    catch (RuntimeException ignored) { pause(); }
                });
            }
        }
    }
}
