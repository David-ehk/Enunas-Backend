package com.enunas.backend.order.integration;

import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class ShippingWithCancelledItemsIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @Autowired TransactionTemplate transactionTemplate;

    private int shipmentRows(long orderId, long brandId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_shipments WHERE order_id = ? AND brand_partner_id = ?",
                Integer.class, orderId, brandId);
    }

    @Test
    void emptiedBrand_cannotConfirmShipment() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(ship(f.brandBToken(), f.orderId()).getStatusCode().value()).isEqualTo(409);
        assertThat(shipmentRows(f.orderId(), f.brandBId())).isZero();
    }

    @Test
    void emptiedBrand_cannotReportAShippingProblem() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(reportProblem(f.brandBToken(), f.orderId()).getStatusCode().value()).isEqualTo(409);
        assertThat(shipmentRows(f.orderId(), f.brandBId())).isZero();
    }

    @Test
    void orderReachesShipped_whenEveryActiveBrandShipped() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderStatus(f.orderId())).isEqualTo("SHIPPED");
    }

    @Test
    void adminBulkShip_neverForceShipsAnEmptiedBrand() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(adminSetStatus(f.adminToken(), f.orderId(), "SHIPPED").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(shipmentRows(f.orderId(), f.brandBId())).isZero();
        assertThat(shipmentRows(f.orderId(), f.brandAId())).isEqualTo(1);
    }

    @Test
    void dispatchEmail_listsOnlyItemsStillActive() {
        Fixture f = paidTwoBrandOrder(null);
        claimViaJdbc(f.a1()); // claimed, not settled — already withdrawn from fulfilment

        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendHtmlEmail(eq("customer@it.local"), anyString(), html.capture());
        List<String> mails = html.getAllValues();
        String dispatchMail = mails.get(mails.size() - 1); // sent last, after the order confirmation
        assertThat(dispatchMail).contains(productName(f.a2())).doesNotContain(productName(f.a1()));
    }

    /**
     * D23: a claim committed while confirmShipment waits for the order lock must be visible to it.
     * Without the lock, confirmShipment reads the items first and ships items being refunded.
     */
    @Test
    void confirmShipment_seesAClaimCommittedWhileItWaitedForTheOrderLock() throws Exception {
        Fixture f = paidTwoBrandOrder(null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<ResponseEntity<Map>>> shipping = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            orderRepository.findByIdForUpdate(f.orderId()).orElseThrow();
            shipping.set(pool.submit(() -> ship(f.brandAToken(), f.orderId())));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            claimViaJdbc(f.a1(), f.a2(), f.a3()); // commits together with this transaction
        });

        ResponseEntity<Map> resp = shipping.get().get(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(resp.getStatusCode().value()).as("ship: %s", resp.getBody()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipments WHERE order_id = ? "
                + "AND brand_partner_id = ? AND status = 'SHIPPED'", Integer.class, f.orderId(), f.brandAId())).isZero();
    }
}
