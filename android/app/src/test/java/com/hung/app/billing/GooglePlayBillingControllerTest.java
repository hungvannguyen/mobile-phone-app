package com.hung.app.billing;

import static org.junit.Assert.*;

import java.util.*;
import org.junit.Test;

public class GooglePlayBillingControllerTest {
    static final String ACCOUNT = "v1_synthetic_account";
    static final class Time implements GooglePlayBillingController.Clock, GooglePlayBillingController.Scheduler {
        long mono = 100, wall = 1000000;
        final List<Runnable> scheduled = new ArrayList<>();
        public long monotonicMs() { return mono; }
        public long wallMs() { return wall; }
        public void after(long delay, Runnable task) { scheduled.add(task); }
        void advance(long ms) { mono += ms; wall += ms; for (Runnable r : new ArrayList<>(scheduled)) r.run(); }
    }
    static final class Gateway implements GooglePlayBillingController.Gateway {
        int connects, products, owned, closes, resets;
        GooglePlayBillingController.Callback<Void> connecting;
        GooglePlayBillingController.Callback<List<GooglePlayBillingController.Offer>> product;
        GooglePlayBillingController.Callback<List<GooglePlayBillingController.Observation>> recovery, observer;
        public void setObserver(GooglePlayBillingController.Callback<List<GooglePlayBillingController.Observation>> o) { observer = o; }
        public void connect(GooglePlayBillingController.Callback<Void> c) {
            if(connecting!=null) { c.complete(null,"BUSY"); return; }
            connects++; connecting = (value,error) -> { connecting=null; c.complete(value,error); };
        }
        public void reset() { resets++; connecting=null; }
        public void queryProduct(GooglePlayBillingController.Selection s, GooglePlayBillingController.Callback<List<GooglePlayBillingController.Offer>> c) { products++; product = c; }
        public void queryOwned(GooglePlayBillingController.Callback<List<GooglePlayBillingController.Observation>> c) { owned++; recovery = c; }
        public void close() { closes++; }
    }
    static final class Peer implements GooglePlayBillingController.Reply {
        final List<Map<String, Object>> replies = new ArrayList<>();
        Runnable onQuota;
        public void send(String json) {
            replies.add(GooglePlayBillingController.Json.object(json));
            if(onQuota!=null && "DOCUMENT_QUOTA_EXHAUSTED".equals(payload().get("error"))) onQuota.run();
        }
        Map<String, Object> last() { return replies.get(replies.size() - 1); }
        @SuppressWarnings("unchecked") Map<String, Object> payload() { return (Map<String, Object>) last().get("payload"); }
    }
    static final class Fixture {
        final Time time = new Time(); final Gateway gateway = new Gateway(); final Peer peer = new Peer();
        int nonces;
        final GooglePlayBillingController controller;
        String document; long account; String challenge;
        Fixture() { this(Runnable::run); }
        Fixture(java.util.concurrent.Executor executor) {
            controller=new GooglePlayBillingController(gateway,time,time,executor,() -> String.format("%032x",++nonces));
            controller.resume();
        }
        String id() { return UUID.randomUUID().toString(); }
        void hello() {
            controller.receive("{\"v\":1,\"type\":\"hello\",\"operation_id\":\"" + id() + "\"}", peer);
            document = (String) peer.last().get("document_epoch"); account = (Long) peer.last().get("account_epoch");
            challenge = (String) peer.payload().get("challenge");
        }
        String message(String type, String op, Map<String,Object> payload) {
            return GooglePlayBillingController.Json.write(Map.of("v",1,"type",type,"operation_id",op,
                "document_epoch",document,"account_epoch",account,"payload",payload));
        }
        void send(String type, Map<String,Object> payload) { controller.receive(message(type,id(),payload),peer); }
        void bind() { hello(); send("bindScope",Map.of("challenge",challenge,"selection",selection(time.wall + 900000)));
            account = (Long) peer.last().get("account_epoch"); assertEquals("scopeBound",peer.last().get("type")); }
        void ready() { bind(); send("queryOwned",Map.of()); gateway.connecting.complete(null,null); }
        GooglePlayBillingController.Observation observation(int state, String accountId) {
            return new GooglePlayBillingController.Observation("com.invidim.app","product",accountId,"synthetic-not-a-purchase-token",state);
        }
    }
    static Map<String,Object> selection(long expires) {
        Map<String,Object> s = new LinkedHashMap<>();
        s.put("binding_intent_id",UUID.randomUUID().toString()); s.put("expires_at_ms",expires);
        s.put("obfuscated_account_id",ACCOUNT); s.put("catalog_key","basic"); s.put("catalog_version","v1");
        s.put("package_name","com.invidim.app"); s.put("product_id","product"); s.put("base_plan_id","monthly"); s.put("offer_id",null);
        return s;
    }
    @Test public void helloReportsHardClosedCapabilities() {
        Fixture f = new Fixture(); f.hello();
        assertEquals("challenge",f.peer.last().get("type"));
        assertEquals(false,f.peer.payload().get("purchaseLaunchEnabled"));
        assertEquals(false,f.peer.payload().get("tokenDeliveryEnabled"));
        assertEquals(32,f.document.length()); assertEquals(0,f.gateway.connects);
    }
    @Test public void malformedDuplicateUnknownOversizedAndInvalidUuidMessagesAreDropped() {
        Fixture f = new Fixture();
        for (String json : List.of("{", "[]", "{\"v\":1,\"v\":1,\"type\":\"hello\"}",
            "{\"v\":2,\"type\":\"hello\",\"operation_id\":\""+f.id()+"\"}",
            "{\"v\":1,\"type\":\"hello\",\"operation_id\":\"bad\"}",
            "{\"v\":1,\"type\":\"unknown\",\"operation_id\":\""+f.id()+"\"}"," ".repeat(32769))) {
            f.controller.receive(json,f.peer);
        }
        assertTrue(f.peer.replies.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> GooglePlayBillingController.Json.object("{\"a\":null,\"a\":1}"));
        assertThrows(IllegalArgumentException.class, () -> GooglePlayBillingController.Json.object("{\"v\":1.0}"));
    }
    @Test public void challengeIsSingleUseAndExpiresAtThirtySeconds() {
        Fixture f = new Fixture(); f.hello(); String c = f.challenge; f.time.advance(30000);
        f.send("bindScope",Map.of("challenge",c,"selection",selection(f.time.wall+60000)));
        assertEquals("CHALLENGE_INVALID",f.peer.payload().get("error"));
        f.hello(); f.send("bindScope",Map.of("challenge",f.challenge,"selection",selection(f.time.wall+60000)));
        assertEquals("scopeBound",f.peer.last().get("type"));
        f.account = (Long) f.peer.last().get("account_epoch");
        f.send("bindScope",Map.of("challenge",f.challenge,"selection",selection(f.time.wall+60000)));
        assertEquals("CHALLENGE_INVALID",f.peer.payload().get("error"));
    }
    @Test public void duplicateOperationNeverRepeatsAnActionAndAlteredReplayIsRejected() {
        Fixture f = new Fixture(); f.bind(); String op = f.id(); String request = f.message("queryOwned",op,Map.of());
        f.controller.receive(request,f.peer); f.controller.receive(request,f.peer);
        f.controller.receive(f.message("queryProduct",op,Map.of()),f.peer);
        assertEquals(1,f.gateway.connects); assertEquals("OPERATION_REPLAY",f.peer.payload().get("error"));
    }
    @Test public void foreignReplyAndStaleEpochCannotStartBilling() {
        Fixture f = new Fixture(); f.bind(); Peer other = new Peer();
        f.controller.receive(f.message("queryOwned",f.id(),Map.of()),other);
        f.account--; f.send("queryOwned",Map.of());
        assertEquals(0,f.gateway.connects); assertTrue(other.replies.isEmpty());
    }
    @Test public void bindingExpiresWithMonotonicTimeDespiteWallClockRollback() {
        Fixture f = new Fixture(); f.bind(); f.time.wall -= 1000000; f.time.advance(900000);
        f.send("queryOwned",Map.of()); assertEquals(0,f.gateway.connects);
    }
    @Test public void connectionIsSingleFlightTimeoutBoundedAndRetryCapped() {
        Fixture f = new Fixture(); f.bind(); f.send("queryOwned",Map.of()); f.send("queryProduct",Map.of());
        assertEquals(1,f.gateway.connects); f.time.advance(10000);
        assertEquals("CONNECTION_TIMEOUT",f.peer.payload().get("error"));
        f.send("queryOwned",Map.of()); assertEquals(2,f.gateway.connects);
        f.gateway.connecting.complete(null,"UNAVAILABLE"); f.send("queryOwned",Map.of());
        assertEquals(2,f.gateway.connects); assertEquals("RETRY_LIMIT",f.peer.payload().get("error"));
    }
    @Test public void recoverySingleFlightAndRetryBound() {
        Fixture f = new Fixture(); f.ready(); f.send("queryOwned",Map.of());
        assertEquals(1,f.gateway.owned); f.gateway.recovery.complete(List.of(),null);
        f.send("queryOwned",Map.of()); f.gateway.recovery.complete(List.of(),null); f.send("queryOwned",Map.of());
        assertEquals(2,f.gateway.owned); assertEquals("RETRY_LIMIT",f.peer.payload().get("error"));
    }
    @Test public void nativeObservationsAreDistinctDeduplicatedAndNeverContainToken() {
        Fixture f = new Fixture(); f.ready();
        f.gateway.recovery.complete(List.of(f.observation(2,ACCOUNT)),null);
        long pending = f.peer.replies.stream().filter(r -> "purchaseObserved".equals(r.get("type"))).count();
        f.gateway.observer.complete(List.of(f.observation(2,ACCOUNT)),null);
        assertEquals(pending,f.peer.replies.stream().filter(r -> "purchaseObserved".equals(r.get("type"))).count());
        f.gateway.observer.complete(List.of(f.observation(1,ACCOUNT)),null);
        assertEquals(pending+1,f.peer.replies.stream().filter(r -> "purchaseObserved".equals(r.get("type"))).count());
        assertFalse(GooglePlayBillingController.Json.write(f.peer.replies).contains("synthetic-not-a-purchase-token"));
        assertEquals(false,f.peer.payload().get("tokenDeliveryEnabled"));
    }
    @Test public void missingOrMismatchedAccountAndWrongProductAreNotObserved() {
        Fixture f = new Fixture(); f.ready(); int before = f.peer.replies.size();
        f.gateway.observer.complete(List.of(f.observation(1,null),f.observation(1,"other"),
            new GooglePlayBillingController.Observation("wrong","product",ACCOUNT,"synthetic",1)),null);
        assertEquals(before,f.peer.replies.size());
    }
    @Test public void invalidationAckPrecedesAnyNewAccountAndDropsOldCallbacks() {
        Fixture f = new Fixture(); f.ready(); var old = f.gateway.recovery;
        f.send("invalidateScope",Map.of()); assertEquals(true,f.peer.payload().get("acknowledged"));
        int count = f.peer.replies.size(); old.complete(List.of(f.observation(1,ACCOUNT)),null);
        assertEquals(count,f.peer.replies.size());
        f.bind(); assertTrue(f.account>1); old.complete(List.of(f.observation(2,ACCOUNT)),null);
        assertEquals("scopeBound",f.peer.last().get("type"));
    }
    @Test public void navigationAndLifecycleDropOutstandingCallbacksAndRequireHandshake() {
        for (String event : List.of("navigate","pause","renderer","destroy")) {
            Fixture f = new Fixture(); f.ready(); var old = f.gateway.recovery;
            switch(event) { case "navigate" -> f.controller.navigate(); case "pause" -> f.controller.pause();
                case "renderer" -> f.controller.rendererGone(); default -> f.controller.destroy(); }
            int count=f.peer.replies.size(); old.complete(List.of(f.observation(1,ACCOUNT)),null);
            assertEquals(event,count,f.peer.replies.size());
            if (event.equals("pause")) { f.controller.resume(); f.send("queryOwned",Map.of()); assertEquals(1,f.gateway.owned); f.bind(); }
            if (event.equals("destroy")) { f.controller.destroy(); assertEquals(1,f.gateway.closes); }
        }
    }
    @Test public void everyPurchaseRequestIsPermanentlyDisabled() {
        Fixture f = new Fixture(); f.bind();
        for (int i=0;i<3;i++) { f.send("requestPurchase",Map.of()); assertEquals("LAUNCH_DISABLED",f.peer.payload().get("error")); }
        assertEquals(0,f.gateway.connects);
    }
    @Test public void strictJsonRejectsSignedUnicodeEscapesAndEscapedDuplicateKeys() {
        for(String json: List.of("{\"\\u+041\":1}","{\"\\u-001\":1}","{\"a\":1,\"\\u0061\":2}")) {
            assertThrows(IllegalArgumentException.class,()->GooglePlayBillingController.Json.object(json));
        }
    }
    @Test public void lifecycleAbandonsPhysicalRequestsAndAllowsNewScopeRecovery() {
        Fixture f=new Fixture(); f.ready(); int before=f.gateway.resets;
        f.controller.pause(); f.controller.resume(); f.bind();
        assertTrue(f.gateway.resets>before);
        f.send("queryOwned",Map.of()); f.gateway.connecting.complete(null,null);
        assertEquals(2,f.gateway.owned);
    }
    @Test public void queuedAndLateObserverCannotBeRetargetedEvenToSameAccount() {
        class Deferred implements java.util.concurrent.Executor {
            boolean defer; final Queue<Runnable> tasks=new ArrayDeque<>();
            public void execute(Runnable r) { if(defer) tasks.add(r); else r.run(); }
        }
        Deferred executor=new Deferred(); Fixture f=new Fixture(executor); f.ready();
        var oldObserver=f.gateway.observer;
        executor.defer=true; oldObserver.complete(List.of(f.observation(1,ACCOUNT)),null); executor.defer=false;
        f.send("invalidateScope",Map.of()); f.bind(); f.send("queryOwned",Map.of());
        f.gateway.connecting.complete(null,null);
        int before=f.peer.replies.size();
        while(!executor.tasks.isEmpty()) executor.tasks.remove().run();
        oldObserver.complete(List.of(f.observation(2,ACCOUNT)),null);
        assertEquals(before,f.peer.replies.size());
    }
    @Test public void rawTokensNeverRemainInObservationObjectsAndInvalidTokensAreDropped() throws Exception {
        Fixture f=new Fixture(); f.ready(); var p=f.observation(1,ACCOUNT);
        for(var field:p.getClass().getDeclaredFields()) {
            field.setAccessible(true); assertNotEquals("synthetic-not-a-purchase-token",field.get(p));
        }
        int before=f.peer.replies.size();
        f.gateway.observer.complete(List.of(new GooglePlayBillingController.Observation("com.invidim.app","product",ACCOUNT,"x".repeat(8193),1)),null);
        assertEquals(before,f.peer.replies.size());
    }

