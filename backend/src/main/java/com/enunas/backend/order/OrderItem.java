package com.enunas.backend.order;

import com.enunas.backend.common.MoneyMath;
import com.enunas.backend.product.productvariant.ProductVariant;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Line item in an Order. Owns its own price/variant snapshot — the variant FK is the only
 * link back into the catalog. Listings can be deleted without affecting historical orders.
 *
 * Ownership path: OrderItem → ProductVariant → Product → Brand
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    // --- Purchase snapshot (immutable, prevents future changes from affecting history) ---
    @Column(nullable = false)
    private String productSnapshotName;

    @Column(nullable = false)
    private String variantSnapshotSku;

    @Column(nullable = false)
    private String variantSnapshotColor;

    @Column(nullable = false)
    private String variantSnapshotSize;

    private String brandSnapshotName;

    // --- Price snapshot at purchase time ---
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceAtPurchase;      // Final price paid (after discount)

    @Column(precision = 10, scale = 2)
    private BigDecimal discountPriceAtPurchase; // Original discount if any

    @Column(nullable = false)
    private Integer quantity;

    // --- Calculated fields ---
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;  // back-compat alias of lineGross (set before save)

    // --- Commission snapshot (set at order creation time) ---
    @Column(precision = 5, scale = 4)
    private BigDecimal commissionRate;

    // Legacy gross-basis fields, kept populated so nothing downstream NPEs. platformFeeAmount now
    // mirrors commissionGross and brandPayoutAmount mirrors brandPayout; new code reads the
    // explicit net/VAT fields below.
    @Column(precision = 10, scale = 2)
    private BigDecimal platformFeeAmount;

    @Column(precision = 10, scale = 2)
    private BigDecimal brandPayoutAmount;

    // --- Net + VAT money snapshot (§4; frozen at order creation, read-only thereafter) ---
    @Column(precision = 5, scale = 4)
    private BigDecimal vatRateProduct;

    @Column(precision = 5, scale = 4)
    private BigDecimal vatRateService;

    @Column(precision = 10, scale = 2)
    private BigDecimal lineNet;

    @Column(precision = 10, scale = 2)
    private BigDecimal lineVat;

    @Column(precision = 10, scale = 2)
    private BigDecimal lineGross;

    @Column(precision = 10, scale = 2)
    private BigDecimal baseCommissionNet;     // lineNet × rate, pre-discount

    @Column(precision = 10, scale = 2)
    private BigDecimal commissionNet;         // platform revenue (final)

    @Column(precision = 10, scale = 2)
    private BigDecimal commissionVat;         // 0 under reverse charge

    @Column(precision = 10, scale = 2)
    private BigDecimal commissionGross;       // commissionNet + commissionVat

    @Column(precision = 10, scale = 2)
    private BigDecimal customerGrossAfterDiscount; // what the customer actually pays for this line

    @Column(precision = 10, scale = 2)
    private BigDecimal brandPayout;           // cash transferred to the brand

    @Column(precision = 10, scale = 2)
    private BigDecimal brandNetRevenue;       // brand economic margin (reporting)

    // Boolean wrappers (not primitive): pre-V5 rows hold NULL here, and a primitive would throw
    // when Hibernate materializes such a legacy row.
    private Boolean brandIsDomestic;

    private Boolean reverseCharge;

    // --- Discount snapshot (set at order creation; zero when no code applied) — NET shares ---
    @Column(precision = 10, scale = 2)
    private BigDecimal itemDiscountAmount;     // platformDiscountShare + brandDiscountShare (net)

    @Column(precision = 10, scale = 2)
    private BigDecimal platformDiscountShare;  // net portion of the discount Enunas absorbs

    @Column(precision = 10, scale = 2)
    private BigDecimal brandDiscountShare;     // net portion of the discount the brand absorbs

    // Convenience for ownership (no DB column - transient)
    public Long getBrandId() {
        var brand = variant.getProduct().getBrand();
        return brand != null ? brand.getId() : null;
    }

    /** Pre-discount pass — sets lineNet/baseCommissionNet so the discount guard can read them. */
    public void applyMoneySnapshot(BigDecimal rate, boolean brandIsDomestic,
                                   BigDecimal vatProduct, BigDecimal vatService) {
        applyMoneySnapshot(rate, brandIsDomestic, vatProduct, vatService,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * The single place the canonical net/VAT money formula lives (spec §4). Net is the source of
     * truth: commission is a percentage of {@code lineNet}; VAT is a pass-through added only on the
     * commission for domestic brands (reverse charge ⇒ 0). All persisted figures round HALF_UP/2dp.
     *
     * Discount shares (§5) are NET amounts already split by {@code DiscountService}; {@code percent}
     * scales the customer's gross. The ledger reads {@code commissionNet}/{@code commissionVat}/
     * {@code brandPayout} directly, so all money math lands here and nowhere else.
     */
    public void applyMoneySnapshot(BigDecimal rate, boolean brandIsDomestic,
                                   BigDecimal vatProduct, BigDecimal vatService,
                                   BigDecimal platformShareNet, BigDecimal brandShareNet,
                                   BigDecimal percent) {
        if (rate == null || this.lineGross == null) return;
        BigDecimal pShare = nz(platformShareNet);
        BigDecimal bShare = nz(brandShareNet);
        BigDecimal pct    = nz(percent);

        this.commissionRate  = rate;
        this.brandIsDomestic = brandIsDomestic;
        this.reverseCharge   = !brandIsDomestic;
        this.vatRateProduct  = vatProduct;
        this.vatRateService  = vatService;

        this.lineNet           = MoneyMath.netFromGross(lineGross, vatProduct);
        this.lineVat           = lineGross.subtract(lineNet);
        this.baseCommissionNet = MoneyMath.round2(lineNet.multiply(rate));

        this.commissionNet   = baseCommissionNet.subtract(pShare);
        this.commissionVat   = brandIsDomestic
                ? MoneyMath.round2(commissionNet.multiply(vatService))
                : BigDecimal.ZERO.setScale(2);
        this.commissionGross = commissionNet.add(commissionVat);

        this.customerGrossAfterDiscount =
                MoneyMath.round2(lineGross.multiply(BigDecimal.ONE.subtract(pct)));
        this.brandNetRevenue = lineNet.subtract(baseCommissionNet).subtract(bShare);
        this.brandPayout     = customerGrossAfterDiscount.subtract(commissionGross);

        this.platformDiscountShare = pShare;
        this.brandDiscountShare    = bShare;
        this.itemDiscountAmount    = pShare.add(bShare);

        // Legacy gross-basis mirrors (kept non-null for any old reader).
        this.lineTotal         = lineGross;
        this.platformFeeAmount = commissionGross;
        this.brandPayoutAmount = brandPayout;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    // Helper to calculate line total
    public void calculateLineTotal() {
        if (priceAtPurchase != null && quantity != null) {
            this.lineTotal = priceAtPurchase.multiply(BigDecimal.valueOf(quantity));
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderItem other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}