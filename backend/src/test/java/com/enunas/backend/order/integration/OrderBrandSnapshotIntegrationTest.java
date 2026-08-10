package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for a silent bug: OrderItem.brandSnapshotName was declared on the entity and read
 * from in two places (OrderService.mapShippingSnapshots' brand-name lookup, and an
 * OrderItemRepository aggregate query) but never actually assigned anywhere at order creation. Every
 * order's shippingSnapshots[].brandName came back null — rendering as "Versand — " with nothing after
 * the dash on order confirmation and account order history — even though the checkout preview showed
 * the brand name correctly, because that's a separate code path reading BrandPartner directly rather
 * than this stored snapshot. Nothing tested that order creation actually populated the field the rest
 * of the codebase already depended on.
 */
class OrderBrandSnapshotIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void createOrder_stampsBrandSnapshotNameOnEachItem() {
        seedCustomer();
        BrandFixture s2 = seedBrand("S2", "s2", "0.18");
        long listing = seedListing(s2.brand(), s2.user(), "49.95", 10);
        String token = login("customer@it.local", "Customer123!");

        long orderId = orderId(postOrder(token, null, List.of(item(listing, 1))));

        List<Map<String, Object>> items = orderItemRows(orderId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("brand_snapshot_name")).isEqualTo("S2");
    }
}