    @Test public void accountSwitchKeepsOldAndForeignAccountObservationsOutOfNewScope() {
        Fixture f=new Fixture(); f.ready(); var oldObserver=f.gateway.observer;
        oldObserver.complete(List.of(f.observation(2,ACCOUNT)),null);
        assertEquals("PENDING",f.peer.payload().get("state"));
        f.send("invalidateScope",Map.of()); f.hello();
        var next=selection(f.time.wall+60000); next.put("obfuscated_account_id","v1_account_B");
        f.send("bindScope",Map.of("challenge",f.challenge,"selection",next));
        f.account=(Long)f.peer.last().get("account_epoch");
        f.send("queryOwned",Map.of()); f.gateway.connecting.complete(null,null);
        int before=f.peer.replies.size();
        oldObserver.complete(List.of(f.observation(1,ACCOUNT)),null);
        f.gateway.observer.complete(List.of(f.observation(1,ACCOUNT)),null);
        assertEquals(before,f.peer.replies.size());
        f.gateway.observer.complete(List.of(f.observation(1,"v1_account_B")),null);
        assertEquals(before+1,f.peer.replies.size()); assertEquals("PURCHASED",f.peer.payload().get("state"));
        assertEquals(false,f.peer.payload().get("tokenDeliveryEnabled"));
    }
    @Test public void scopeNeverLivesBeyondFifteenMinutesEvenForLongServerExpiry() {
        Fixture f=new Fixture(); f.hello();
        f.send("bindScope",Map.of("challenge",f.challenge,"selection",selection(f.time.wall+3600000)));
        f.account=(Long)f.peer.last().get("account_epoch"); f.time.advance(900000);
        f.send("queryOwned",Map.of()); assertEquals(0,f.gateway.connects);
    }

