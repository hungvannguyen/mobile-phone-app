package com.hung.app.billing;

import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;

public class GooglePlayProductSelectionTest {
    private GooglePlayBillingController.Selection selection() {
        return GooglePlayBillingController.Selection.from(GooglePlayBillingControllerTest.selection(2000000));
    }
    private GooglePlayBillingController.Offer offer(String product,String plan,String id) {
        return new GooglePlayBillingController.Offer(product,plan,id,"synthetic-offer","$1");
    }
    @Test public void onlyExactProductPlanAndNullableOfferMatch() {
        var s=selection(); var exact=offer("product","monthly",null);
        assertSame(exact,GooglePlayBillingController.select(s,List.of(offer("other","monthly",null),offer("product","yearly",null),offer("product","monthly","discount"),exact)));
    }
    @Test public void missingAndAmbiguousMatchesNeverFallback() {
        var s=selection();
        assertThrows(IllegalArgumentException.class,()->GooglePlayBillingController.select(s,List.of(offer("other","monthly",null))));
        assertThrows(IllegalArgumentException.class,()->GooglePlayBillingController.select(s,List.of(offer("product","monthly",null),offer("product","monthly",null))));
        assertThrows(IllegalArgumentException.class,()->GooglePlayBillingController.select(s,List.of()));
    }
    @Test public void exactOfferAndNoClientOfferToken() {
        var raw=GooglePlayBillingControllerTest.selection(2000000); raw.put("offer_id","discount");
        var s=GooglePlayBillingController.Selection.from(raw); var exact=offer("product","monthly","discount");
        assertSame(exact,GooglePlayBillingController.select(s,List.of(offer("product","monthly",null),exact)));
        raw.put("offerToken","untrusted"); assertThrows(IllegalArgumentException.class,()->GooglePlayBillingController.Selection.from(raw));
    }
    @Test public void invalidSelectionTypesAndPackageFailClosed() {
        for (String field: List.of("expires_at_ms","obfuscated_account_id","package_name","binding_intent_id","catalog_version")) {
            var raw=GooglePlayBillingControllerTest.selection(2000000); raw.put(field,"invalid");
            if(field.equals("obfuscated_account_id") || field.equals("catalog_version")) raw.put(field,"");
            assertThrows(field,IllegalArgumentException.class,()->GooglePlayBillingController.Selection.from(raw));
        }
    }
    @Test public void productQueryErrorNeverProducesASelectionOrOfferToken() {
        var f=new GooglePlayBillingControllerTest.Fixture(); f.bind(); f.send("queryProduct",Map.of()); f.gateway.connecting.complete(null,null);
        f.gateway.product.complete(null,"PRODUCT_UNAVAILABLE"); assertEquals("PRODUCT_UNAVAILABLE",f.peer.payload().get("error"));
        String response=GooglePlayBillingController.Json.write(f.peer.last());
        assertFalse(response.contains("offerToken")); assertFalse(response.contains("offer_token"));
    }
}
