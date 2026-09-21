package com.enunas.backend.payment.mock;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockRefundIdempotencyTest extends AbstractDiscountIntegrationTest {

    @Autowired PaymentProvider paymentProvider;

    @Test
    void sameKeyReturnsSameRefundId() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String token = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(token, null, List.of(item(listing, 1))));
        String txId = (String) jdbc.queryForMap("SELECT transaction_id FROM payments WHERE order_id = ?", oid)
                .get("transaction_id");

        String first = paymentProvider.refundPayment(
                new RefundCommand(txId, new BigDecimal("123.99"), "test", "order-cancel-X")).refundId();
        String second = paymentProvider.refundPayment(
                new RefundCommand(txId, new BigDecimal("123.99"), "test", "order-cancel-X")).refundId();

        assertThat(second).isEqualTo(first);
    }
}