    private static int ledgerSize(Fixture f) {
        try {
            var field=GooglePlayBillingController.class.getDeclaredField("operations");
            field.setAccessible(true);
            return ((Map<?,?>)field.get(f.controller)).size();
        } catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static void fillLedger(Fixture f) {
        while(ledgerSize(f)<128) f.send("requestPurchase",Map.of());
        assertEquals(128,ledgerSize(f));
        assertEquals("LAUNCH_DISABLED",f.peer.payload().get("error"));
    }
    private static void bindAccountB(Fixture f) {
        f.hello();
        var next=selection(f.time.wall+60000); next.put("obfuscated_account_id","v1_account_B");
        f.send("bindScope",Map.of("challenge",f.challenge,"selection",next));
        assertEquals("scopeBound",f.peer.last().get("type"));
        f.account=(Long)f.peer.last().get("account_epoch");
    }
    @Test public void f1StaleAlteredReplayCannotEmitAccountBContextAndBStillWorks() {
        Fixture f=new Fixture(); f.bind(); String x=f.id();
        f.controller.receive(f.message("requestPurchase",x,Map.of()),f.peer);
        String stale=f.message("queryOwned",x,Map.of());
        f.send("invalidateScope",Map.of()); bindAccountB(f);
        int before=f.peer.replies.size(), count=ledgerSize(f);
        f.controller.receive(stale,f.peer);
        assertEquals(before,f.peer.replies.size()); assertEquals(count,ledgerSize(f));
        f.send("requestPurchase",Map.of());
        assertEquals("LAUNCH_DISABLED",f.peer.payload().get("error"));
        assertEquals(f.account,f.peer.last().get("account_epoch"));
    }
    @Test public void f1HelloCannotReplayOldScopedIdentityIntoNewAccount() {
        Fixture f=new Fixture(); f.bind(); String x=f.id();
        f.controller.receive(f.message("requestPurchase",x,Map.of()),f.peer);
        String hello=GooglePlayBillingController.Json.write(Map.of("v",1,"type","hello","operation_id",x));
        f.controller.receive(hello,f.peer);
        assertEquals("OPERATION_REPLAY",f.peer.payload().get("error"));
        f.send("invalidateScope",Map.of()); bindAccountB(f);
        int before=f.peer.replies.size(); f.controller.receive(hello,f.peer);
        assertEquals(before,f.peer.replies.size());
        f.send("requestPurchase",Map.of()); assertEquals("LAUNCH_DISABLED",f.peer.payload().get("error"));
    }
    @Test public void f1ForeignReplyAndStaleEpochsNeverConsumeLedgerQuota() {
        Fixture f=new Fixture(); f.bind(); int count=ledgerSize(f), before=f.peer.replies.size();
        Peer foreign=new Peer(); f.controller.receive(f.message("queryOwned",f.id(),Map.of()),foreign);
        assertEquals(count,ledgerSize(f)); assertTrue(foreign.replies.isEmpty());
        f.account--; f.send("queryOwned",Map.of()); f.account++;
        assertEquals(count,ledgerSize(f));
        String document=f.document; f.document="ffffffffffffffffffffffffffffffff";
        f.send("queryOwned",Map.of()); f.document=document;
        assertEquals(count,ledgerSize(f)); assertEquals(before,f.peer.replies.size());
        assertEquals(0,f.gateway.connects);
    }
    @Test public void f2Operation129EntersTerminalStateWithoutClearingReplayHistory() {
        Fixture f=new Fixture(); f.bind(); fillLedger(f);
        long originalAccount=f.account; String originalDocument=f.document, originalChallenge=f.challenge;
        f.send("requestPurchase",Map.of());
        assertEquals("DOCUMENT_QUOTA_EXHAUSTED",f.peer.payload().get("error"));
        assertEquals(originalAccount,f.peer.last().get("account_epoch"));
        assertEquals(originalDocument,f.peer.last().get("document_epoch"));
        int replies=f.peer.replies.size();
        f.send("invalidateScope",Map.of()); f.hello();
        f.send("bindScope",Map.of("challenge",originalChallenge,"selection",selection(f.time.wall+60000)));
        f.controller.pause(); f.controller.resume(); f.hello();
        assertEquals(replies,f.peer.replies.size()); assertEquals(128,ledgerSize(f));
        assertEquals(0,f.gateway.connects);
    }
    @Test public void f2NavigationResetsQuotaButOldDocumentReplayCannotBecomeValid() {
        Fixture f=new Fixture(); f.bind(); String oldId=f.id();
        f.controller.receive(f.message("requestPurchase",oldId,Map.of()),f.peer);
        String oldEnvelope=f.message("queryOwned",oldId,Map.of()), oldDocument=f.document;
        fillLedger(f); f.send("requestPurchase",Map.of());
        assertEquals("DOCUMENT_QUOTA_EXHAUSTED",f.peer.payload().get("error"));
        f.controller.navigate(); assertEquals(0,ledgerSize(f)); f.bind();
        assertNotEquals(oldDocument,f.document);
        int before=f.peer.replies.size(); f.controller.receive(oldEnvelope,f.peer);
        assertEquals(before,f.peer.replies.size()); assertEquals(2,ledgerSize(f));
        f.controller.receive(f.message("requestPurchase",oldId,Map.of()),f.peer);
        assertEquals("LAUNCH_DISABLED",f.peer.payload().get("error")); assertEquals(3,ledgerSize(f));
    }
    @Test public void f2QuotaTeardownPrecedesDiagnosticAndRejectsReentrantOrLateCallbacks() {
        Fixture f=new Fixture(); f.ready(); var late=f.gateway.recovery; fillLedger(f);
        int resets=f.gateway.resets;
        f.peer.onQuota=() -> {
            f.peer.onQuota=null;
            assertTrue(f.gateway.resets>resets);
            f.hello(); f.send("invalidateScope",Map.of());
            late.complete(List.of(f.observation(1,ACCOUNT)),null);
        };
        int before=f.peer.replies.size(); f.send("requestPurchase",Map.of());
        assertEquals(before+1,f.peer.replies.size());
        assertEquals("DOCUMENT_QUOTA_EXHAUSTED",f.peer.payload().get("error"));
        assertEquals(128,ledgerSize(f));
    }
    @Test public void f2UnscopedHelloAtQuotaCannotExposeBoundAccountContext() {
        Fixture f=new Fixture(); f.bind(); fillLedger(f); int before=f.peer.replies.size();
        Peer other=new Peer();
        f.controller.receive(GooglePlayBillingController.Json.write(Map.of("v",1,"type","hello","operation_id",f.id())),other);
        assertTrue(other.replies.isEmpty()); assertEquals(before,f.peer.replies.size());
        f.hello(); assertEquals(before,f.peer.replies.size()); assertEquals(128,ledgerSize(f));
        f.controller.navigate(); f.bind(); assertEquals("scopeBound",f.peer.last().get("type"));
    }

}
