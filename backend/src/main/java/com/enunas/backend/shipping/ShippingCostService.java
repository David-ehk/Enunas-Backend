package com.enunas.backend.shipping;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.order.ShippingAddress;

import java.util.List;

/**
 * Calculates shipping cost per brand for a cart. {@code destination} and {@code brandItems} are
 * part of the contract now so future country/weight/express implementations never need a
 * signature change — {@link FlatRateShippingCostService} (V1) ignores both and prices purely
 * from the brand.
 */
public interface ShippingCostService {

    ShippingCostResult calculate(BrandPartner brand, ShippingAddress destination, List<OrderItem> brandItems);
}
