package com.enunas.backend.order.integration;

import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class AdminItemCancellationIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    private List<RefundCommand> refundCalls() {
        ArgumentCaptor<RefundCommand> c = ArgumentCaptor.forClass(RefundCommand.class);
        verify(paymentProvider, atLeast(0)).refundPayment(c.capture());
        return c.getAllValues();
    }

    private long shippingReversals(long orderId, long brandId) {
        return reversals(orderId).stream()
                .filter(r -> ((String) r.get("external_reference_id")).endsWith(":SHIPPING"))
                .filter(r -> ((Number) r.get("brand_partner_id")).longValue() == brandId)
                .count();
    }

    private static String message(ResponseEntity<Map> resp) {
        return String.valueOf(resp.getBody().get("message"));
    }

    // ===== happy paths =====

    @Test
    void cancellingOneOfABrandsItems_refundsExactlyThatItem() {
        Fixture f = paidTwoBrandOrder(null);
        BigDecimal pendingB = brandPending(f.brandBId());

        ResponseEntity<Map> resp = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(refundCalls()).hasSize(1);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount"));
        Map<String, Object> a1 = itemRow(f.a1());
        assertThat(a1.get("cancellation_reason")).isEqualTo("OUT_OF_STOCK");
        assertThat((String) a1.get("refund_transaction_id")).startsWith("ref_mock_");
        assertThat(stockOfItem(f.a1())).isEqualTo(5);
        assertThat(stockOfItem(f.a2())).isEqualTo(4);
        assertThat(reversals(f.orderId())).hasSize(1);
        assertThat((BigDecimal) reversals(f.orderId()).get(0).get("brand_payout"))
                .isEqualByComparingTo(itemMoney(f.a1(), "brand_payout_amount").negate());
        assertThat(brandPending(f.brandBId())).isEqualByComparingTo(pendingB);
        assertThat(orderStatus(f.orderId())).isEqualTo("PAID");
        assertThat(paymentStatus(f.orderId())).isEqualTo("REFUNDED");
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), contains("storniert"), contains("erstatten"));
        verify(emailService).sendPlainTextEmail(eq("brand-a@it.local"), contains("Stornierung"), contains("NICHT"));
    }

    @Test
    void discountedItem_refundsWhatTheCustomerPaid_notThePreDiscountPrice() {
        Fixture f = paidTwoBrandOrder("CX10");
        BigDecimal paid = itemMoney(f.a1(), "customer_gross_after_discount");
        assertThat(paid).isLessThan(itemMoney(f.a1(), "line_gross"));

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(paid);
    }

    @Test
    void partiallyShippedOrder_emptyingTheUnshippedBrand_refundsItsShipping_andCompletesTheOrder() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderStatus(f.orderId())).isEqualTo("PARTIALLY_SHIPPED");

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);

        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(
                itemMoney(f.b1(), "customer_gross_after_discount").add(shippingOf(f.orderId(), f.brandBId())));
        assertThat(shippingReversals(f.orderId(), f.brandBId())).isEqualTo(1);
        assertThat(orderStatus(f.orderId())).as("D27: nothing left to wait for").isEqualTo("SHIPPED");
    }

    @Test
    void emptyingABrandBeforeAnyShipment_refundsItsShipping_andTheOrderShipsWithTheOtherBrand() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);
        assertThat(shippingReversals(f.orderId(), f.brandBId())).isEqualTo(1);
        assertThat(orderStatus(f.orderId())).isEqualTo("PAID");

        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderStatus(f.orderId())).isEqualTo("SHIPPED");
    }

    @Test
    void partialCancel_neverRefundsShipping() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount"));
        assertThat(shippingReversals(f.orderId(), f.brandAId())).isZero();
    }

    @Test
    void cancellingEveryItem_cancelsTheOrder_andReleasesTheDiscountOnce() {
        Fixture f = paidTwoBrandOrder("CX10");
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a2(), f.a3())).getStatusCode().value())
                .isEqualTo(200);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);

        assertThat(orderStatus(f.orderId())).isEqualTo("CANCELLED");
        assertThat(usedCount("CX10")).isZero();
        assertThat(orderRow(f.orderId()).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    void secondCancelAfterThePaymentReadsRefunded_stillRefunds_andBlocksTheWholeOrderCancel() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(f.orderId())).isEqualTo("REFUNDED");

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a2())).getStatusCode().value()).isEqualTo(200);

        List<RefundCommand> calls = refundCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).idempotencyKey()).isNotEqualTo(calls.get(1).idempotencyKey());
        assertThat(reversals(f.orderId())).hasSize(2);
        ResponseEntity<Map> wholeOrder = rest.exchange("/admin/orders/" + f.orderId() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "CUSTOMER_REQUEST"), auth(f.adminToken())), Map.class);
        assertThat(wholeOrder.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void brandWithShippingProblem_canBeCancelled() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(reportProblem(f.brandBToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void duplicateIdsInOneRequest_countOnce() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a1())).getStatusCode().value())
                .isEqualTo(200);
        assertThat(refundCalls()).hasSize(1);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount"));
    }

    // ===== rejections — nothing written, no Mollie call =====

    @Test
    void shippedBrand_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(409);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void unpaidOrder_isRejected_andStockUntouched() {
        Fixture f = unpaidTwoBrandOrder(null);
        ResponseEntity<Map> resp = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(message(resp)).contains("whole-order cancel");
        assertThat(stockOfItem(f.a1())).isEqualTo(5);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void legacyRowWithoutPaidAmount_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        jdbc.update("UPDATE order_items SET customer_gross_after_discount = NULL WHERE id = ?", f.a1());
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(409);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void mixedBrands_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.b1())).getStatusCode().value())
                .isEqualTo(400);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void itemOfAnotherOrder_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        long listing = jdbc.queryForObject("SELECT listing_id_snapshot FROM order_items WHERE id = ?", Long.class, f.a1());
        long otherOrder = orderId(postOrder(f.customerToken(), null, List.of(item(listing, 1))));
        long foreignItem = itemIdOf(otherOrder, listing);

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(foreignItem)).getStatusCode().value()).isEqualTo(400);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void alreadyCancelledItem_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> again = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(message(again)).contains("already cancelled");
        assertThat(refundCalls()).hasSize(1);
    }

    @Test
    void invalidBody_isRejectedBeforeAnything() {
        Fixture f = paidTwoBrandOrder(null);
        Map<String, Object> noReason = new HashMap<>();
        noReason.put("orderItemIds", List.of(f.a1()));
        ResponseEntity<Map> missingReason = rest.exchange("/admin/orders/" + f.orderId() + "/cancel-items",
                HttpMethod.POST, new HttpEntity<>(noReason, auth(f.adminToken())), Map.class);
        ResponseEntity<Map> emptyList = rest.exchange("/admin/orders/" + f.orderId() + "/cancel-items",
                HttpMethod.POST, new HttpEntity<>(Map.of("orderItemIds", List.of(), "reason", "OTHER"),
                        auth(f.adminToken())), Map.class);

        assertThat(missingReason.getStatusCode().value()).isEqualTo(400);
        assertThat(emptyList.getStatusCode().value()).isEqualTo(400);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        verify(paymentProvider, never()).refundPayment(any());
    }
}
