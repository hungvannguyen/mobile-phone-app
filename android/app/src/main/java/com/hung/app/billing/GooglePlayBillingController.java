package com.hung.app.billing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Serialized, verification-only coordinator. Web scope is metadata, never ownership proof. */
public final class GooglePlayBillingController {
    public interface Callback<T> { void complete(T value, String error); }
    public interface Gateway {
        void setObserver(Callback<List<Observation>> observer);
        void connect(Callback<Void> callback);
        void queryProduct(Selection selection, Callback<List<Offer>> callback);
        void queryOwned(Callback<List<Observation>> callback);
        void reset();
        void close();
    }
    public interface Clock { long monotonicMs(); long wallMs(); }
    public interface Scheduler { void after(long delayMs, Runnable task); }
    public interface Reply { void send(String json); }
    private static final int MAX_MESSAGE_BYTES = 32768;
    private static final int MAX_OPERATIONS = 128;
    private static final long CHALLENGE_MS = 30000, SCOPE_MS = 900000, CONNECTION_MS = 10000;
    private static final Set<String> COMMANDS = keys("hello", "bindScope", "queryProduct", "queryOwned",
        "observationAccepted", "invalidateScope", "requestPurchase");
    private final Gateway gateway;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Executor coordinator;
    private final Supplier<String> nonce;
    private final Map<String, Map<String,Object>> operations = new LinkedHashMap<>();
    private final Map<String,Integer> observations = new HashMap<>();
    private String documentEpoch, challenge;
    private long accountEpoch, challengeDeadline, scopeDeadline, connectionTicket, queryTicket;
    private boolean resumed, destroyed, connecting, connected, querying;
    private boolean quotaExhausted;
    private int connectionAttempts, recoveryAttempts, productAttempts;
    private Reply documentReply;
    private Selection selection;
    private volatile Operation observing;

