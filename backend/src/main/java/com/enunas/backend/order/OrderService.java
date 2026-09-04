package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.customer.UserAddress;
import com.enunas.backend.customer.UserAddressRepository;
import com.enunas.backend.exception.AddressNotFoundException;
import com.enunas.backend.exception.OrderNotFoundException;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
import com.enunas.backend.order.dto.OrderPreviewResponseDto;
import com.enunas.backend.order.dto.OrderItemResponseDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.OrderShipmentDto;
import com.enunas.backend.order.dto.ReturnRequestDto;
import com.enunas.backend.order.dto.ReturnSummaryDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingAddressDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
import com.enunas.backend.order.dto.ShippingSnapshotDto;
import com.enunas.backend.order.dto.UploadReturnLabelDto;
import com.enunas.backend.order.validation.AllowedShippingCountries;
import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.payment.CreatePaymentCommand;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentResult;
import com.enunas.backend.payment.PaymentStatus;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.discount.DiscountApplication;
import com.enunas.backend.discount.DiscountService;
import com.enunas.backend.shipping.ShippingCostResult;
import com.enunas.backend.shipping.ShippingCostService;
import com.enunas.backend.user.User;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ORDER_NUM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductListingRepository productListingRepository;
    private final ProductVariantRepository productVariantRepository;
    private final PaymentRepository paymentRepository;
    private final BrandEconomicsRepository brandEconomicsRepository;
    private final BrandPartnerRepository brandPartnerRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final PaymentProvider paymentProvider;
    private final LedgerService ledgerService;
    private final RefundPersistenceHelper refundPersistenceHelper;
    private final DiscountService discountService;
    private final ApplicationEventPublisher eventPublisher;
    private final ReturnAddressSnapshotFactory returnAddressSnapshotFactory;
    private final UserAddressRepository userAddressRepository;
    private final Validator validator;
    private final ShippingCostService shippingCostService;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${enunas.platform.commission-rate:0.18}")
    private BigDecimal globalCommissionRate;

    @Value("${enunas.vat.product-rate:0.19}")
    private BigDecimal vatRateProduct;

    @Value("${enunas.vat.service-rate:0.19}")
    private BigDecimal vatRateService;

    // ===== Customer =====

    /**
     * Places an order and opens a payment for it, in three steps with a hard rule between them:
     * <b>our database is always ahead of the provider</b>.
     *
     * <ol>
     *   <li>Everything that touches our own tables — pricing, the discount-usage reservation, the
     *       order, its items, its shipping snapshots and a PENDING payment row — in one
     *       transaction that COMMITS before anything leaves the process.</li>
     *   <li>The Mollie call, holding no transaction and no database connection.</li>
     *   <li>A second, single-row transaction attaching the provider's payment id.</li>
     * </ol>
     *
     * <p>This method is deliberately NOT {@code @Transactional}. It used to be, and that was the
     * bug: the Mollie call sat inside the same transaction as every write above it, so any failure
     * after it — the payment-row insert, a constraint, a connection drop at commit — rolled the
     * order away and left a live payment at Mollie belonging to an order that no longer existed,
     * recoverable only by hand. Committing first inverts the failure: a payment can no longer
     * outlive its order, because the order is already durable when the payment is created.
     *
     * <p>What each step now costs when it fails:
     * <ul>
     *   <li><b>Step 1</b> — nothing external has happened. The transaction rolls back whole,
     *       including the discount usage it reserved. Unchanged from before.</li>
     *   <li><b>Step 2</b> — no Mollie payment exists (or one does, and we never learned its id,
     *       which for an unpaid payment is the same thing: it expires there untouched). The order
     *       stays PENDING and {@link OrderExpiryService} cancels it within 30 minutes, releasing
     *       the discount usage on the way out. Deliberately NOT cancelled inline here: if the call
     *       actually reached Mollie and only the response was lost, destroying our side of it
     *       immediately is the one move that could still strand a real payment.</li>
     *   <li><b>Step 3</b> — a Mollie payment exists that our webhook cannot resolve by
     *       transaction id. It is unreachable rather than orphaned: this method throws, so the
     *       checkout URL never reaches the customer, nobody can pay it, and it expires at Mollie.
     *       The order is cleaned up by expiry exactly as in step 2. The PAYMENT_LINK_FAILURE log
     *       below carries the order number and payment id, which is all a human needs to attach it
     *       by hand if one ever does need rescuing.</li>
     * </ul>
     */
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponseDto createOrder(CreateOrderDto dto, User buyer) {
        PendingOrder pending = transactionTemplate.execute(status -> persistPendingOrder(dto, buyer));

        Order saved = pending.order();
        PaymentResult paymentResult;
        try {
            paymentResult = paymentProvider.createPayment(new CreatePaymentCommand(
                    saved.getTotal(),
                    saved.getCurrency(),
                    "Enunas order " + saved.getOrderNumber(),
                    orderLink(saved)));
        } catch (Exception e) {
            log.error("Payment creation failed for order {} (left PENDING for the expiry job): {}",
                    saved.getOrderNumber(), e.getMessage());
            throw new PaymentException("Could not initiate payment. Please try again.", e);
        }

        try {
            transactionTemplate.executeWithoutResult(status ->
                    attachProviderPayment(pending.paymentId(), paymentResult.paymentId()));
        } catch (Exception e) {
            // PAYMENT_LINK_FAILURE — stable marker for a log-based alert, same convention as
            // EMAIL_DELIVERY_FAILURE. Everything needed to link the two by hand is on this line.
            log.error("PAYMENT_LINK_FAILURE order={} paymentId={} reason={}",
                    saved.getOrderNumber(), paymentResult.paymentId(), e.getMessage());
            throw new PaymentException("Could not initiate payment. Please try again.", e);
        }

        log.info("Order created: {} for buyer: {} (brands: {}, paymentId: {})",
                saved.getOrderNumber(), buyer.getEmail(), pending.shippingSnapshots().size(),
                paymentResult.paymentId());

        return OrderResponseDto.from(saved, paymentResult.checkoutUrl())
                .toBuilder()
                .shippingSnapshots(mapShippingSnapshots(saved, pending.shippingSnapshots()))
                // Straight off the just-created payment; no lookup needed on this path.
                .molliePaymentId(paymentResult.paymentId())
                .build();
    }

    /** What step 1 of {@link #createOrder} leaves committed, carried into steps 2 and 3. */
    private record PendingOrder(Order order, List<OrderShippingSnapshot> shippingSnapshots, Long paymentId) {}

    /**
     * Step 1 of {@link #createOrder}: every write this order needs on our side, including the
     * payment row, which is inserted PENDING with no transaction id because there is nothing to
     * put there yet — step 3 fills it in. Runs inside the caller's {@code transactionTemplate}
     * rather than carrying its own {@code @Transactional}, since a self-call could not be
     * intercepted by the proxy anyway.
     */
    private PendingOrder persistPendingOrder(CreateOrderDto dto, User buyer) {
        OrderPricingDraft draft = buildPricingDraft(dto, buyer, true);

        ShippingAddressDto resolvedAddress = draft.resolvedAddress();
        ShippingAddress address = ShippingAddress.builder()
                .firstName(resolvedAddress.getFirstName())
                .lastName(resolvedAddress.getLastName())
                .street(resolvedAddress.getStreet())
                .houseNumber(resolvedAddress.getHouseNumber())
                .addressLine2(resolvedAddress.getAddressLine2())
                .city(resolvedAddress.getCity())
                .postalCode(resolvedAddress.getPostalCode())
                .country(resolvedAddress.getCountry())
                .phone(resolvedAddress.getPhone())
                .build();

        Order.OrderBuilder orderBuilder = Order.builder()
                .orderNumber(generateOrderNumber())
                .buyer(buyer)
                .status(OrderStatus.PENDING)
                .shippingAddress(address)
                .subtotal(draft.subtotal())
                .shippingTotal(draft.shippingTotal())
                .currency(draft.currency())
                .notes(dto.getNotes());

        if (draft.discount() != null) {
            orderBuilder
                    .discountCode(draft.discount().code().getCode())
                    .discountType(draft.discount().type())
                    .discountPercent(draft.discount().percent())
                    .discountAmount(draft.discountAmount())
                    .platformDiscountAmount(draft.discount().platformDiscountAmount())
                    .brandDiscountAmount(draft.discount().brandDiscountAmount());
        }

        Order order = orderBuilder.build();
        // total = subtotal - discountAmount + shippingTotal. This is what Mollie charges and the
        // webhook verifies — see Order.computeTotal().
        order.setTotal(order.computeTotal());

        Order saved = orderRepository.save(order);
        draft.orderItems().forEach(saved::addItem);
        orderItemRepository.saveAll(draft.orderItems());

        List<OrderShippingSnapshot> snapshots = draft.shippingLines().stream()
                .map(line -> OrderShippingSnapshot.builder()
                        .orderId(saved.getId())
                        .brandPartnerId(line.brandId())
                        .amount(line.result().amount())
                        .currency(line.result().currency())
                        .calculationMethod(line.result().method())
                        .ruleVersion(line.result().ruleVersion())
                        .brandShippingProfileId(line.result().brandShippingProfileId())
                        .build())
                .toList();
        orderShippingSnapshotRepository.saveAll(snapshots);

        Payment payment = paymentRepository.save(Payment.builder()
                .order(saved)
                .amount(saved.getTotal())
                .currency(saved.getCurrency())
                .build());

        return new PendingOrder(saved, snapshots, payment.getId());
    }

    /**
     * Step 3 of {@link #createOrder}: links the committed payment row to the provider's payment.
     * This id is the ONLY thing {@link com.enunas.backend.payment.MollieWebhookController} has to
     * find the order by, so until it is written the payment cannot be settled — which is why it
     * gets its own short transaction immediately after the call returns, rather than riding along
     * with anything slower.
     */
    private void attachProviderPayment(Long paymentId, String providerPaymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalStateException("Payment row " + paymentId + " vanished"));
        payment.setTransactionId(providerPaymentId);
        paymentRepository.save(payment);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public OrderPreviewResponseDto previewOrder(CreateOrderDto dto, User buyer) {
        return OrderPreviewResponseDto.from(buildPricingDraft(dto, buyer, false));
    }

    /**
     * Prices a cart end to end — listing resolution, stock check, per-item money snapshot,
     * optional discount application, and per-brand shipping — without persisting anything. Shared
     * by {@link #createOrder} and {@link #previewOrder} so the checkout preview and the actual
     * Mollie charge can never drift.
     *
     * @param applyDiscount controls ONLY whether the code's usage is reserved, not whether the
     *                       discount is computed. Both paths compute the identical discount split,
     *                       so the preview quotes exactly what checkout will charge. When
     *                       {@code true} (real order creation) the code goes through
     *                       {@link com.enunas.backend.discount.DiscountService#validateAndApply},
     *                       which reserves one usage atomically. When {@code false} (checkout
     *                       preview) it goes through the side-effect-free
     *                       {@link com.enunas.backend.discount.DiscountService#validateAndCompute},
     *                       so a repeatable, non-committal preview never burns a usage.
     */
    private OrderPricingDraft buildPricingDraft(CreateOrderDto dto, User buyer, boolean applyDiscount) {
        List<ProductListing> listings = resolveAndValidateListings(dto.getItems());

        for (int i = 0; i < dto.getItems().size(); i++) {
            OrderItemRequestDto itemDto = dto.getItems().get(i);
            ProductVariant variant = listings.get(i).getVariant();
            if (!variant.hasStock(itemDto.getQuantity())) {
                throw new IllegalStateException(
                        "Insufficient stock for: " + listings.get(i).getProduct().getName() +
                        " (" + variant.getColor() + " / " + variant.getSize() + ")" +
                        " — requested: " + itemDto.getQuantity() +
                        ", available: " + variant.getStockQuantity());
            }
        }

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        // brandId -> brand, and brandId -> that brand's OrderItems, in first-seen order — both
        // needed to make one ShippingCostService call per brand after this loop.
        Map<Long, BrandPartner> brandsById = new LinkedHashMap<>();
        Map<Long, List<OrderItem>> itemsByBrand = new LinkedHashMap<>();

        for (int i = 0; i < dto.getItems().size(); i++) {
            OrderItemRequestDto itemDto = dto.getItems().get(i);
            ProductListing pl = listings.get(i);
            ProductVariant variant = pl.getVariant();

            // lineGross is the customer-facing gross (sale gross if on sale, else regular gross).
            BigDecimal effectiveGross = pl.getEffectiveGross();
            BigDecimal lineGross = effectiveGross.multiply(BigDecimal.valueOf(itemDto.getQuantity()));
            subtotal = subtotal.add(lineGross);

            BrandPartner brand = pl.getProduct().getBrand();
            boolean domestic = (brand == null) || brand.isDomestic();
            BigDecimal rate = brand != null ? getBrandCommissionRate(brand.getId()) : BigDecimal.ZERO;

            OrderItem item = OrderItem.builder()
                    .variant(variant)
                    .listingIdSnapshot(pl.getId())
                    .productSnapshotName(pl.getProduct().getName())
                    .brandSnapshotName(brand != null ? brand.getBrandName() : null)
                    .variantSnapshotSku(variant.getSku())
                    .variantSnapshotColor(variant.getColor())
                    .variantSnapshotSize(variant.getSize())
                    .priceAtPurchase(pl.getPrice())
                    .discountPriceAtPurchase(pl.getDiscountPrice())
                    .quantity(itemDto.getQuantity())
                    .lineGross(lineGross)
                    .lineTotal(lineGross)
                    .build();
            // Pre-discount pass: freezes lineNet/baseCommissionNet so the discount guard can read them.
            item.applyMoneySnapshot(rate, domestic, vatRateProduct, vatRateService);
            orderItems.add(item);

            if (brand != null) {
                brandsById.putIfAbsent(brand.getId(), brand);
                itemsByBrand.computeIfAbsent(brand.getId(), k -> new ArrayList<>()).add(item);
            }
        }

        // Apply optional discount code (max one per order — no stacking). Re-runs the money
        // snapshot per item with the NET discount shares folded in, so the ledger (which reads
        // commissionNet/commissionVat/brandPayout) stays correct per brand. The discount is ALWAYS
        // computed so preview and checkout quote the same total; only the usage reservation is
        // gated on applyDiscount — see this method's javadoc.
        DiscountApplication discount = null;
        if (dto.getDiscountCode() != null && !dto.getDiscountCode().isBlank()) {
            discount = applyDiscount
                    ? discountService.validateAndApply(dto.getDiscountCode(), orderItems)
                    : discountService.validateAndCompute(dto.getDiscountCode(), orderItems);
            for (int i = 0; i < orderItems.size(); i++) {
                OrderItem item = orderItems.get(i);
                DiscountApplication.ItemShare share = discount.itemShares().get(i);
                BigDecimal itemPercent = share.total().signum() > 0 ? discount.percent() : BigDecimal.ZERO;
                item.applyMoneySnapshot(item.getCommissionRate(), Boolean.TRUE.equals(item.getBrandIsDomestic()),
                        vatRateProduct, vatRateService,
                        share.platformShareNet(), share.brandShareNet(), itemPercent);
            }
        }

        // Customer-facing reduction is GROSS (= Σ lineGross − Σ customerGrossAfterDiscount); the
        // platform/brand discount aggregates on the Order are the NET absorption shares.
        BigDecimal customerSubtotalAfterDiscount = orderItems.stream()
                .map(OrderItem::getCustomerGrossAfterDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountAmount = subtotal.subtract(customerSubtotalAfterDiscount);

        ShippingAddressDto resolvedAddress = resolveShippingAddress(dto, buyer);
        ShippingAddress destination = ShippingAddress.builder()
                .firstName(resolvedAddress.getFirstName())
                .lastName(resolvedAddress.getLastName())
                .street(resolvedAddress.getStreet())
                .houseNumber(resolvedAddress.getHouseNumber())
                .addressLine2(resolvedAddress.getAddressLine2())
                .city(resolvedAddress.getCity())
                .postalCode(resolvedAddress.getPostalCode())
                .country(resolvedAddress.getCountry())
                .phone(resolvedAddress.getPhone())
                .build();

        // 5. Shipping — one ShippingCostService call per distinct brand on the cart.
        List<OrderPricingDraft.ShippingLine> shippingLines = new ArrayList<>();
        BigDecimal shippingTotal = BigDecimal.ZERO;
        for (Map.Entry<Long, BrandPartner> e : brandsById.entrySet()) {
            BrandPartner brand = e.getValue();
            List<OrderItem> brandItems = itemsByBrand.get(e.getKey());
            ShippingCostResult result = shippingCostService.calculate(brand, destination, brandItems);
            shippingLines.add(new OrderPricingDraft.ShippingLine(brand.getId(), brand.getBrandName(), result));
            shippingTotal = shippingTotal.add(result.amount());
        }

        // 6. total = subtotal − discountAmount + shippingTotal — this is what Mollie charges and
        //    the webhook trusts (see Order.total's own javadoc, which already documented this
        //    formula before shipping was wired up to stop being hardcoded zero).
        BigDecimal total = subtotal.subtract(discountAmount).add(shippingTotal);

        return new OrderPricingDraft(orderItems, subtotal, discount, discountAmount,
                shippingLines, shippingTotal, total, resolvedAddress,
                listings.get(0).getCurrency());
    }

    private List<ShippingSnapshotDto> mapShippingSnapshots(Order order, List<OrderShippingSnapshot> snapshots) {
        Map<Long, String> brandNames = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            if (item.getBrandId() != null) brandNames.putIfAbsent(item.getBrandId(), item.getBrandSnapshotName());
        }
        return snapshots.stream()
                .map(s -> ShippingSnapshotDto.from(s, brandNames.get(s.getBrandPartnerId())))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CUSTOMER')")
    public Page<OrderResponseDto> getMyOrders(User buyer, Pageable pageable) {
        Page<Order> page = orderRepository.findByBuyerOrderByCreatedAtDesc(buyer, pageable);
        OrderRelations relations = loadRelations(page.getContent());
        return page.map(order -> toDto(order, relations));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponseDto getMyOrderById(Long orderId, User buyer) {
        Order order = findById(orderId);
        assertOwnership(order, buyer);
        return toDto(order);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderResponseDto requestReturn(Long orderId, ReturnRequestDto dto, User buyer) {
        Order order = findById(orderId);
        assertOwnership(order, buyer);
        return doRequestReturn(order, dto, true);
    }

    /**
     * Admin-initiated goodwill return: same split/merge logic as the customer path, but bypasses
     * the 14-day Widerruf window. There is no ownership check — an admin can act on any order — and
     * the {@link ReturnOrder#getUser()} on whatever is created is still the order's buyer, since the
     * return is on their behalf, not the admin's.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto adminRequestReturn(Long orderId, ReturnRequestDto dto, User admin) {
        Order order = findById(orderId);
        log.info("Admin {} initiating a goodwill return (bypassing the 14-day window) for order {}",
                admin.getEmail(), order.getOrderNumber());
        return doRequestReturn(order, dto, false);
    }

    private OrderResponseDto doRequestReturn(Order order, ReturnRequestDto dto, boolean enforceReturnWindow) {
        User buyer = order.getBuyer();
        Long orderId = order.getId();

        // DELIVERED is the entry point, but an order already carrying a return must stay open to
        // further ones: returning brand A's item flips the order to RETURN_REQUESTED, and a
        // DELIVERED-only guard would then make brand B's item unreturnable forever. Which items
        // may still go back is decided per item below, not by the order-level status.
        if (order.getStatus() != OrderStatus.DELIVERED
                && order.getStatus() != OrderStatus.RETURN_REQUESTED
                && order.getStatus() != OrderStatus.RETURN_APPROVED
                && order.getStatus() != OrderStatus.RETURN_RECEIVED) {
            throw new IllegalStateException(
                    "Return can only be requested for DELIVERED orders. Current status: " + order.getStatus());
        }

        // 14-day Widerruf window (§355 BGB), anchored on delivered_at — NOT order creation date and
        // NOT carrier delivery, since delivered_at itself is admin-set (see Order.deliveredAt). This
        // is a simplified gate on the return REQUEST, not a full compliance calculation (e.g. it
        // does not special-case weekends/holidays) — confirm the edge cases with counsel before
        // relying on it as the sole enforcement. A null deliveredAt (should not happen going
        // forward; defensive only) is treated as no time limit rather than blocking everyone.
        if (enforceReturnWindow && order.getDeliveredAt() != null) {
            LocalDateTime deadline = order.getDeliveredAt().plusDays(14);
            if (LocalDateTime.now().isAfter(deadline)) {
                throw new IllegalStateException(
                        "The 14-day Widerruf window for this order expired on " + deadline
                                + ". Contact support about a goodwill return.");
            }
        }

        // Which items is the customer returning? (null orderItemId = the whole order.)
        List<OrderItem> requested;
        if (dto.orderItemId() == null) {
            requested = new ArrayList<>(order.getItems());
        } else {
            requested = List.of(order.getItems().stream()
                    .filter(i -> i.getId().equals(dto.orderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "OrderItem " + dto.orderItemId() + " does not belong to this order")));
        }

        // Guard per ITEM, not per order. The old order-wide guard meant returning one brand's item
        // permanently blocked returning another brand's item from the same order.
        Set<Long> alreadyReturned = new HashSet<>(returnOrderRepository.findReturnedOrderItemIds(orderId));
        List<OrderItem> toReturn = requested.stream()
                .filter(i -> !alreadyReturned.contains(i.getId()))
                .toList();
        if (toReturn.isEmpty()) {
            throw new IllegalStateException(
                    "All requested items on order " + order.getOrderNumber() + " have already been returned");
        }

        // Split by brand: goods physically travel to different places, so each brand gets its own
        // return with its own number, address snapshot, approval and refund.
        Map<Long, List<OrderItem>> itemsByBrand = new LinkedHashMap<>();
        for (OrderItem item : toReturn) {
            Long brandId = item.getBrandId();
            if (brandId == null) {
                throw new IllegalStateException(
                        "OrderItem " + item.getId() + " has no brand — cannot route a return for it");
            }
            itemsByBrand.computeIfAbsent(brandId, k -> new ArrayList<>()).add(item);
        }

        // At most one ACTIVE (non-REFUNDED) return may exist per (order, brand) — enforced here and
        // backed by the partial unique index in V15. A brand's items join its existing open return
        // while that return is still REQUESTED (the customer is still assembling what they're
        // sending back); once it has moved past REQUESTED, the physical/refund lifecycle for what
        // was already requested has started, so a new item for that brand must wait.
        List<ReturnOrder> touched = new ArrayList<>();
        List<String> blockedBrandNames = new ArrayList<>();

        for (Map.Entry<Long, List<OrderItem>> entry : itemsByBrand.entrySet()) {
            Long brandId = entry.getKey();
            List<OrderItem> brandItems = entry.getValue();
            BrandPartner brand = brandItems.get(0).getVariant().getProduct().getBrand();

            Optional<ReturnOrder> active = returnOrderRepository
                    .findFirstByOrder_IdAndBrand_IdAndStatusNot(orderId, brandId, ReturnStatus.REFUNDED);

            if (active.isPresent() && active.get().getStatus() != ReturnStatus.REQUESTED) {
                blockedBrandNames.add(brand != null ? brand.getBrandName() : ("brand " + brandId));
                log.warn("Return request for order {} skips brand {} — its existing return {} is already {}",
                        order.getOrderNumber(), brandId, active.get().getReturnNumber(), active.get().getStatus());
                continue;
            }

            ReturnOrder returnOrder;
            if (active.isPresent()) {
                // Merge into the existing REQUESTED return — same brand, same destination, one
                // return number. Snapshot/status/timestamps are untouched; only items are added.
                returnOrder = active.get();
            } else {
                returnOrder = returnOrderRepository.save(ReturnOrder.create(
                        generateReturnNumber(), order, buyer, brand, dto.reason(), dto.description()));
                // Snapshot the address NOW, so the customer sees it immediately (rather than only
                // after admin approval) and it stays fixed if the brand later moves warehouses.
                returnOrder.applyShipToSnapshot(
                        returnAddressSnapshotFactory.create(brand, returnOrder.getReturnNumber()));
            }

            for (OrderItem item : brandItems) {
                returnOrder.addItem(ReturnItem.builder()
                        .orderItem(item)
                        .quantityReturned(item.getQuantity())
                        .build());
            }
            touched.add(returnOrderRepository.save(returnOrder));
        }

        if (touched.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot request this return: " + String.join(", ", blockedBrandNames)
                            + " already have a return in progress for this order. Wait until it completes.");
        }

        // Same rule as every other transition: the order tracks its least-advanced return, which a
        // brand-new REQUESTED one now is.
        Order saved = orderRepository.save(syncOrderStatus(order));
        log.info("Return requested for order {} by {} — {} brand return(s) touched: {}{}",
                order.getOrderNumber(), buyer.getEmail(), touched.size(),
                touched.stream().map(ReturnOrder::getReturnNumber).toList(),
                blockedBrandNames.isEmpty() ? "" : " (blocked: " + blockedBrandNames + ")");
        return toDto(saved);
    }

    // ===== BrandPartner =====

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public Page<OrderResponseDto> getMyBrandOrders(User brandPartner, Pageable pageable) {
        Page<Order> page = orderRepository.findByBrandPartnerCreatorId(brandPartner.getId(), pageable);
        OrderRelations relations = loadRelations(page.getContent());
        return page.map(order -> toBrandScopedDto(order, brandPartner, relations));
    }

    /**
     * Per-brand shipment confirmation (see {@link OrderShipment}). Used to flip the WHOLE order to
     * SHIPPED — corrupting every other brand's still-pending shipment on the same multi-brand
     * order. Now: finds-or-creates this brand's own {@link OrderShipment} row and recomputes
     * {@link Order#getStatus()} as an honest rollup ({@link #syncShipmentStatus}) — never touches
     * another brand's data.
     */
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public OrderResponseDto confirmShipment(Long orderId, ShipmentConfirmationDto dto, User brandPartner) {
        Order order = findById(orderId);
        BrandPartner brand = resolveOwnBrand(order, brandPartner);

        OrderShipment shipment = resolveOrCreateShipment(order, brand);
        if (shipment.getStatus() == ShipmentStatus.SHIPPED) {
            throw new IllegalStateException(
                    "Your items on order " + order.getOrderNumber() + " were already marked shipped.");
        }
        assertInShippingWindow(order, "Shipment can only be confirmed");
        shipment.setCarrier(dto.getCarrier());
        shipment.setTrackingNumber(dto.getTrackingNumber());
        shipment.setShippedAt(LocalDateTime.now());
        shipment.setStatus(ShipmentStatus.SHIPPED);
        orderShipmentRepository.save(shipment);

        Order saved = orderRepository.save(syncShipmentStatus(order));

        // Best-effort shipment-notification email, dispatched AFTER_COMMIT — never blocks/rolls
        // back the already-committed shipment record.
        publishShipmentConfirmed(order, brand, dto.getCarrier(), dto.getTrackingNumber(), dto.getNote());

        log.info("BrandPartner {} confirmed shipment for their items on order {} via {}",
                brandPartner.getEmail(), order.getOrderNumber(), dto.getCarrier());
        return toBrandScopedDto(saved, brandPartner, loadRelations(List.of(saved)));
    }

    /**
     * Per-brand shipping-problem report — same fix as {@link #confirmShipment}: no longer flips the
     * whole order to SHIPPING_PROBLEM (which would also have blocked every OTHER brand's
     * confirmShipment, since that used to require the whole order still be PAID). Sets this brand's
     * own {@link OrderShipment} row to PROBLEM and {@link Order#isShippingProblem()} for admin
     * visibility — {@link Order#getStatus()} itself stays the honest per-brand rollup, never faked
     * into SHIPPING_PROBLEM for brands whose own shipment is unaffected.
     */
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public OrderResponseDto reportShippingProblem(Long orderId, ShippingProblemDto dto, User brandPartner) {
        Order order = findById(orderId);
        BrandPartner brand = resolveOwnBrand(order, brandPartner);

        OrderShipment shipment = resolveOrCreateShipment(order, brand);
        if (shipment.getStatus() == ShipmentStatus.SHIPPED) {
            throw new IllegalStateException(
                    "Your items on order " + order.getOrderNumber() + " were already shipped — cannot report a problem.");
        }
        assertInShippingWindow(order, "Shipping problems can only be reported");
        shipment.setStatus(ShipmentStatus.PROBLEM);
        shipment.setProblemDescription(dto.getDescription());
        shipment.setProblemReportedAt(LocalDateTime.now());
        shipment.setProblemReportedBy(brandPartner.getEmail());
        orderShipmentRepository.save(shipment);

        order.setShippingProblem(true);
        Order saved = orderRepository.save(syncShipmentStatus(order));

        log.warn("BrandPartner {} reported a shipping problem for their items on order {}: {}",
                brandPartner.getEmail(), order.getOrderNumber(), dto.getDescription());
        return toBrandScopedDto(saved, brandPartner, loadRelations(List.of(saved)));
    }

    /**
     * Tells the buyer that one brand's parcel is on its way. Every path that moves an
     * {@link OrderShipment} to SHIPPED publishes through here — the brand's own
     * {@link #confirmShipment} and the admin's order-wide override in
     * {@link #bulkMarkAllBrandsShipped} — so whether the customer hears about a dispatch no longer
     * depends on which of the two recorded it.
     *
     * <p>{@code carrier}/{@code trackingNumber} are null on the admin path, which has neither to
     * offer; {@link ShipmentConfirmedEmailListener} renders that case explicitly instead of
     * printing "null" at the customer.
     */
    private void publishShipmentConfirmed(Order order, BrandPartner brand,
                                          String carrier, String trackingNumber, String note) {
        eventPublisher.publishEvent(new ShipmentConfirmedEvent(
                order.getBuyer().getEmail(),
                order.getOrderNumber(),
                brand.getBrandName(),
                carrier,
                trackingNumber,
                note,
                // Only THIS brand's articles: the mail describes one parcel, and listing the whole
                // order would tell the customer that items still sitting at another brand are on
                // their way.
                order.getItems().stream()
                        .filter(item -> brand.getId().equals(item.getBrandId()))
                        .map(item -> new OrderItemLine(item.getProductSnapshotName(),
                                item.getVariantSnapshotColor(), item.getVariantSnapshotSize(),
                                item.getQuantity(), null))
                        .toList(),
                orderLink(order)));
    }

    /** The order-detail page the customer is sent to from every mail about this order. */
    private String orderLink(Order order) {
        return frontendBaseUrl + "/orders/" + order.getOrderNumber() + "/confirmation";
    }

    // ===== Payment webhook (no role check — called server-to-server after amount verified) =====

    @Transactional
    public void confirmPaymentByWebhook(Long orderId) {
        // Pessimistic lock serializes concurrent duplicate webhooks: the second caller blocks here
        // until the first commits, then reads status = PAID and no-ops below. Without this, two
        // webhooks could both read PENDING and both book the ledger → double payout.
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));
        OrderStatus current = order.getStatus();

        if (current == OrderStatus.PAID) return; // idempotent

        if (current != OrderStatus.PENDING) {
            log.warn("Webhook: order {} in {} state, expected PENDING — ignoring", orderId, current);
            return;
        }

        // Mark payment captured regardless of stock outcome — money was taken by Mollie.
        paymentRepository.findByOrderId(orderId).ifPresent(p -> {
            p.setStatus(PaymentStatus.PAID);
            p.setPaidAt(LocalDateTime.now());
            paymentRepository.save(p);
        });

        // Try to decrement stock for every line item. Track successful decrements so we
        // can restore them if a later item runs out (race between order creation and this call).
        List<OrderItem> decremented = new ArrayList<>();
        OrderItem failedItem = null;

        for (OrderItem item : order.getItems()) {
            int updated = productVariantRepository.decrementStock(
                    item.getVariant().getId(), item.getQuantity());
            if (updated == 0) {
                failedItem = item;
                break;
            }
            decremented.add(item);
        }

        if (failedItem != null) {
            // Roll back stock for items we already decremented.
            for (OrderItem done : decremented) {
                productVariantRepository.restoreStock(done.getVariant().getId(), done.getQuantity());
            }
            // Cancel and flag for manual refund — do NOT throw, so the webhook returns 200
            // and Mollie stops retrying (this is a permanent stock-out, not a transient error).
            String note = "REFUND_REQUIRED: payment captured but variant "
                    + failedItem.getVariant().getId() + " sold out at confirmation time.";
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationNote(note);
            orderRepository.save(order);
            log.error("REFUND_REQUIRED: order {} — Mollie payment captured but variant {} out of stock."
                    + " Manual refund needed.", order.getOrderNumber(), failedItem.getVariant().getId());
            return;
        }

        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        List<OrderShippingSnapshot> shippingSnapshots =
                orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(order.getId());
        ledgerService.recordOrderPayment(order);
        ledgerService.recordShippingRevenue(order, shippingSnapshots);

        sendOrderConfirmationEmail(order, shippingSnapshots);

        log.info("Webhook: order {} PENDING → PAID", order.getOrderNumber());
    }

    /**
     * Publishes a confirmation event once payment is captured. Sent from the webhook path only
     * (not {@code createOrder}) — that's the point where money has actually changed hands, not
     * just where a Mollie payment intent was opened. The actual send happens AFTER_COMMIT in
     * {@link OrderConfirmationEmailListener} so an SMTP failure can never roll back the
     * already-committed PENDING → PAID transition (see that class for the best-effort contract).
     */
    private void sendOrderConfirmationEmail(Order order, List<OrderShippingSnapshot> shippingSnapshots) {
        List<OrderItemLine> items = order.getItems().stream()
                .map(item -> new OrderItemLine(item.getProductSnapshotName(),
                        item.getVariantSnapshotColor(), item.getVariantSnapshotSize(),
                        item.getQuantity(), item.getLineTotal()))
                .toList();

        ShippingAddress addr = order.getShippingAddress();
        String addressBlock = addr == null ? "" :
                addr.getFirstName() + " " + addr.getLastName() + "\n"
                + addr.getStreet() + " " + addr.getHouseNumber() + "\n"
                + (addr.getAddressLine2() != null && !addr.getAddressLine2().isBlank()
                        ? addr.getAddressLine2() + "\n" : "")
                + addr.getPostalCode() + " " + addr.getCity() + "\n"
                + addr.getCountry();

        String orderLink = orderLink(order);

        // Per-brand breakdown only when there's more than one shipping line — a single-brand
        // order's shipping is already fully explained by the one shippingTotal figure.
        List<String> shippingBreakdown = List.of();
        if (shippingSnapshots.size() > 1) {
            Map<Long, String> brandNames = brandPartnerRepository
                    .findAllById(shippingSnapshots.stream().map(OrderShippingSnapshot::getBrandPartnerId).toList())
                    .stream()
                    .collect(Collectors.toMap(BrandPartner::getId, BrandPartner::getBrandName));
            shippingBreakdown = shippingSnapshots.stream()
                    .map(s -> "  - " + brandNames.getOrDefault(s.getBrandPartnerId(), "Marke")
                            + ": " + s.getAmount() + " " + s.getCurrency())
                    .toList();
        }

        eventPublisher.publishEvent(new OrderConfirmationEvent(
                order.getBuyer().getEmail(),
                order.getOrderNumber(),
                items,
                order.getSubtotal(),
                order.getShippingTotal(),
                shippingBreakdown,
                order.getDiscountAmount(),
                order.getTotal(),
                order.getCurrency(),
                addressBlock,
                orderLink));
    }

    // ===== Admin: forward flow =====

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponseDto> getAllOrders(Pageable pageable) {
        Page<Order> page = orderRepository.findAllByOrderByCreatedAtDesc(pageable);
        OrderRelations relations = loadRelations(page.getContent());
        return page.map(order -> toDto(order, relations));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponseDto> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        Page<Order> page = orderRepository.findByStatus(status, pageable);
        OrderRelations relations = loadRelations(page.getContent());
        return page.map(order -> toDto(order, relations));
    }

    /**
     * Admin-driven status transitions:
     * PENDING            → PAID
     * PAID               → SHIPPED (bulk fallback; BrandPartners normally ship individually via
     *                       confirmShipment, each producing their own PARTIALLY_SHIPPED → SHIPPED
     *                       progress — see syncShipmentStatus) | CANCELLED
     * PARTIALLY_SHIPPED  → SHIPPED (same bulk fallback, force the remaining brands' rows too)
     * SHIPPED            → DELIVERED
     * SHIPPING_PROBLEM   → AWAITING_ADMIN | MANUAL_REVIEW | PAID | CANCELLED
     * AWAITING_ADMIN     → MANUAL_REVIEW | PAID | CANCELLED
     * MANUAL_REVIEW      → PAID | CANCELLED
     *
     * Note: SHIPPING_PROBLEM/AWAITING_ADMIN/MANUAL_REVIEW here are the admin's own deliberate,
     * order-wide escalation levers — distinct from a single brand's local shipping-problem report
     * (see reportShippingProblem), which never touches this field; it only ever moves the
     * per-brand OrderShipment.status and Order.isShippingProblem().
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = findById(orderId);
        OrderStatus current = order.getStatus();

        // Idempotent: already in target state — return without side effects (CB-3).
        if (current == newStatus) {
            return withPaymentId(OrderResponseDto.from(order), order.getId());
        }

        validateForwardTransition(current, newStatus);

        // Decrement stock when payment is confirmed — stock is not held during PENDING.
        if (newStatus == OrderStatus.PAID && current == OrderStatus.PENDING) {
            for (OrderItem item : order.getItems()) {
                int updated = productVariantRepository.decrementStock(
                        item.getVariant().getId(), item.getQuantity());
                if (updated == 0) {
                    throw new IllegalStateException(
                            "Insufficient stock for variant " + item.getVariant().getId() +
                            " at payment confirmation — item may have sold out since order was placed.");
                }
            }
            // Sync payment record (CB-1).
            paymentRepository.findByOrderId(orderId).ifPresent(p -> {
                p.setStatus(PaymentStatus.PAID);
                p.setPaidAt(LocalDateTime.now());
                paymentRepository.save(p);
            });
        }

        // Restore stock when cancelling any post-payment order (CB-6 fix).
        boolean postPaymentCancel = newStatus == OrderStatus.CANCELLED &&
                (current == OrderStatus.PAID ||
                 current == OrderStatus.SHIPPING_PROBLEM ||
                 current == OrderStatus.AWAITING_ADMIN ||
                 current == OrderStatus.MANUAL_REVIEW);
        // Cancelling restores stock for EVERY item and reverses the whole order's ledger — which is
        // wrong once goods are physically out the door. Escalation states are reachable from
        // PARTIALLY_SHIPPED, so without this an admin could escalate a part-shipped order and then
        // cancel it, restoring stock for items a brand already dispatched. Same reasoning that
        // keeps CANCELLED off SHIPPED and PARTIALLY_SHIPPED in validateForwardTransition; a return
        // or refund is the correct instrument once anything has shipped.
        if (newStatus == OrderStatus.CANCELLED && anyBrandHasShipped(order)) {
            throw new IllegalStateException(
                    "Order " + order.getOrderNumber() + " cannot be cancelled — at least one brand has "
                    + "already shipped. Use a return/refund instead.");
        }

        if (postPaymentCancel) {
            restoreVariantStock(order);
        }

        // First (and only) time this order reaches DELIVERED — anchors the 14-day Widerruf window.
        if (newStatus == OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(LocalDateTime.now());
        }

        // Admin's order-wide SHIPPED override — brands normally ship individually via
        // confirmShipment, each recording their own OrderShipment row. This bulk path bypasses
        // that, so backfill a SHIPPED row for every brand that hasn't recorded one, keeping the
        // per-brand shipments list consistent with the order-wide status this call just set.
        if (newStatus == OrderStatus.SHIPPED) {
            bulkMarkAllBrandsShipped(order);
        }

        order.setStatus(newStatus);

        // Coming back from an escalation, PAID means "return to the normal shipping flow", not
        // "forget what already shipped" — re-derive the honest rollup from the brand rows, which
        // escalation never touched. An order where one brand had shipped lands back on
        // PARTIALLY_SHIPPED, not flatly on PAID.
        if (newStatus == OrderStatus.PAID && isAdminEscalation(current)) {
            syncShipmentStatus(order);
        }

        log.info("Order {} status: {} → {}", order.getOrderNumber(), current, order.getStatus());
        Order saved = orderRepository.save(order);

        // Record ledger entries for admin-forced payment confirmation.
        if (newStatus == OrderStatus.PAID && current == OrderStatus.PENDING) {
            ledgerService.recordOrderPayment(saved);
            ledgerService.recordShippingRevenue(saved,
                    orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(saved.getId()));
        }

        // Reverse brand ledger entries when a post-payment order is cancelled. The discount code
        // (if any) is released here too — a cancelled order no longer has a discounted sale to its
        // name, so the usage it reserved at checkout must go back.
        if (postPaymentCancel) {
            ledgerService.recordRefund(saved, saved.getTotal(), "ADMIN_CANCEL_" + saved.getId());
            releaseDiscountUsageOnce(saved);
        }

        return withPaymentId(OrderResponseDto.from(saved), saved.getId());
    }

    /**
     * Releases this order's reserved discount-code usage, guarded so it only ever happens once per
     * order — called from every path that ends an order's life without a discounted sale to show
     * for it (cancel, auto-expiry, full refund).
     */
    private void releaseDiscountUsageOnce(Order order) {
        if (order.getDiscountCode() == null || order.isDiscountUsageReleased()) return;
        discountService.releaseUsage(order.getDiscountCode());
        order.setDiscountUsageReleased(true);
        orderRepository.save(order);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto cancelOrder(Long orderId, CancelOrderDto dto, User admin) {
        Order order = findById(orderId);

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING orders can be cancelled. Current status: " + order.getStatus());
        }

        // No stock restore needed — PENDING orders never decremented stock.

        order.setCancellationReason(dto.getReason());
        order.setCancellationNote(dto.getNote());
        order.setCancelledByAdminEmail(admin.getEmail());
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        // Reserved at checkout (validateAndApply), before payment — release it now the order never
        // completes.
        releaseDiscountUsageOnce(order);

        // Best-effort cancellation-notification email, dispatched AFTER_COMMIT — never blocks/rolls
        // back the already-committed cancellation + discount-usage release.
        eventPublisher.publishEvent(new OrderCancelledEvent(
                order.getBuyer().getEmail(), order.getOrderNumber(), dto.getReason(), dto.getNote()));

        log.info("Order {} cancelled by admin {} — reason: {}",
                order.getOrderNumber(), admin.getEmail(), dto.getReason());
        return withPaymentId(OrderResponseDto.from(order), order.getId());
    }

    // ===== Admin: return flow =====

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto approveReturn(String returnNumber) {
        ReturnOrder returnOrder = findReturnByNumber(returnNumber);
        Order order = returnOrder.getOrder();

        if (returnOrder.getStatus() != ReturnStatus.REQUESTED) {
            throw new IllegalStateException("Can only approve a return in REQUESTED status. Current: "
                    + returnOrder.getStatus());
        }

        returnOrder.setStatus(ReturnStatus.APPROVED);
        returnOrder.setApprovedAt(LocalDateTime.now());
        // labelStatus is already PENDING from creation — approval just makes uploading one valid
        // (see ReturnOrder.applyBrandUploadedLabel's state guard).
        returnOrderRepository.save(returnOrder);

        Order saved = orderRepository.save(syncOrderStatus(order));

        // Publish AFTER_COMMIT so a mail failure can never roll back this approval. The address is
        // read from the return's own snapshot, so the listener needs no DB access — and each brand
        // return produces its own email naming that brand and its own destination.
        eventPublisher.publishEvent(new ReturnApprovedEvent(
                order.getBuyer().getEmail(),
                order.getOrderNumber(),
                returnOrder.getReturnNumber(),
                returnOrder.getBrand() != null ? returnOrder.getBrand().getBrandName() : null,
                returnOrder.getShipToFormatted()));

        log.info("Return {} approved for order {} (brand {})", returnNumber, order.getOrderNumber(),
                returnOrder.getBrand() != null ? returnOrder.getBrand().getId() : null);
        return toDto(saved);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto receiveReturn(String returnNumber) {
        ReturnOrder returnOrder = findReturnByNumber(returnNumber);
        Order order = returnOrder.getOrder();

        if (returnOrder.getStatus() != ReturnStatus.APPROVED) {
            throw new IllegalStateException("Can only receive a return in APPROVED status. Current: "
                    + returnOrder.getStatus());
        }

        for (ReturnItem item : returnOrder.getItems()) {
            ProductVariant variant = item.getOrderItem().getVariant();
            productVariantRepository.restoreStock(variant.getId(), item.getQuantityReturned());
        }

        returnOrder.setStatus(ReturnStatus.RECEIVED);
        returnOrder.setReceivedAt(LocalDateTime.now());
        returnOrderRepository.save(returnOrder);

        log.info("Return {} received for order {} — variant stock restored", returnNumber,
                order.getOrderNumber());
        return toDto(orderRepository.save(syncOrderStatus(order)));
    }

    /**
     * Refunds a single brand's return. The amount defaults to that brand's refundable share when
     * null, and is capped by it — refunding "the order total" against one brand's return would
     * reverse ledger entries for brands whose goods never came back.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto processRefund(String returnNumber, BigDecimal refundAmount) {
        // Validate state before touching Mollie or the DB.
        ReturnOrder returnOrder = findReturnByNumber(returnNumber);
        Order order = returnOrder.getOrder();

        if (returnOrder.getStatus() != ReturnStatus.RECEIVED) {
            throw new IllegalStateException("Can only refund after the return is received. Current: "
                    + returnOrder.getStatus());
        }
        if (returnOrder.getBrand() == null) {
            throw new IllegalStateException("Return " + returnNumber + " has no brand — cannot scope the refund");
        }

        BigDecimal refundable = refundableTotal(returnOrder);
        BigDecimal amount = refundAmount != null ? refundAmount : refundable;
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (amount.compareTo(refundable) > 0) {
            throw new IllegalArgumentException("Refund amount " + amount
                    + " exceeds the refundable total " + refundable + " for return " + returnNumber);
        }

        Payment payment = paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new IllegalStateException("No payment record for order " + order.getId()));

        // 1. Call the payment provider OUTSIDE any transaction — avoids holding a DB connection
        //    during an HTTP call and separates the external side-effect from the atomic DB commit.
        String refundId;
        try {
            refundId = paymentProvider.refundPayment(new RefundCommand(
                    payment.getTransactionId(),
                    amount,
                    "Refund for return " + returnNumber + " (order " + order.getOrderNumber() + ")")).refundId();
        } catch (Exception e) {
            log.error("Refund failed for return {}: {}", returnNumber, e.getMessage());
            throw new PaymentException("Could not process refund: " + e.getMessage(), e);
        }

        // 2. Persist all DB changes atomically in a single @Transactional block.
        //    The refund ID is the idempotency key — duplicate calls are safe.
        return refundPersistenceHelper.persist(returnOrder.getId(), amount, refundId);
    }

    /**
     * Brand uploads a label it obtained itself for its own return. Ownership is checked against
     * the return's brand — not against order-item creators — since a return already carries the
     * specific brand it belongs to.
     */
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public OrderResponseDto uploadReturnLabel(String returnNumber, UploadReturnLabelDto dto, User brandPartner) {
        ReturnOrder returnOrder = findReturnByNumber(returnNumber);
        if (returnOrder.getBrand() == null
                || returnOrder.getBrand().getUser() == null
                || !returnOrder.getBrand().getUser().getId().equals(brandPartner.getId())) {
            throw new SecurityException("This return does not belong to your brand");
        }

        returnOrder.applyBrandUploadedLabel(dto.getCarrier(), dto.getTrackingNumber(), dto.getLabelUrl());
        returnOrderRepository.save(returnOrder);

        log.info("BrandPartner {} uploaded return label for {} (carrier={}, tracking={})",
                brandPartner.getEmail(), returnNumber, dto.getCarrier(), dto.getTrackingNumber());
        return toDto(returnOrder.getOrder());
    }

    /** Gross value of the items on this return — the ceiling for refunding it. */
    private BigDecimal refundableTotal(ReturnOrder returnOrder) {
        return returnOrder.getItems().stream()
                .map(ri -> {
                    OrderItem oi = ri.getOrderItem();
                    BigDecimal lineGross = oi.getLineGross() != null ? oi.getLineGross() : oi.getLineTotal();
                    if (oi.getQuantity() == null || oi.getQuantity() == 0) return BigDecimal.ZERO;
                    // Pro-rate when only part of a line came back.
                    return lineGross
                            .multiply(BigDecimal.valueOf(ri.getQuantityReturned()))
                            .divide(BigDecimal.valueOf(oi.getQuantity()), 2, RoundingMode.HALF_UP);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ReturnOrder findReturnByNumber(String returnNumber) {
        return returnOrderRepository.findByReturnNumber(returnNumber)
                .orElseThrow(() -> new IllegalArgumentException("Return not found: " + returnNumber));
    }

    /**
     * Resolves the single return on an order, for the deprecated order-scoped admin endpoints.
     * Rejects multi-brand orders rather than guessing — silently acting on the first brand is the
     * class of bug this whole change exists to remove.
     */
    // ===== Deprecated order-scoped entry points, for callers still addressing returns by order.
    // They resolve the order's single return and delegate; a multi-brand order raises 409. =====

    // approveReturnByOrder/receiveReturnByOrder call approveReturn/receiveReturn as a plain
    // in-class method call (this.foo(...)) — Spring's @Transactional is proxy-based and never
    // intercepts that kind of self-invocation, so the callee's own @Transactional is silently
    // inert here and each shim needs its OWN @Transactional to actually get one. Without it,
    // receiveReturnByOrder 500s (InvalidDataAccessApiUsageException: "No active transaction for
    // update or delete query" — ProductVariantRepository.restoreStock is a bare @Modifying query,
    // which requires an active transaction and has none from a proxy-bypassing caller) and
    // approveReturnByOrder silently loses atomicity between its two repository saves. See
    // RefundPersistenceHelper's own javadoc for the same self-invocation pitfall, worked around
    // there by being a genuinely separate bean.
    //
    // processRefundByOrder does NOT get @Transactional here — processRefund is deliberately NOT
    // @Transactional itself (see its javadoc: the Mollie call must run outside any DB transaction),
    // and its actual DB persistence goes through RefundPersistenceHelper, a separate bean whose own
    // @Transactional fires correctly regardless of caller. Adding @Transactional to this shim would
    // wrap the outbound Mollie HTTP call in a DB transaction — exactly the anti-pattern processRefund
    // exists to avoid.

    @Deprecated
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto approveReturnByOrder(Long orderId) {
        return approveReturn(resolveSoleReturn(orderId).getReturnNumber());
    }

    @Deprecated
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto receiveReturnByOrder(Long orderId) {
        return receiveReturn(resolveSoleReturn(orderId).getReturnNumber());
    }

    @Deprecated
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto processRefundByOrder(Long orderId, BigDecimal refundAmount) {
        return processRefund(resolveSoleReturn(orderId).getReturnNumber(), refundAmount);
    }

    private ReturnOrder resolveSoleReturn(Long orderId) {
        List<ReturnOrder> returns = returnOrderRepository.findByOrder_IdOrderByIdAsc(orderId);
        if (returns.isEmpty()) {
            throw new IllegalStateException("No return found for order id: " + orderId);
        }
        if (returns.size() > 1) {
            throw new MultipleReturnsException(orderId,
                    returns.stream().map(ReturnOrder::getReturnNumber).toList());
        }
        return returns.get(0);
    }

    /**
     * Recomputes the order-level status from its returns. {@link #} is a single field
     * and cannot express "brand A received, brand B still requested", so it tracks the LEAST
     * advanced open return — the order is only as far along as its slowest brand. REFUNDED requires
     * every return to be refunded AND every item to be covered, so a partial return never makes a
     * whole order look refunded.
     */
    private Order syncOrderStatus(Order order) {
        List<ReturnOrder> returns = returnOrderRepository.findByOrder_IdOrderByIdAsc(order.getId());
        if (returns.isEmpty()) return order;

        boolean allRefunded = returns.stream().allMatch(r -> r.getStatus() == ReturnStatus.REFUNDED);
        if (allRefunded && allItemsReturned(order, returns)) {
            order.setStatus(OrderStatus.REFUNDED);
            return order;
        }

        ReturnStatus least = returns.stream()
                .map(ReturnOrder::getStatus)
                .min(Comparator.comparingInt(ReturnStatus::ordinal))
                .orElse(ReturnStatus.REQUESTED);
        order.setStatus(switch (least) {
            case REQUESTED -> OrderStatus.RETURN_REQUESTED;
            case APPROVED  -> OrderStatus.RETURN_APPROVED;
            case RECEIVED  -> OrderStatus.RETURN_RECEIVED;
            case REFUNDED  -> OrderStatus.RETURN_RECEIVED; // refunded but not all items covered
        });
        return order;
    }

    private boolean allItemsReturned(Order order, List<ReturnOrder> returns) {
        Set<Long> returned = returns.stream()
                .flatMap(r -> r.getItems().stream())
                .map(ri -> ri.getOrderItem().getId())
                .collect(Collectors.toSet());
        return order.getItems().stream().allMatch(i -> returned.contains(i.getId()));
    }

    // ===== Private helpers =====

    private List<ProductListing> resolveAndValidateListings(List<OrderItemRequestDto> items) {
        List<ProductListing> listings = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (OrderItemRequestDto item : items) {
            ProductListing pl = productListingRepository.findById(item.getListingId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Listing not found with id: " + item.getListingId()));

            if (!pl.isActive()) {
                throw new IllegalStateException("Listing " + pl.getId() + " is not active");
            }
            if (pl.getAvailableFrom() != null && now.isBefore(pl.getAvailableFrom())) {
                throw new IllegalStateException(
                        "Listing " + pl.getId() + " is not yet available (drop: " + pl.getAvailableFrom() + ")");
            }
            if (pl.getAvailableUntil() != null && now.isAfter(pl.getAvailableUntil())) {
                throw new IllegalStateException("Listing " + pl.getId() + " is no longer available");
            }

            listings.add(pl);
        }
        return listings;
    }

    private void restoreVariantStock(Order order) {
        for (OrderItem item : order.getItems()) {
            productVariantRepository.restoreStock(item.getVariant().getId(), item.getQuantity());
        }
    }

    private BigDecimal getBrandCommissionRate(Long brandId) {
        return brandEconomicsRepository.findByBrandPartner_Id(brandId)
                .map(BrandEconomics::getDefaultCommissionRate)
                .filter(r -> r != null && r.compareTo(BigDecimal.ZERO) > 0)
                .orElse(globalCommissionRate);
    }

    private void validateForwardTransition(OrderStatus from, OrderStatus to) {
        boolean valid = switch (from) {
            case PENDING            -> to == OrderStatus.PAID;
            case PAID               -> to == OrderStatus.SHIPPED || to == OrderStatus.CANCELLED
                                        || isAdminEscalation(to);
            // CANCELLED is deliberately absent here, same as on SHIPPED below: once any brand has
            // physically shipped, a blanket cancel+refund+stock-restore across every brand isn't
            // the right lever — a return/refund flow is. Escalation IS allowed, so a mixed-state
            // order (one brand shipped, another stuck) still has an admin exit.
            case PARTIALLY_SHIPPED  -> to == OrderStatus.SHIPPED || isAdminEscalation(to);
            case SHIPPED            -> to == OrderStatus.DELIVERED;
            case SHIPPING_PROBLEM, AWAITING_ADMIN, MANUAL_REVIEW ->
                    to == OrderStatus.AWAITING_ADMIN ||
                    to == OrderStatus.MANUAL_REVIEW  ||
                    to == OrderStatus.PAID           ||
                    to == OrderStatus.CANCELLED;
            default -> false;
        };

        if (!valid) {
            throw new IllegalStateException("Invalid status transition: " + from + " → " + to);
        }
    }

    /**
     * The admin's deliberate order-wide escalation states. Reachable only through
     * {@link #updateOrderStatus} — a brand's own {@link #reportShippingProblem} never sets them
     * (that only marks the brand's own OrderShipment row plus the order-wide
     * {@code hasShippingProblem} visibility flag).
     *
     * Escalating does NOT touch any OrderShipment row: a brand that genuinely shipped keeps its
     * SHIPPED row, carrier and tracking number untouched — escalation pauses the order-level
     * rollup, it does not rewrite what physically happened. The way back is
     * {@code -> PAID}, which re-runs {@link #syncShipmentStatus} so the order returns to the
     * honest rollup of its brand rows (PARTIALLY_SHIPPED, or SHIPPED if every brand had shipped),
     * never flatly to PAID — so escalation is a round trip, not a one-way street.
     */
    private boolean isAdminEscalation(OrderStatus to) {
        return to == OrderStatus.SHIPPING_PROBLEM
                || to == OrderStatus.AWAITING_ADMIN
                || to == OrderStatus.MANUAL_REVIEW;
    }

    /** True once any brand on this order has actually dispatched its items. */
    private boolean anyBrandHasShipped(Order order) {
        return orderShipmentRepository.findByOrder_IdOrderByIdAsc(order.getId()).stream()
                .anyMatch(s -> s.getStatus() == ShipmentStatus.SHIPPED);
    }

    private void assertOwnership(Order order, User buyer) {
        if (!order.getBuyer().getId().equals(buyer.getId())) {
            throw new SecurityException("You do not own this order");
        }
    }

    /** Brand owns the order if any line item's variant.product.creator is this user — returns the
     *  matched {@link BrandPartner} entity itself (needed as the FK on {@link OrderShipment}),
     *  rather than just asserting ownership and discarding it. */
    private BrandPartner resolveOwnBrand(Order order, User brandPartner) {
        return order.getItems().stream()
                .filter(item -> item.getVariant().getProduct().getCreator().getId().equals(brandPartner.getId()))
                .map(item -> item.getVariant().getProduct().getBrand())
                .findFirst()
                .orElseThrow(() -> new SecurityException("This order does not contain any of your products"));
    }

    /** Finds this brand's existing shipment row on the order, or a new unsaved one defaulted to
     *  AWAITING_SHIPMENT — callers set fields and save. Never more than one row per (order, brand);
     *  the DB unique index (V27) backs that up. */
    private OrderShipment resolveOrCreateShipment(Order order, BrandPartner brand) {
        return orderShipmentRepository.findByOrder_IdAndBrand_Id(order.getId(), brand.getId())
                .orElseGet(() -> OrderShipment.awaiting(order, brand));
    }

    /**
     * Rolls up per-brand {@link OrderShipment} progress onto {@link Order#getStatus()} — mirrors
     * {@link #syncOrderStatus} for returns. Only advances the order while it is genuinely in the
     * shipping phase (PAID → PARTIALLY_SHIPPED → SHIPPED) and only ever forward: an order already
     * moved into a RETURN_-prefixed status, REFUNDED, or CANCELLED, or an admin's own order-wide
     * SHIPPING_PROBLEM/AWAITING_ADMIN/MANUAL_REVIEW escalation, is left untouched — those remain a deliberate,
     * order-wide admin lever, never overridden by one brand's local shipment event.
     */
    private Order syncShipmentStatus(Order order) {
        if (shippingPhaseRank(order.getStatus()) < 0) return order;

        Set<Long> orderBrandIds = brandsOnOrder(order).keySet();
        if (orderBrandIds.isEmpty()) return order;

        Map<Long, ShipmentStatus> byBrand = orderShipmentRepository.findByOrder_IdOrderByIdAsc(order.getId())
                .stream()
                .collect(Collectors.toMap(s -> s.getBrand().getId(), OrderShipment::getStatus));

        boolean allShipped = orderBrandIds.stream().allMatch(id -> byBrand.get(id) == ShipmentStatus.SHIPPED);
        // Specifically SHIPPED, not "has a row at all" — a brand that only reported a PROBLEM has a
        // row but has dispatched nothing, and must never make the order read as partially shipped.
        boolean anyShipped = orderBrandIds.stream().anyMatch(id -> byBrand.get(id) == ShipmentStatus.SHIPPED);
        OrderStatus computed = allShipped ? OrderStatus.SHIPPED
                : anyShipped ? OrderStatus.PARTIALLY_SHIPPED
                : OrderStatus.PAID;

        if (shippingPhaseRank(computed) > shippingPhaseRank(order.getStatus())) {
            order.setStatus(computed);
        }
        return order;
    }

    /**
     * A brand may only record shipment progress while the order is genuinely awaiting dispatch:
     * PAID, or PARTIALLY_SHIPPED because another brand already shipped. Deliberately NOT a bare
     * {@code == PAID} check — that was the pre-per-brand-shipment guard, and once one brand ships,
     * the order legitimately sits at PARTIALLY_SHIPPED while its remaining brands still need to
     * ship. Widening it to "any shipping-phase status" is what this replaces; dropping it entirely
     * (as an earlier revision did) let a brand mark items shipped on an unpaid PENDING order — and
     * send the customer a dispatch email for an order nobody had paid for.
     */
    private void assertInShippingWindow(Order order, String action) {
        OrderStatus status = order.getStatus();
        if (status != OrderStatus.PAID && status != OrderStatus.PARTIALLY_SHIPPED) {
            throw new IllegalStateException(
                    action + " for a paid order awaiting dispatch. Current status: " + status);
        }
    }

    /** PAID < PARTIALLY_SHIPPED < SHIPPED, -1 for anything outside the shipping phase (see
     *  {@link #syncShipmentStatus}, which never runs outside this band). */
    private int shippingPhaseRank(OrderStatus status) {
        return switch (status) {
            case PAID -> 0;
            case PARTIALLY_SHIPPED -> 1;
            case SHIPPED -> 2;
            default -> -1;
        };
    }

    /**
     * Admin's order-wide SHIPPED override (the documented fallback in {@link #updateOrderStatus} —
     * brands normally ship individually via {@link #confirmShipment}). Upserts a SHIPPED
     * {@link OrderShipment} row for every brand on the order that hasn't already recorded one, so
     * the per-brand shipments list stays consistent with {@code Order.status} instead of silently
     * showing "not shipped yet" under an order the admin just marked SHIPPED.
     *
     * <p>The {@link BrandPartner} to attach is read straight off the order's own items rather than
     * looked back up by the id they expose. That lookup needed an {@code orElse(null)} skip for a
     * brand it couldn't resolve — a branch that would silently leave one brand un-shipped under a
     * SHIPPED order, and that was unreachable anyway, since the id came from that very entity a
     * line earlier.
     *
     * <p>Each brand this actually force-ships also gets a buyer notification, exactly as if it had
     * confirmed the shipment itself. Without that, an admin resolving a stalled brand left the
     * customer with an order reading SHIPPED and no mail ever sent for that part of it.
     */
    private void bulkMarkAllBrandsShipped(Order order) {
        Map<Long, OrderShipment> existing = orderShipmentRepository.findByOrder_IdOrderByIdAsc(order.getId())
                .stream()
                .collect(Collectors.toMap(s -> s.getBrand().getId(), s -> s));

        for (Map.Entry<Long, BrandPartner> entry : brandsOnOrder(order).entrySet()) {
            OrderShipment shipment = existing.get(entry.getKey());
            if (shipment == null) {
                shipment = OrderShipment.awaiting(order, entry.getValue());
            }
            if (shipment.getStatus() != ShipmentStatus.SHIPPED) {
                shipment.setStatus(ShipmentStatus.SHIPPED);
                shipment.setShippedAt(LocalDateTime.now());
                orderShipmentRepository.save(shipment);
                // Only rows this call actually transitions. A brand that had already shipped got
                // its dispatch mail from confirmShipment and must not be told a second time; a
                // brand force-shipped here has had no mail at all, which is the gap this closes.
                publishShipmentConfirmed(order, entry.getValue(),
                        shipment.getCarrier(), shipment.getTrackingNumber(), null);
            }
        }
    }

    /**
     * Every brand with at least one line item on this order, in first-seen order. Read from the
     * items' own {@code variant.product.brand} — already loaded alongside the order — so a caller
     * that needs the entity, not just the id, never has to look it back up.
     */
    private Map<Long, BrandPartner> brandsOnOrder(Order order) {
        Map<Long, BrandPartner> brands = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            BrandPartner brand = item.getVariant().getProduct().getBrand();
            if (brand != null && brand.getId() != null) {
                brands.putIfAbsent(brand.getId(), brand);
            }
        }
        return brands;
    }

    private Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public OrderResponseDto getOrderById(Long orderId) {
        return toDto(findById(orderId));
    }

    /**
     * Resolves whichever address source the caller supplied (exactly one, enforced by
     * {@code @ExactlyOneAddressSource} at the DTO level) into a common {@link ShippingAddressDto}
     * shape. A saved address is loaded with an ownership check — a customer can never use another
     * customer's saved address — and re-checked against {@link AllowedShippingCountries}, since a
     * saved address's country was never restricted at save time (see {@code UserAddress} javadoc).
     * Both paths then run through the same {@link Validator} pass against {@link ShippingAddressDto}'s
     * own constraints — {@code UserAddressDto.postalCode} is intentionally looser (no German-format
     * pattern, since a saved address can be for any country at save time), so a saved address must be
     * re-validated here rather than trusted as-is; this keeps "validated identically regardless of
     * source" a structural guarantee instead of something achieved by two different manual checks.
     */
    private ShippingAddressDto resolveShippingAddress(CreateOrderDto dto, User buyer) {
        ShippingAddressDto resolved;
        if (dto.getSavedAddressId() != null) {
            UserAddress saved = userAddressRepository.findByIdAndUser(dto.getSavedAddressId(), buyer)
                    .orElseThrow(() -> new AddressNotFoundException(
                            "Saved address not found: " + dto.getSavedAddressId()));
            if (!AllowedShippingCountries.isAllowed(saved.getCountry())) {
                throw new IllegalArgumentException(
                        "Shipping to " + saved.getCountry() + " is not currently available");
            }
            resolved = ShippingAddressDto.from(saved);
        } else {
            resolved = dto.getShippingAddress();
        }
        var violations = validator.validate(resolved);
        if (!violations.isEmpty()) {
            var first = violations.iterator().next();
            throw new IllegalArgumentException(first.getPropertyPath() + ": " + first.getMessage());
        }
        return resolved;
    }

    private String generateOrderNumber() {
        return generateUniqueNumber("ENS", c -> orderRepository.findByOrderNumber(c).isPresent());
    }

    private String generateReturnNumber() {
        return generateUniqueNumber("RET", c -> returnOrderRepository.findByReturnNumber(c).isPresent());
    }

    /**
     * {@code PREFIX-<year>-<6 random chars>} over a 36-character alphabet (2.2 billion suffixes per
     * year), re-drawn until {@code taken} reports the candidate free.
     *
     * <p>This is a collision check, not a lock: two concurrent callers can both see the same
     * candidate as free. The unique constraints on {@code orders.order_number} and
     * {@code return_orders.return_number} are what actually guarantee uniqueness — this loop only
     * keeps them from ever realistically firing. Iterating rather than recursing (as both
     * generators used to) keeps a pathological run off the call stack.
     */
    private String generateUniqueNumber(String prefix, Predicate<String> taken) {
        String year = String.valueOf(LocalDateTime.now().getYear());
        String candidate;
        do {
            StringBuilder suffix = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                suffix.append(ORDER_NUM_CHARS.charAt(RANDOM.nextInt(ORDER_NUM_CHARS.length())));
            }
            candidate = prefix + "-" + year + "-" + suffix;
        } while (taken.test(candidate));
        return candidate;
    }

    /**
     * The three side tables every order DTO needs — shipping snapshots, per-brand shipments and
     * per-brand returns — pre-grouped by order id. Both mappers read only from here, so a page of
     * N orders costs a fixed 2-3 queries instead of 2-3 per order (a 20-order list page was 40-60
     * round trips). Single-order callers pass {@code loadRelations(List.of(order))}, which issues
     * exactly the same queries the mappers used to issue inline.
     */
    private record OrderRelations(
            Map<Long, List<OrderShippingSnapshot>> snapshots,
            Map<Long, List<OrderShipment>> shipments,
            Map<Long, List<ReturnOrder>> returns,
            Map<Long, String> paymentIds) {

        List<OrderShippingSnapshot> snapshotsOf(Long orderId) {
            return snapshots.getOrDefault(orderId, List.of());
        }

        List<OrderShipment> shipmentsOf(Long orderId) {
            return shipments.getOrDefault(orderId, List.of());
        }

        List<ReturnOrder> returnsOf(Long orderId) {
            return returns.getOrDefault(orderId, List.of());
        }

        /** Null when the order has no payment row yet, or the provider has not issued an id. */
        String paymentIdOf(Long orderId) {
            return paymentIds.get(orderId);
        }
    }

    /**
     * One batched load of everything {@link #toDto} / {@link #toBrandScopedDto} need for a whole
     * page of orders. Returns are only fetched when at least one order on the page is actually in
     * a return-like status — same condition the mappers apply per order, so a page of ordinary
     * orders pays for two queries, not three.
     *
     * <p>Every {@code getOrder().getId()} below reads an already-managed Order (the page query
     * loaded them in this same persistence context), so grouping never triggers a proxy load.
     */
    private OrderRelations loadRelations(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        if (orderIds.isEmpty()) {
            return new OrderRelations(Map.of(), Map.of(), Map.of(), Map.of());
        }

        Map<Long, List<OrderShippingSnapshot>> snapshots = orderShippingSnapshotRepository
                .findByOrderIdInOrderByIdAsc(orderIds).stream()
                .collect(Collectors.groupingBy(OrderShippingSnapshot::getOrderId));
        Map<Long, List<OrderShipment>> shipments = orderShipmentRepository
                .findByOrder_IdInOrderByIdAsc(orderIds).stream()
                .collect(Collectors.groupingBy(s -> s.getOrder().getId()));

        boolean anyReturnLike = orders.stream().anyMatch(o -> isReturnLikeStatus(o.getStatus()));
        Map<Long, List<ReturnOrder>> returns = anyReturnLike
                ? returnOrderRepository.findByOrder_IdInOrderByIdAsc(orderIds).stream()
                        .collect(Collectors.groupingBy(r -> r.getOrder().getId()))
                : Map.of();

        // payments.order_id is unique, so this is at most one row per order and never overwrites a
        // sibling. transactionId is null until the provider issues one, hence the explicit filter —
        // Collectors.toMap rejects null values.
        Map<Long, String> paymentIds = paymentRepository.findByOrderIdIn(orderIds).stream()
                .filter(p -> p.getTransactionId() != null)
                .collect(Collectors.toMap(p -> p.getOrder().getId(), Payment::getTransactionId));

        return new OrderRelations(snapshots, shipments, returns, paymentIds);
    }

    private OrderResponseDto toDto(Order order) {
        return toDto(order, loadRelations(List.of(order)));
    }

    /**
     * Attaches the provider payment id to a DTO built by the plain {@code OrderResponseDto.from}
     * mappers, which know nothing about payments. One extra lookup, and only on the single-order
     * admin paths — page responses go through {@link #loadRelations}, which batches it.
     */
    private OrderResponseDto withPaymentId(OrderResponseDto dto, Long orderId) {
        return dto.toBuilder()
                .molliePaymentId(paymentRepository.findByOrderId(orderId)
                        .map(Payment::getTransactionId)
                        .orElse(null))
                .build();
    }

    /**
     * Maps an Order to its DTO, attaching every return on the order — one per brand — so the
     * customer sees which items go where. Each return carries its own snapshotted ship-to address;
     * nothing is re-derived from the order's items here.
     */
    private OrderResponseDto toDto(Order order, OrderRelations relations) {
        OrderResponseDto base;
        if (isReturnLikeStatus(order.getStatus())) {
            List<ReturnOrder> returns = relations.returnsOf(order.getId());
            base = !returns.isEmpty() ? OrderResponseDto.withReturns(order, returns) : OrderResponseDto.from(order);
        } else {
            base = OrderResponseDto.from(order);
        }

        return base.toBuilder()
                .shippingSnapshots(mapShippingSnapshots(order, relations.snapshotsOf(order.getId())))
                .shipments(relations.shipmentsOf(order.getId()).stream().map(OrderShipmentDto::from).toList())
                .molliePaymentId(relations.paymentIdOf(order.getId()))
                .build();
    }

    private boolean isReturnLikeStatus(OrderStatus status) {
        return status == OrderStatus.RETURN_REQUESTED
                || status == OrderStatus.RETURN_APPROVED
                || status == OrderStatus.RETURN_RECEIVED
                || status == OrderStatus.REFUNDED;
    }

    /**
     * Scopes an order to exactly what one brand partner may see on a (possibly multi-brand) order:
     * their own line items, their own shipping snapshot, their own return (if any), and an order
     * total recomputed from just those — never another brand's items, shipping revenue, return
     * details, or unit prices. Every /brand/orders/** endpoint must use this, never {@link #toDto}
     * (which is the full, unfiltered view — correct for the customer's own GET /orders and for
     * admin oversight, both of which are allowed to see the whole order).
     *
     * total = subtotal − discountAmount + shippingTotal, same formula as {@link Order#computeTotal()},
     * just re-derived from this brand's own items/snapshot instead of the whole order's.
     */
    private OrderResponseDto toBrandScopedDto(Order order, User brandPartner, OrderRelations relations) {
        List<OrderItem> ownItems = order.getItems().stream()
                .filter(item -> item.getVariant().getProduct().getCreator().getId().equals(brandPartner.getId()))
                .toList();
        if (ownItems.isEmpty()) {
            throw new SecurityException("This order does not contain any of your products");
        }
        Long brandId = ownItems.get(0).getBrandId();

        List<OrderShippingSnapshot> ownSnapshots = relations.snapshotsOf(order.getId()).stream()
                .filter(s -> brandId.equals(s.getBrandPartnerId()))
                .toList();

        BigDecimal subtotal = ownItems.stream()
                .map(OrderItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        // customerGrossAfterDiscount is null on pre-V5 legacy rows (see OrderItem) — falls back to
        // the pre-discount lineTotal, same as those rows always did for the whole-order total.
        BigDecimal customerGrossAfterDiscount = ownItems.stream()
                .map(i -> i.getCustomerGrossAfterDiscount() != null ? i.getCustomerGrossAfterDiscount() : i.getLineTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountAmount = subtotal.subtract(customerGrossAfterDiscount);
        BigDecimal shippingTotal = ownSnapshots.stream()
                .map(OrderShippingSnapshot::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        // Same result as Order.computeTotal()'s subtotal − discount + shipping, stated directly:
        // customerGrossAfterDiscount IS subtotal minus this brand's discount share.
        BigDecimal total = customerGrossAfterDiscount.add(shippingTotal);

        // Always exactly one entry: this brand's own shipment row if it exists, or a synthesized
        // AWAITING_SHIPMENT placeholder if it doesn't — a row only gets created the moment this
        // brand first ships or reports a problem (same convention as ReturnOrder: no row = nothing
        // has happened yet), but the caller shouldn't have to distinguish "no row" from "confirmed
        // not shipped" themselves.
        OrderShipmentDto ownShipment = relations.shipmentsOf(order.getId()).stream()
                .filter(s -> brandId.equals(s.getBrand().getId()))
                .findFirst()
                .map(OrderShipmentDto::from)
                .orElseGet(() -> OrderShipmentDto.awaiting(brandId, ownItems.get(0).getBrandSnapshotName()));
        List<OrderShipmentDto> ownShipments = List.of(ownShipment);

        var builder = OrderResponseDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .buyerId(order.getBuyer().getId())
                .buyerEmail(order.getBuyer().getEmail())
                .status(order.getStatus())
                .shippingAddress(order.getShippingAddress())
                .items(ownItems.stream().map(OrderItemResponseDto::from).toList())
                .subtotal(subtotal)
                .discountCode(order.getDiscountCode())
                .discountType(order.getDiscountType())
                .discountPercent(order.getDiscountPercent())
                .discountAmount(discountAmount)
                .shippingTotal(shippingTotal)
                .shippingSnapshots(mapShippingSnapshots(order, ownSnapshots))
                .shipments(ownShipments)
                // Scoped to THIS brand's own shipment row, not Order.isShippingProblem() — that
                // order-wide flag is true if ANY brand had a problem, and exposing it raw here
                // would leak "some other brand on this order has an issue" to a brand that has no
                // business knowing that.
                .hasShippingProblem(ownShipments.stream().anyMatch(s -> s.getStatus() == ShipmentStatus.PROBLEM))
                .total(total)
                .currency(order.getCurrency())
                .notes(order.getNotes())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt());

        if (isReturnLikeStatus(order.getStatus())) {
            List<ReturnOrder> ownReturns = relations.returnsOf(order.getId()).stream()
                    .filter(r -> r.getBrand() != null && brandId.equals(r.getBrand().getId()))
                    .toList();
            if (!ownReturns.isEmpty()) {
                builder.returns(ownReturns.stream().map(ReturnSummaryDto::from).toList());
                if (ownReturns.size() == 1) {
                    ReturnOrder ret = ownReturns.get(0);
                    builder.returnNumber(ret.getReturnNumber())
                            .returnReason(ret.getReason() != null ? ret.getReason().name() : null)
                            .returnDescription(ret.getDescription())
                            .returnRequestedAt(ret.getRequestedAt())
                            .returnShipToAddress(ret.getShipToFormatted());
                }
            }
        }
        return builder.build();
    }
}
