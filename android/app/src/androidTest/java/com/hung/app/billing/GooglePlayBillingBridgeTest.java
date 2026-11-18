package com.hung.app.billing;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.webkit.WebViewRenderProcess;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Real WebView transport tests. The gateway never connects to Google Play. */
@RunWith(AndroidJUnit4.class)
public class GooglePlayBillingBridgeTest {
    private static final String DIAG_TAG = "InvidimBillingDiag";
    private static final AtomicLong DIAG_SEQUENCE = new AtomicLong();
    // A timed-out UI task cannot be safely interrupted. No later test may mutate UI in this process.
    private static final AtomicBoolean PROCESS_UNSAFE = new AtomicBoolean();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CountDownLatch diagnosticFinished = new CountDownLatch(1);
    private final AtomicReference<String> lastStarted = new AtomicReference<>("none");
    private final AtomicReference<String> lastCompleted = new AtomicReference<>("none");
    private Thread instrumentationThread;
    private static final String ORIGIN = "https://tappable-nondichogamous-spencer.ngrok-free.dev";
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final FakeGateway gateway = new FakeGateway();
    private WebView webView;
    private GooglePlayBillingPlugin.Transport transport;
    private CountDownLatch loaded;
    private final CountDownLatch rendererLost = new CountDownLatch(1);
    private volatile String childHtml = "";