    public GooglePlayBillingController(Gateway gateway, Clock clock, Scheduler scheduler, Executor coordinator, Supplier<String> nonce) {
        this.gateway=gateway; this.clock=clock; this.scheduler=scheduler; this.coordinator=coordinator; this.nonce=nonce;
        documentEpoch=nonce.get();
    }
    public void receive(String raw, Reply reply) { coordinator.execute(() -> handle(raw,reply)); }
    public void resume() { coordinator.execute(() -> { if(!destroyed) { invalidate(); resumed=true; } }); }
    public void pause() { coordinator.execute(() -> { resumed=false; invalidate(); }); }
    public void navigate() { coordinator.execute(() -> {
        invalidate(); documentEpoch=nonce.get(); operations.clear(); quotaExhausted=false;
    }); }
    public void rendererGone() { destroy(); }
    public void destroy() { coordinator.execute(() -> {
        if(destroyed) return;
        resumed=false; destroyed=true; invalidate(); connectionTicket++; gateway.close();
    }); }
    private void invalidate() {
        abandonPhysical();
        accountEpoch++; challenge=null; selection=null; documentReply=null; observing=null; observations.clear();
        connectionAttempts=0; recoveryAttempts=0; productAttempts=0;
    }
    private void abandonPhysical() {
        connectionTicket++; queryTicket++; connecting=false; connected=false; querying=false; gateway.reset();
    }
    private void watch(Operation owner) {
        gateway.setObserver((value,error) -> {
            Operation captured=observing;
            coordinator.execute(() -> {
                if(!live(owner) || !live(captured)) return;
                if(error!=null) { reply(captured,"purchaseObserved",map("error",safeError(error))); return; }
                observe(captured,value);
            });
        });
    }
    private void handle(String raw, Reply target) {
        if (destroyed || !resumed || quotaExhausted || target==null || raw==null || raw.length()>MAX_MESSAGE_BYTES
            || raw.getBytes(StandardCharsets.UTF_8).length>MAX_MESSAGE_BYTES) return;
        final Map<String,Object> m;
        final String type, id;
        try {
            m=Json.object(raw); type=text(m,"type"); id=text(m,"operation_id");
            if(!Long.valueOf(1).equals(m.get("v")) || !COMMANDS.contains(type) || !uuid(id)) return;
            fields(m,type.equals("hello") ? keys("v","type","operation_id")
                : keys("v","type","operation_id","document_epoch","account_epoch","payload"));
            if(!type.equals("hello")) {
                text(m,"document_epoch");
                if(!(m.get("account_epoch") instanceof Long)) throw new IllegalArgumentException();
                Map<String,Object> body=object(m.get("payload"));
                fields(body,type.equals("bindScope") ? keys("challenge","selection") : keys());
                if(type.equals("bindScope")) { text(body,"challenge"); Selection.from(object(body.get("selection"))); }
            }
        } catch (IllegalArgumentException e) { return; }
        if(!type.equals("hello")) {
            if(documentReply!=target) return;
            if(!documentEpoch.equals(m.get("document_epoch")) || !Long.valueOf(accountEpoch).equals(m.get("account_epoch"))) return;
        }
        Map<String,Object> previous=operations.get(id);
        if(previous!=null) {
            boolean replayContextMatches=!type.equals("hello")
                || (documentEpoch.equals(previous.get("document_epoch")) && Long.valueOf(accountEpoch).equals(previous.get("account_epoch")));
            if(!previous.equals(m) && documentReply==target && replayContextMatches) emit(target,type,id,map("error","OPERATION_REPLAY"));
            return;
        }
        if(operations.size()>=MAX_OPERATIONS) { exhaustDocument(type,id,target); return; }
        operations.put(id,m);
        if(type.equals("hello")) {
            invalidate(); documentReply=target; challenge=nonce.get(); challengeDeadline=clock.monotonicMs()+CHALLENGE_MS;
            emit(target,"challenge",id,map("challenge",challenge,"expires_in_ms",CHALLENGE_MS,
                "purchaseLaunchEnabled",false,"tokenDeliveryEnabled",false));
            return;
        }
        final Map<String,Object> payload;
        try { payload=object(m.get("payload")); }
        catch(IllegalArgumentException e) { emit(target,type,id,map("error","INVALID_MESSAGE")); return; }
        if(type.equals("requestPurchase")) {
            emit(target,type,id,map("error","LAUNCH_DISABLED","purchaseLaunchEnabled",false));
            return;
        }
        if(type.equals("bindScope")) {
            String expected=challenge; challenge=null;
            try {
                fields(payload,keys("challenge","selection"));
                if(expected==null || !expected.equals(payload.get("challenge")) || clock.monotonicMs()>=challengeDeadline)
                    throw new IllegalStateException();
                Selection candidate=Selection.from(object(payload.get("selection")));
                long remaining=Math.min(SCOPE_MS,candidate.expiresAtMs-clock.wallMs());
                if(remaining<=0) throw new IllegalArgumentException();
                selection=candidate; scopeDeadline=clock.monotonicMs()+remaining; accountEpoch++;
                observations.clear(); recoveryAttempts=0; productAttempts=0; connectionAttempts=0;
                long epoch=accountEpoch;
                scheduler.after(remaining,() -> coordinator.execute(() -> {
                    if(epoch==accountEpoch && clock.monotonicMs()>=scopeDeadline) invalidate();
                }));
                emit(target,"scopeBound",id,map("purchaseLaunchEnabled",false,"tokenDeliveryEnabled",false));
            } catch(IllegalStateException e) { emit(target,type,id,map("error","CHALLENGE_INVALID")); }
            catch(IllegalArgumentException e) { emit(target,type,id,map("error","INVALID_SELECTION")); }
            return;
        }
        try { fields(payload,keys()); } catch(IllegalArgumentException e) { emit(target,type,id,map("error","INVALID_MESSAGE")); return; }
        if(type.equals("invalidateScope")) {
            Reply original=documentReply; invalidate(); emit(original,type,id,map("acknowledged",true)); return;
        }
        Operation op=new Operation(id,documentEpoch,accountEpoch,selection,target,scopeDeadline);
        if(!live(op)) { invalidate(); return; }
        if(type.equals("observationAccepted")) {
            // No raw token is ever retained or delivered in this native-only stage.
            reply(op,type,map("acknowledged",true,"tokenDeliveryEnabled",false)); return;
        }
        if(querying || connecting) { reply(op,type,map("error","BUSY")); return; }
        if(type.equals("queryOwned") ? recoveryAttempts>=2 : productAttempts>=2) {
            reply(op,type,map("error","RETRY_LIMIT")); return;
        }
        if(connected) query(type,op); else connect(type,op);
    }
    private void exhaustDocument(String type, String id, Reply target) {
        // Only a scoped request has a validated account context for the diagnostic.
        String response=type.equals("hello") ? null : Json.write(map("v",1,"type",type,"operation_id",id,
            "document_epoch",documentEpoch,"account_epoch",accountEpoch,"payload",map("error","DOCUMENT_QUOTA_EXHAUSTED")));
        quotaExhausted=true;
        invalidate();
        if(response!=null && resumed && !destroyed) target.send(response);
    }
    private void connect(String type, Operation op) {
        if(connectionAttempts>=2) { reply(op,type,map("error","RETRY_LIMIT")); return; }
        watch(op);
        connecting=true; connectionAttempts++; long ticket=++connectionTicket;
        long deadline=clock.monotonicMs()+CONNECTION_MS;
        scheduler.after(CONNECTION_MS,() -> coordinator.execute(() -> {
            if(connecting && ticket==connectionTicket && clock.monotonicMs()>=deadline) {
                abandonPhysical(); reply(op,type,map("error","CONNECTION_TIMEOUT"));
            }
        }));
        gateway.connect((value,error) -> coordinator.execute(() -> {
            if(destroyed || ticket!=connectionTicket || !connecting) return;
            connecting=false;
            if(error!=null) { connected=false; reply(op,type,map("error",safeError(error))); return; }
            connected=true;
            if(live(op)) query(type,op);
        }));
    }
    private void query(String type, Operation op) {
        if(!live(op)) return;
        querying=true; long ticket=++queryTicket;
        if(type.equals("queryProduct")) {
            productAttempts++;
            gateway.queryProduct(op.selection,(offers,error) -> coordinator.execute(() -> {
                if(ticket!=queryTicket || !live(op)) return;
                querying=false;
                if(error!=null) { connected=false; reply(op,type,map("error",safeError(error))); return; }
                try {
                    Offer match=select(op.selection,offers);
                    reply(op,type,map("product_id",match.productId,"base_plan_id",match.basePlanId,
                        "formatted_price",match.formattedPrice,"purchaseLaunchEnabled",false));
                } catch(IllegalArgumentException e) { reply(op,type,map("error","PRODUCT_UNAVAILABLE")); }
            }));
        } else {
            recoveryAttempts++; observing=op;
            gateway.queryOwned((purchases,error) -> coordinator.execute(() -> {
                if(ticket!=queryTicket || !live(op)) return;
                querying=false;
                if(error!=null) { connected=false; reply(op,type,map("error",safeError(error))); return; }
                observe(op,purchases);
                reply(op,type,map("completed",true,"tokenDeliveryEnabled",false));
            }));
        }
    }
    private void observe(Operation op,List<Observation> purchases) {
        if(!live(op) || purchases==null) return;
        for(Observation p:purchases) {
            if(p==null || !op.selection.packageName.equals(p.packageName) || !op.selection.productId.equals(p.productId)
                || p.accountId==null || !op.selection.accountId.equals(p.accountId) || p.tokenDigest==null
                || (p.state!=1 && p.state!=2)) continue;
            String fingerprint=p.tokenDigest;
            if(Objects.equals(observations.get(fingerprint),p.state)) continue;
            if(observations.size()>=128) { invalidate(); return; }
            observations.put(fingerprint,p.state);
            // Permanently closed: only nonsecret state crosses the channel; token lifetime here is zero.
            reply(op,"purchaseObserved",map("product_id",p.productId,"state",p.state==2 ? "PENDING" : "PURCHASED",
                "tokenDeliveryEnabled",false));
        }
    }
    private static String digest(String token) {
        try { byte[] hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(); for(byte b:hash) hex.append(String.format("%02x",b & 255)); return hex.toString(); }
        catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private boolean live(Operation op) {
        return op!=null && !destroyed && resumed && selection!=null && op.selection==selection && documentReply==op.reply
            && documentEpoch.equals(op.document) && accountEpoch==op.account && clock.monotonicMs()<op.deadline;
    }
    private void reply(Operation op,String type,Map<String,Object> payload) { if(live(op)) emit(op.reply,type,op.id,payload); }
    private void emit(Reply target,String type,String id,Map<String,Object> payload) {
        if(destroyed || !resumed) return;
        target.send(Json.write(map("v",1,"type",type,"operation_id",id,"document_epoch",documentEpoch,
            "account_epoch",accountEpoch,"payload",payload)));
    }
    private static String safeError(String code) {
        return keys("BUSY","USER_CANCELED","BILLING_CONNECTION_FAILED","BILLING_UNAVAILABLE","PRODUCT_UNAVAILABLE",
            "PRODUCT_QUERY_FAILED","OWNED_QUERY_FAILED","GATEWAY_CLOSED").contains(code) ? code : "BILLING_UNAVAILABLE";
    }
    private static final class Operation {
        final String id,document; final long account,deadline; final Selection selection; final Reply reply;
        Operation(String id,String document,long account,Selection selection,Reply reply,long deadline) {
            this.id=id; this.document=document; this.account=account; this.selection=selection; this.reply=reply; this.deadline=deadline;
        }
    }
    public static final class Selection {
        public final String bindingIntentId,accountId,catalogKey,catalogVersion,packageName,productId,basePlanId,offerId;
        public final long expiresAtMs;
        private Selection(Map<String,Object> m) {
            fields(m,keys("binding_intent_id","expires_at_ms","obfuscated_account_id","catalog_key","catalog_version",
                "package_name","product_id","base_plan_id","offer_id"));
            bindingIntentId=text(m,"binding_intent_id"); if(!uuid(bindingIntentId)) throw new IllegalArgumentException();
            if(!(m.get("expires_at_ms") instanceof Long)) throw new IllegalArgumentException();
            expiresAtMs=(Long)m.get("expires_at_ms"); if(expiresAtMs<=0) throw new IllegalArgumentException();
            accountId=text(m,"obfuscated_account_id"); if(accountId.length()>64) throw new IllegalArgumentException();
            catalogKey=text(m,"catalog_key"); catalogVersion=text(m,"catalog_version"); packageName=text(m,"package_name");
            productId=text(m,"product_id"); basePlanId=text(m,"base_plan_id");
            offerId=m.get("offer_id")==null ? null : text(m,"offer_id");
            if(!packageName.equals("com.invidim.app")) throw new IllegalArgumentException();
        }
        public static Selection from(Map<String,Object> m) { return new Selection(m); }
    }
    public static final class Offer {
        public final String productId,basePlanId,offerId,offerToken,formattedPrice;
        public Offer(String productId,String basePlanId,String offerId,String offerToken,String formattedPrice) {
            this.productId=productId; this.basePlanId=basePlanId; this.offerId=offerId; this.offerToken=offerToken; this.formattedPrice=formattedPrice;
        }
    }
    public static Offer select(Selection s,List<Offer> offers) {
        Offer selected=null;
        if(offers==null) throw new IllegalArgumentException();
        for(Offer o:offers) if(o!=null && s.productId.equals(o.productId) && s.basePlanId.equals(o.basePlanId) && Objects.equals(s.offerId,o.offerId)) {
            if(selected!=null || o.offerToken==null || o.offerToken.isEmpty() || o.formattedPrice==null || o.formattedPrice.isEmpty()) throw new IllegalArgumentException();
            selected=o;
        }
        if(selected==null) throw new IllegalArgumentException(); return selected;
    }
    public static final class Observation {
        public final String packageName,productId,accountId; private final String tokenDigest; public final int state;
        public Observation(String packageName,String productId,String accountId,String token,int state) {
            this.packageName=packageName; this.productId=productId; this.accountId=accountId; this.state=state;
            // Raw tokens never enter coordinator queues, caches, replies or saved state.
            tokenDigest=token==null || token.isEmpty() || token.length()>8192
                || token.getBytes(StandardCharsets.UTF_8).length>8192 || token.chars().anyMatch(c -> c<32 || c==127) ? null : digest(token);
        }
    }
    private static Map<String,Object> map(Object... entries) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2) result.put((String)entries[i],entries[i+1]);
        return result;
    }
    private static Set<String> keys(String... names) { return new HashSet<>(Arrays.asList(names)); }
    private static String join(List<String> values) {
        StringBuilder result=new StringBuilder();
        for(String value:values) { if(result.length()>0) result.append(','); result.append(value); }
        return result.toString();
    }
    private static boolean uuid(String value) { return value.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}"); }
    private static String text(Map<String,Object> m,String key) {
        if(!(m.get(key) instanceof String s) || s.isEmpty() || s.length()>150 || s.chars().anyMatch(c -> c<32 || c==127)) throw new IllegalArgumentException();
        return s;
    }
    private static void fields(Map<String,Object> m,Set<String> names) { if(!m.keySet().equals(names)) throw new IllegalArgumentException(); }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object o) {
        if(!(o instanceof Map)) throw new IllegalArgumentException(); return (Map<String,Object>)o;
    }

    /** Small strict JSON codec: object-only input, duplicate detection, integers only, bounded depth. */
    public static final class Json {
        private final String input; private int pos;
        private Json(String input) { this.input=input; }
        public static Map<String,Object> object(String input) {
            if(input==null || input.length()>MAX_MESSAGE_BYTES || input.getBytes(StandardCharsets.UTF_8).length>MAX_MESSAGE_BYTES) throw new IllegalArgumentException();
            Json p=new Json(input); Object value=p.value(0); p.space();
            if(p.pos!=input.length()) throw new IllegalArgumentException(); return GooglePlayBillingController.object(value);
        }
        private void space() { while(pos<input.length() && " \r\n\t".indexOf(input.charAt(pos))>=0) pos++; }
        private Object value(int depth) {
            space(); if(depth>8 || pos>=input.length()) throw new IllegalArgumentException();
            char c=input.charAt(pos);
            if(c=='{') {
                pos++; Map<String,Object> m=new LinkedHashMap<>(); space(); if(take('}')) return m;
                do { space(); String k=string(); space(); require(':');
                    if(m.containsKey(k)) throw new IllegalArgumentException(); m.put(k,value(depth+1)); space();
                    if(take('}')) return m; require(',');
                } while(true);
            }
            if(c=='"') return string();
            for(String literal:Arrays.asList("null","true","false")) if(input.startsWith(literal,pos)) {
                pos+=literal.length(); if(literal.equals("null")) return null; return literal.equals("true");
            }
            int start=pos; if(c=='-') pos++;
            if(pos>=input.length() || input.charAt(pos)<'0' || input.charAt(pos)>'9') throw new IllegalArgumentException();
            if(input.charAt(pos)=='0') pos++; else while(pos<input.length() && input.charAt(pos)>='0' && input.charAt(pos)<='9') pos++;
            try { return Long.valueOf(input.substring(start,pos)); } catch(NumberFormatException e) { throw new IllegalArgumentException(); }
        }
        private boolean take(char c) { if(pos<input.length() && input.charAt(pos)==c) { pos++; return true; } return false; }
        private void require(char c) { if(!take(c)) throw new IllegalArgumentException(); }
        private String string() {
            require('"'); StringBuilder b=new StringBuilder(); boolean end=false;
            while(pos<input.length()) {
                char c=input.charAt(pos++); if(c=='"') { end=true; break; }
                if(c<32) throw new IllegalArgumentException();
                if(c=='\\') {
                    if(pos>=input.length()) throw new IllegalArgumentException(); char escape=input.charAt(pos++);
                    switch(escape) {
                        case '"','\\','/' -> b.append(escape); case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'n' -> b.append('\n'); case 'r' -> b.append('\r'); case 't' -> b.append('\t');
                        case 'u' -> { if(pos+4>input.length()) throw new IllegalArgumentException();
                            try {
                                String hex=input.substring(pos,pos+4);
                                if(!hex.matches("[0-9a-fA-F]{4}")) throw new IllegalArgumentException();
                                b.append((char)Integer.parseInt(hex,16));
                            }
                            catch(NumberFormatException e) { throw new IllegalArgumentException(); } pos+=4; }
                        default -> throw new IllegalArgumentException();
                    }
                } else b.append(c);
            }
            if(!end) throw new IllegalArgumentException();
            String result=b.toString();
            for(int i=0;i<result.length();i++) {
                char c=result.charAt(i);
                if(Character.isHighSurrogate(c)) { if(++i>=result.length() || !Character.isLowSurrogate(result.charAt(i))) throw new IllegalArgumentException(); }
                else if(Character.isLowSurrogate(c)) throw new IllegalArgumentException();
            }
            return result;
        }
        public static String write(Object value) {
            if(value==null) return "null";
            if(value instanceof Map<?,?> m) {
                List<String> entries=new ArrayList<>(); for(var e:m.entrySet()) entries.add(write(e.getKey().toString())+":"+write(e.getValue()));
                return "{"+join(entries)+"}";
            }
            if(value instanceof Collection<?> c) { List<String> values=new ArrayList<>(); for(Object v:c) values.add(write(v)); return "["+join(values)+"]"; }
            if(value instanceof Boolean || value instanceof Number) return value.toString();
            StringBuilder b=new StringBuilder("\"");
            for(char c:value.toString().toCharArray()) {
                if(c=='"' || c=='\\') b.append('\\').append(c);
                else if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c);
            }
            return b.append('"').toString();
        }
    }
}
