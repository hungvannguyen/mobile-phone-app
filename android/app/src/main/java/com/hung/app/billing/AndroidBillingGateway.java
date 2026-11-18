package com.hung.app.billing;

import android.content.Context;

import com.android.billingclient.api.AccountIdentifiers;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.UnfetchedProduct;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.hung.app.billing.GooglePlayBillingController.Callback;
import com.hung.app.billing.GooglePlayBillingController.Observation;
import com.hung.app.billing.GooglePlayBillingController.Offer;
import com.hung.app.billing.GooglePlayBillingController.Selection;

/** Read-only SDK adapter. Purchase observations require controller ownership checks. */
public final class AndroidBillingGateway implements GooglePlayBillingController.Gateway {
    private final Context context;
    private BillingClient client;
    private Callback<List<Observation>> observer;
    private Callback<Void> connectionCallback;
    private boolean ownedQueryPending;
    private boolean productQueryPending;
    private boolean closed;
    private long connectionRequest;
    private long sessionGeneration;
    private boolean cleanupFailed;

    public AndroidBillingGateway(Context context) {
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
    }

    @Override
    public synchronized void setObserver(Callback<List<Observation>> observer) {
        if (!closed) this.observer = observer;
    }

    @Override
    public synchronized void connect(Callback<Void> callback) {
        if (closed) {
            callback.complete(null, "GATEWAY_CLOSED");
            return;
        }
        if (cleanupFailed) {
            callback.complete(null, "BILLING_CONNECTION_FAILED");
            return;
        }
        if (connectionCallback != null) {
            callback.complete(null, "BUSY");
            return;
        }
        try {
            if (client != null && client.isReady()) {
                callback.complete(null, null);
                return;
            }
        } catch (RuntimeException ignored) {
            reset();
            callback.complete(null, "BILLING_CONNECTION_FAILED");
            return;
        }
        connectionCallback = callback;
        final long request = ++connectionRequest;
        final long generation = sessionGeneration;
        try {
            if (client == null) {
                final Callback<List<Observation>> clientObserver = observer;
                client = BillingClient.newBuilder(context)
                        .setListener((result, purchases) ->
                                onPurchasesUpdated(generation, clientObserver, result, purchases))
                        .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                                .enableOneTimeProducts().build())
                        .enableAutoServiceReconnection()
                        .build();
            }
            client.startConnection(new BillingClientStateListener() {
                @Override
                public void onBillingSetupFinished(BillingResult result) {
                    finishConnection(generation, request, result.getResponseCode() == BillingClient.BillingResponseCode.OK
                            ? null : error(result, "BILLING_CONNECTION_FAILED"));
                }

                @Override
                public void onBillingServiceDisconnected() {
                    finishConnection(generation, request, "BILLING_UNAVAILABLE");
                }
            });
        } catch (RuntimeException ignored) {
            finishConnection(generation, request, "BILLING_CONNECTION_FAILED");
        }
    }

    private synchronized void finishConnection(long generation, long request, String error) {
        if (closed || generation != sessionGeneration
                || request != connectionRequest || connectionCallback == null) return;
        Callback<Void> callback = connectionCallback;
        connectionCallback = null;
        callback.complete(null, error);
    }

    @Override
    public synchronized void queryProduct(Selection selection, Callback<List<Offer>> callback) {
        if (!ready(callback)) return;
        if (productQueryPending) {
            callback.complete(null, "BUSY");
            return;
        }
        productQueryPending = true;
        final long generation = sessionGeneration;
        try {
            QueryProductDetailsParams params = QueryProductDetailsParams.newBuilder()
                    .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                            .setProductId(selection.productId)
                            .setProductType(BillingClient.ProductType.SUBS).build()))
                    .build();
            client.queryProductDetailsAsync(params, (result, detailsResult) -> {
                synchronized (AndroidBillingGateway.this) {
                    if (closed || generation != sessionGeneration) return;
                    productQueryPending = false;
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        callback.complete(null, error(result, "PRODUCT_QUERY_FAILED"));
                        return;
                    }
                    if (detailsResult == null) {
                        callback.complete(null, "PRODUCT_UNAVAILABLE");
                        return;
                    }
                    for (UnfetchedProduct unavailable : detailsResult.getUnfetchedProductList()) {
                        if (selection.productId.equals(unavailable.getProductId())) {
                            callback.complete(null, "PRODUCT_UNAVAILABLE");
                            return;
                        }
                    }
                    List<Offer> offers = new ArrayList<>();
                    for (ProductDetails details : detailsResult.getProductDetailsList()) {
                        if (!selection.productId.equals(details.getProductId())
                                || !BillingClient.ProductType.SUBS.equals(details.getProductType())
                                || details.getSubscriptionOfferDetails() == null) continue;
                        for (ProductDetails.SubscriptionOfferDetails offer : details.getSubscriptionOfferDetails()) {
                            String price = recurringPrice(offer);
                            if (price == null) continue;
                            offers.add(new Offer(details.getProductId(), offer.getBasePlanId(),
                                    offer.getOfferId(), offer.getOfferToken(), price));
                        }
                    }
                    callback.complete(offers.isEmpty() ? null : offers,
                            offers.isEmpty() ? "PRODUCT_UNAVAILABLE" : null);
                }
            });
        } catch (RuntimeException ignored) {
            if (!closed && generation == sessionGeneration) {
                productQueryPending = false;
                callback.complete(null, "PRODUCT_QUERY_FAILED");
            }
        }
    }

    private static String recurringPrice(ProductDetails.SubscriptionOfferDetails offer) {
        String price = null;
        for (ProductDetails.PricingPhase phase : offer.getPricingPhases().getPricingPhaseList()) {
            if (phase.getRecurrenceMode() == ProductDetails.RecurrenceMode.INFINITE_RECURRING
                    || phase.getRecurrenceMode() == ProductDetails.RecurrenceMode.FINITE_RECURRING) {
                price = phase.getFormattedPrice();
            }
        }
        return price;
    }

    @Override
    public synchronized void queryOwned(Callback<List<Observation>> callback) {
        if (!ready(callback)) return;
        if (ownedQueryPending) {
            callback.complete(null, "BUSY");
            return;
        }
        ownedQueryPending = true;
        final long generation = sessionGeneration;
        try {
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                    .setProductType(BillingClient.ProductType.SUBS).build(), (result, purchases) -> {
                synchronized (AndroidBillingGateway.this) {
                    if (closed || generation != sessionGeneration) return;
                    ownedQueryPending = false;
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        callback.complete(null, error(result, "OWNED_QUERY_FAILED"));
                        return;
                    }
                    callback.complete(observations(purchases), null);
                }
            });
        } catch (RuntimeException ignored) {
            if (!closed && generation == sessionGeneration) {
                ownedQueryPending = false;
                callback.complete(null, "OWNED_QUERY_FAILED");
            }
        }
    }

    private <T> boolean ready(Callback<T> callback) {
        if (closed) {
            callback.complete(null, "GATEWAY_CLOSED");
            return false;
        }
        boolean connected;
        try {
            connected = client != null && client.isReady();
        } catch (RuntimeException ignored) {
            reset();
            callback.complete(null, "BILLING_UNAVAILABLE");
            return false;
        }
        if (!connected) {
            callback.complete(null, "BILLING_UNAVAILABLE");
            return false;
        }
        return true;
    }

    private synchronized void onPurchasesUpdated(long generation,
            Callback<List<Observation>> clientObserver, BillingResult result, List<Purchase> purchases) {
        if (closed || generation != sessionGeneration || clientObserver == null) return;
        if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
            clientObserver.complete(null, error(result, "PURCHASE_UPDATE_FAILED"));
            return;
        }
        clientObserver.complete(observations(purchases), null);
    }

    private static List<Observation> observations(List<Purchase> purchases) {
        List<Observation> observations = new ArrayList<>();
        if (purchases == null) return observations;
        for (Purchase purchase : purchases) {
            List<String> products = purchase.getProducts();
            if (products == null || products.size() != 1) continue;
            AccountIdentifiers identifiers = purchase.getAccountIdentifiers();
            int state = purchase.getPurchaseState();
            if (state != Purchase.PurchaseState.PENDING && state != Purchase.PurchaseState.PURCHASED) state = 0;
            observations.add(new Observation(purchase.getPackageName(), products.get(0),
                    identifiers == null ? null : identifiers.getObfuscatedAccountId(),
                    purchase.getPurchaseToken(), state));
        }
        return observations;
    }

    private static String error(BillingResult result, String fallback) {
        return result.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED
                ? "USER_CANCELED" : fallback;
    }

    @Override
    public synchronized void reset() {
        if (closed) return;
        sessionGeneration++;
        connectionRequest++;
        BillingClient previous = client;
        client = null;
        connectionCallback = null;
        ownedQueryPending = false;
        productQueryPending = false;
        if (previous != null) {
            try {
                previous.endConnection();
            } catch (RuntimeException ignored) {
                cleanupFailed = true;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        reset();
        closed = true;
        observer = null;
    }
}
