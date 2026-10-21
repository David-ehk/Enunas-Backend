package com.enunas.backend.order.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartialReturnRequestIntegrationTest extends AbstractReturnRefundIntegrationTest {

    @Test
    void returnsOneOfTwoUnits() {
        ReturnFixture f = deliveredOrder(null);
        assertThat(requestReturn(f.customerToken(), f.orderId(), f.a1(), 1).getStatusCode().value()).isEqualTo(200);
        assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandAId()), f.a1())).isEqualTo(1);
    }

    @Test
    void secondRequestWhileRequestedMergesIntoTheSameItem() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        String rn = returnNumberOf(f.orderId(), f.brandAId());
        assertThat(quantityOnReturn(rn, f.a1())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM return_items WHERE order_item_id = ?", Integer.class, f.a1()))
                .isEqualTo(1);
    }

    @Test
    void moreThanRemainingIs400() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        assertThat(requestReturn(f.customerToken(), f.orderId(), f.a1(), 2).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void wholeOrderRequestTakesOnlyRemainingUnitsAndSkipsFullyReturnedLines() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        requestReturn(f.customerToken(), f.orderId(), f.a2(), null);           // a2 fully on return
        assertThat(requestReturn(f.customerToken(), f.orderId(), null, null).getStatusCode().value()).isEqualTo(200);
        String rnA = returnNumberOf(f.orderId(), f.brandAId());
        assertThat(quantityOnReturn(rnA, f.a1())).isEqualTo(2);
        assertThat(quantityOnReturn(rnA, f.a2())).isEqualTo(1);
        assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandBId()), f.b1())).isEqualTo(1);
    }

    @Test
    void singleLineWithNothingRemainingIs409() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a2(), null);
        assertThat(requestReturn(f.customerToken(), f.orderId(), f.a2(), null).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void quantityOmittedMeansAllRemainingUnitsOfTheLine() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), null);
        assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandAId()), f.a1())).isEqualTo(2);
    }
}