    @Before public void setUp() {
        assertFalse("Previous diagnostic timeout requires a new test process", PROCESS_UNSAFE.get());
        instrumentationThread = Thread.currentThread();
        Thread watchdog = new Thread(() -> {
            try {
                if (!diagnosticFinished.await(30, TimeUnit.SECONDS)) abortDiagnostic("WATCHDOG_TIMEOUT");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "billing-diagnostic-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        String setup = begin("setUp");
        String probe = begin("featureProbe");
        boolean supported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        complete(probe);
        assumeTrue(supported);
        ui(() -> {
            String creation = begin("newWebView");
            webView = new WebView(InstrumentationRegistry.getInstrumentation().getTargetContext());
            complete(creation);
            webView.getSettings().setJavaScriptEnabled(true);
            WebViewCompat.addWebMessageListener(webView, "TestResult", Collections.singleton("*"),
                    (view, message, source, main, reply) -> {
                        String data = message.getData();
                        checkpoint("TEST_RESULT", "received=true source=" + safeFixtureUrl(source.toString())
                                + " main=" + main + " chars=" + (data == null ? -1 : data.length()));
                        boolean offered = messages.offer(data);
                        checkpoint("TEST_QUEUE", "offered=" + offered + " size=" + messages.size());
                    });
            transport = new GooglePlayBillingPlugin.Transport(webView, ORIGIN, gateway);
            webView.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    // Serve iframe documents locally: these tests never contact a network origin.
                    return new WebResourceResponse("text/html", "UTF-8",
                            new ByteArrayInputStream(childHtml.getBytes(StandardCharsets.UTF_8)));
                }
                @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                    checkpoint("CALLBACK", "onPageStarted url=" + safeFixtureUrl(url));
                    transport.navigate();
                }
                @Override public void onPageFinished(WebView view, String url) {
                    checkpoint("CALLBACK", "onPageFinished url=" + safeFixtureUrl(url));
                    if (loaded != null) loaded.countDown();
                }
                @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    transport.rendererGone();
                    view.destroy();
                    rendererLost.countDown();
                    return true;
                }
            });
        });
        complete(setup);
    }

    @After public void tearDown() {
        checkpoint("START", "tearDown");
        try {
            assertFalse("Unsafe context: teardown cannot cancel an already-running UI task", PROCESS_UNSAFE.get());
            if (webView != null) ui(() -> {
                if (transport != null) transport.destroy();
                webView.destroy();
            });
            checkpoint("COMPLETE", "tearDown");
        } finally { diagnosticFinished.countDown(); }
    }

    @Test public void trustedMainFrameGetsChallengeAndPurchaseRemainsDisabled() throws Exception {
        install();
        load(ORIGIN, "");
        sendHello();
        JSONObject response = new JSONObject(next());
        assertEquals("challenge", response.getString("type"));
        assertFalse(response.getJSONObject("payload").getBoolean("purchaseLaunchEnabled"));
        assertFalse(response.getJSONObject("payload").getBoolean("tokenDeliveryEnabled"));
        assertEquals(0, gateway.connects);
    }

    @Test public void unsupportedTransportFailsClosed() throws Exception {
        ui(() -> assertFalse(transport.install(false)));
        load(ORIGIN, "");
        assertEquals("\"undefined\"", evaluate("typeof InvidimPlayBillingNative"));
        assertEquals(0, gateway.connects);
    }

    @Test public void wrongSchemeHostAndPortNeverReceiveNativeObject() throws Exception {
        install();
        for (String origin : new String[] {"http://tappable-nondichogamous-spencer.ngrok-free.dev",
                "https://untrusted.invalid", ORIGIN + ":444"}) {
            load(origin, "");
            assertEquals(origin, "\"undefined\"", evaluate("typeof InvidimPlayBillingNative"));
        }
        assertEquals(0, gateway.connects);
    }

    @Test public void sameOriginAndCrossOriginIframesCannotHandshake() throws Exception {
        install();
        String hello = JSONObject.quote(hello());
        String child = "<script>if(typeof InvidimPlayBillingNative!=='undefined'){"
                + "InvidimPlayBillingNative.onmessage=function(e){TestResult.postMessage('IFRAME_REPLY')};"
                + "InvidimPlayBillingNative.postMessage(" + hello + ");}"
                + "TestResult.postMessage('IFRAME_DONE');</script>";
        childHtml = child;
        load(ORIGIN, "<iframe srcdoc=" + attribute(child) + "></iframe>"
                + "<iframe src=\"https://untrusted.invalid/child\"></iframe>");
        assertEquals("IFRAME_DONE", next());
        assertEquals("IFRAME_DONE", next());
        sendHello();
        assertEquals("challenge", new JSONObject(next()).getString("type"));
        assertEquals(0, gateway.connects);
    }

    @Test public void navigationAndPauseInvalidateTheOldDocument() throws Exception {
        install();
        load(ORIGIN, "");
        sendHello();
        JSONObject first = new JSONObject(next());
        ui(() -> transport.pause());
        sendHello();
        assertNull(messages.poll(250, TimeUnit.MILLISECONDS));
        ui(() -> transport.resume());
        sendHello();
        JSONObject resumed = new JSONObject(next());
        assertNotEquals(first.getJSONObject("payload").getString("challenge"),
                resumed.getJSONObject("payload").getString("challenge"));
        load(ORIGIN, "");
        sendHello();
        assertNotEquals(resumed.getString("document_epoch"), new JSONObject(next()).getString("document_epoch"));
    }

    @Test public void rendererLossClosesGatewayAndRejectsFurtherMessages() throws Exception {
        install();
        load(ORIGIN, "");
        ui(() -> transport.rendererGone());
        sendHello();
        assertNull(messages.poll(250, TimeUnit.MILLISECONDS));
        assertTrue(gateway.closed);
    }

    @Test public void delayedQueryCannotReplyIntoReloadedDocument() throws Exception {
        install();
        load(ORIGIN, "");
        beginHeldProductQuery();
        load(ORIGIN, "");
        sendHello();
        assertEquals("challenge", new JSONObject(next()).getString("type"));
        ui(() -> gateway.productCallback.complete(Collections.emptyList(), null));
        assertNull(messages.poll(250, TimeUnit.MILLISECONDS));
    }

    @Test public void delayedQueryCannotReplyAfterPauseOrDestroy() throws Exception {
        install();
        load(ORIGIN, "");
        beginHeldProductQuery();
        ui(() -> transport.pause());
        ui(() -> gateway.productCallback.complete(Collections.emptyList(), null));
        assertNull(messages.poll(250, TimeUnit.MILLISECONDS));
        ui(() -> transport.destroy());
        assertTrue(gateway.closed);
        sendHello();
        assertNull(messages.poll(250, TimeUnit.MILLISECONDS));
    }

    @Test public void actualRendererTerminationCleansUpTransport() throws Exception {
        assumeTrue(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE));
        install();
        load(ORIGIN, "");
        AtomicReference<WebViewRenderProcess> process = new AtomicReference<>();
        ui(() -> process.set(WebViewCompat.getWebViewRenderProcess(webView)));
        org.junit.Assume.assumeNotNull(process.get());
        ui(() -> assertTrue(process.get().terminate()));
        assertTrue("Renderer cleanup callback did not arrive", rendererLost.await(10, TimeUnit.SECONDS));
        assertTrue(gateway.closed);
    }

    private void beginHeldProductQuery() throws Exception {
        sendHello();
        JSONObject challenge = new JSONObject(next());
        JSONObject selection = new JSONObject()
                .put("binding_intent_id", UUID.randomUUID().toString())
                .put("expires_at_ms", System.currentTimeMillis() + 60000)
                .put("obfuscated_account_id", "v1_synthetic")
                .put("catalog_key", "basic").put("catalog_version", "synthetic-v1")
                .put("package_name", "com.invidim.app").put("product_id", "product")
                .put("base_plan_id", "monthly").put("offer_id", JSONObject.NULL);
        send(new JSONObject().put("v", 1).put("type", "bindScope")
                .put("operation_id", UUID.randomUUID().toString())
                .put("document_epoch", challenge.getString("document_epoch"))
                .put("account_epoch", challenge.getLong("account_epoch"))
                .put("payload", new JSONObject().put("challenge", challenge.getJSONObject("payload").getString("challenge"))
                        .put("selection", selection)));
        JSONObject bound = new JSONObject(next());
        assertEquals("scopeBound", bound.getString("type"));
        send(new JSONObject().put("v", 1).put("type", "queryProduct")
                .put("operation_id", UUID.randomUUID().toString())
                .put("document_epoch", bound.getString("document_epoch"))
                .put("account_epoch", bound.getLong("account_epoch"))
                .put("payload", new JSONObject()));
        assertTrue("Product query did not reach fake gateway", gateway.productRequested.await(10, TimeUnit.SECONDS));
        assertNotNull(gateway.productCallback);
    }

    private void send(JSONObject envelope) throws Exception {
        evaluate("InvidimPlayBillingNative.postMessage(" + JSONObject.quote(envelope.toString()) + ")");
    }

    private void install() {
        String stage = begin("install");
        ui(() -> { assertTrue(transport.install()); transport.resume(); });
        complete(stage);
    }

    private void load(String origin, String body) throws Exception {
        String stage = begin("load");
        loaded = new CountDownLatch(1);
        ui(() -> webView.loadDataWithBaseURL(origin + "/", "<!doctype html><html><body>" + body
                + "</body></html>", "text/html", "UTF-8", origin + "/"));
        assertTrue("WebView did not load", loaded.await(10, TimeUnit.SECONDS));
        ui(() -> checkpoint("WEBVIEW_URL", "getUrl=" + safeFixtureUrl(webView.getUrl())
                + " getOriginalUrl=" + safeFixtureUrl(webView.getOriginalUrl())));
        complete(stage);
    }

    private void sendHello() throws Exception {
        String stage = begin("sendHello");
        String result = evaluate("(function(){var d={href:location.href,origin:location.origin,"
                + "native_type:typeof window.InvidimPlayBillingNative,test_result_type:typeof window.TestResult,"
                + "post_type:'unavailable',post_started:false,post_returned:false,error:'none'};"
                + "try{d.post_type=typeof window.InvidimPlayBillingNative.postMessage;"
                + "InvidimPlayBillingNative.onmessage=function(e){TestResult.postMessage(e.data)};"
                + "d.post_started=true;InvidimPlayBillingNative.postMessage(" + JSONObject.quote(hello()) + ");"
                + "d.post_returned=true;}catch(e){d.error=e&&e.name?String(e.name):'UnknownError';}return d;})()");
        describeHelloResult(result);
        complete(stage);
    }

    private static String hello() {
        return "{\"v\":1,\"type\":\"hello\",\"operation_id\":\"" + UUID.randomUUID() + "\"}";
    }

    private String evaluate(String script) throws Exception {
        String stage = begin("evaluateJavascript");
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        ui(() -> webView.evaluateJavascript(script, result -> {
            checkpoint("CALLBACK", "evaluateJavascript");
            value.set(result); done.countDown();
        }));
        assertTrue("JavaScript did not finish", done.await(10, TimeUnit.SECONDS));
        complete(stage);
        return value.get();
    }

    private String next() throws Exception {
        String stage = begin("next");
        checkpoint("TEST_QUEUE", "beforePoll size=" + messages.size());
        String result = messages.poll(10, TimeUnit.SECONDS);
        checkpoint("TEST_QUEUE", "afterPoll present=" + (result != null) + " size=" + messages.size());
        assertNotNull("No transport response", result);
        complete(stage);
        return result;
    }

    private static String attribute(String html) {
        return "\"" + html.replace("&", "&amp;").replace("\"", "&quot;") + "\"";
    }

    // Only known synthetic fixture URLs may be logged. Never print data URLs, queries or payloads.
    private static String safeFixtureUrl(String value) {
        if (value == null) return "<null>";
        for (String allowed : new String[] {ORIGIN, ORIGIN + "/", ORIGIN + ":444/",
                "http://tappable-nondichogamous-spencer.ngrok-free.dev/",
                "https://untrusted.invalid", "https://untrusted.invalid/", "about:blank", "null"}) {
            if (allowed.equals(value)) return value;
        }
        return value.startsWith("data:") ? "<data-url-redacted>" : "<unexpected-url-redacted>";
    }

    private static String diagnosticClass(String value) {
        for (String allowed : new String[] {"undefined", "object", "function", "string", "number",
                "boolean", "symbol", "bigint", "unavailable", "none", "Error", "TypeError",
                "ReferenceError", "SyntaxError", "SecurityError", "InvalidStateError", "UnknownError"}) {
            if (allowed.equals(value)) return value;
        }
        return "<unexpected-classification>";
    }

    private void describeHelloResult(String result) {
        // This result contains only diagnostics; the real native reply still must pass through TestResult.
        try {
            JSONObject d = new JSONObject(result);
            checkpoint("JS_HELLO_RESULT", "href=" + safeFixtureUrl(d.optString("href", null))
                    + " origin=" + safeFixtureUrl(d.optString("origin", null))
                    + " native_type=" + diagnosticClass(d.optString("native_type"))
                    + " test_result_type=" + diagnosticClass(d.optString("test_result_type"))
                    + " post_type=" + diagnosticClass(d.optString("post_type"))
                    + " post_started=" + d.optBoolean("post_started")
                    + " post_returned=" + d.optBoolean("post_returned")
                    + " error=" + diagnosticClass(d.optString("error")));
        } catch (org.json.JSONException | NullPointerException e) {
            checkpoint("JS_HELLO_RESULT", "classification=unparseable-or-null");
        }
    }

    private String begin(String name) {
        assertFalse("Diagnostic context is unsafe", PROCESS_UNSAFE.get());
        String stage = DIAG_SEQUENCE.incrementAndGet() + ":" + name;
        lastStarted.set(stage);
        checkpoint("START", stage);
        return stage;
    }

    private void complete(String stage) {
        assertFalse("Diagnostic context became unsafe", PROCESS_UNSAFE.get());
        lastCompleted.set(stage);
        checkpoint("COMPLETE", stage);
    }

    private void checkpoint(String phase, String stage) {
        Log.i(DIAG_TAG, "D" + DIAG_SEQUENCE.incrementAndGet() + " t=" + SystemClock.elapsedRealtime()
                + " thread=" + Thread.currentThread().getName() + " " + phase + " " + stage);
    }

    private void abortDiagnostic(String reason) {
        PROCESS_UNSAFE.set(true);
        checkpoint("DIAG_ABORT_REQUIRED", reason + " lastStarted=" + lastStarted.get()
                + " lastCompleted=" + lastCompleted.get());
        dumpThread("main", Looper.getMainLooper().getThread());
        dumpThread("instrumentation", instrumentationThread);
        checkpoint("ABORT_STACKS_CAPTURED", "stop test process; running UI task is not canceled");
    }

    private void dumpThread(String label, Thread thread) {
        if (thread == null) return;
        checkpoint("STACK", label + " state=" + thread.getState());
        for (StackTraceElement frame : thread.getStackTrace()) checkpoint("STACK", label + " at " + frame);
    }

    private void ui(Runnable runnable) {
        String stage = begin("ui.schedule");
        if (Looper.myLooper() == Looper.getMainLooper()) {
            checkpoint("RUNNING", stage);
            runnable.run();
            complete(stage);
            return;
        }
        Object gate = new Object();
        int[] state = {0}; // queued, running, completed, canceled-before-start
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable task = () -> {
            synchronized (gate) {
                if (state[0] != 0 || PROCESS_UNSAFE.get()) return;
                state[0] = 1;
            }
            checkpoint("RUNNING", stage);
            try { runnable.run(); }
            catch (Throwable error) { failure.set(error); }
            finally {
                synchronized (gate) { state[0] = 2; }
                checkpoint("UI_TASK_FINISHED", stage);
                done.countDown();
            }
        };
        assertTrue("Main Handler rejected diagnostic task", main.post(task));
        boolean finished;
        try { finished = done.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finished = false;
        }
        if (!finished) {
            String status;
            synchronized (gate) {
                PROCESS_UNSAFE.set(true);
                status = state[0] == 0 ? "NOT_STARTED" : state[0] == 1 ? "RUNNING_NOT_CANCELED" : "COMPLETED_AT_DEADLINE";
                if (state[0] == 0) state[0] = 3;
            }
            main.removeCallbacks(task);
            abortDiagnostic("UI_TIMEOUT " + stage + " " + status);
            throw new AssertionError("UI timeout: " + status + "; stop test process");
        }
        Throwable error = failure.get();
        if (error instanceof Error) throw (Error) error;
        if (error instanceof RuntimeException) throw (RuntimeException) error;
        if (error != null) throw new AssertionError(error);
        complete(stage);
    }

    private static final class FakeGateway implements GooglePlayBillingController.Gateway {
        volatile int connects;
        volatile boolean closed;
        GooglePlayBillingController.Callback<List<GooglePlayBillingController.Offer>> productCallback;
        final CountDownLatch productRequested = new CountDownLatch(1);
        @Override public void setObserver(GooglePlayBillingController.Callback<List<GooglePlayBillingController.Observation>> observer) {}
        @Override public void connect(GooglePlayBillingController.Callback<Void> callback) { connects++; callback.complete(null, null); }
        @Override public void queryProduct(GooglePlayBillingController.Selection selection,
                GooglePlayBillingController.Callback<List<GooglePlayBillingController.Offer>> callback) {
            productCallback = callback;
            productRequested.countDown();
        }
        @Override public void queryOwned(GooglePlayBillingController.Callback<List<GooglePlayBillingController.Observation>> callback) {
            callback.complete(Collections.emptyList(), null);
        }
        @Override public void reset() { /* Retain callback references to exercise stale completions. */ }
        @Override public void close() { closed = true; }
    }
}
