package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.exception.OrderNotFoundException;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReturnRequestDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
import com.enunas.backend.order.dto.UploadReturnLabelDto;
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
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ReturnOrderRepository returnOrderRepository;
    private final EmailService emailService;
    private final PaymentProvider paymentProvider;
    private final LedgerService ledgerService;
    private final RefundPersistenceHelper refundPersistenceHelper;
    private final DiscountService discountService;
    private final ApplicationEventPublisher eventPublisher;
    private final ReturnAddressSnapshotFactory returnAddressSnapshotFactory;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${enunas.platform.commission-rate:0.18}")
    private BigDecimal globalCommissionRate;

    @Value("${enunas.vat.product-rate:0.19}")
    private BigDecimal vatRateProduct;

    @Value("${enunas.vat.service-rate:0.19}")
    private BigDecimal vatRateService;

    // ===== Customer =====

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderResponseDto createOrder(CreateOrderDto dto, User buyer) {
        // 1. Resolve listings (price source) and validate availability/window.
        List<ProductListing> listings = resolveAndValidateListings(dto.getItems());

        // 2. Validate stock availability — decrement happens at PAID, not here.
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

        // 3. Build order items with price snapshot from listing.
        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        Set<Long> distinctBrandIds = new HashSet<>();
        Map<Long, BigDecimal> brandSubtotals = new HashMap<>(); // brandId → product revenue only

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
            if (brand != null) {
                distinctBrandIds.add(brand.getId());
                brandSubtotals.merge(brand.getId(), lineGross, BigDecimal::add);
            }

            OrderItem item = OrderItem.builder()
                    .variant(variant)
                    .productSnapshotName(pl.getProduct().getName())
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
        }

        // 4. Apply optional discount code (max one per order — no stacking). Re-runs the money
        //    snapshot per item with the NET discount shares folded in, so the ledger (which reads
        //    commissionNet/commissionVat/brandPayout) stays correct per brand. Reserves usage.
        DiscountApplication discount = null;
        if (dto.getDiscountCode() != null && !dto.getDiscountCode().isBlank()) {
            discount = discountService.validateAndApply(dto.getDiscountCode(), orderItems);
            for (int i = 0; i < orderItems.size(); i++) {
                OrderItem item = orderItems.get(i);
                DiscountApplication.ItemShare share = discount.itemShares().get(i);
                // percent reduces the customer price only for items the code actually applies to
                // (a BRAND code leaves other brands' items at full price → zero share, zero percent).
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

        // 5. Free shipping — the platform never charges, collects, or splits shipping. The brand
        //    bears its own carrier cost off-platform. shippingTotal is always 0 (column retained).
        BigDecimal shippingTotal = BigDecimal.ZERO;

        // 6. Build & persist order.
        ShippingAddress address = ShippingAddress.builder()
                .fullName(dto.getShippingAddress().getFullName())
                .street(dto.getShippingAddress().getStreet())
                .street2(dto.getShippingAddress().getStreet2())
                .city(dto.getShippingAddress().getCity())
                .postalCode(dto.getShippingAddress().getPostalCode())
                .country(dto.getShippingAddress().getCountry())
                .state(dto.getShippingAddress().getState())
                .phone(dto.getShippingAddress().getPhone())
                .build();

        Order.OrderBuilder orderBuilder = Order.builder()
                .orderNumber(generateOrderNumber())
                .buyer(buyer)
                .status(OrderStatus.PENDING)
                .shippingAddress(address)
                .subtotal(subtotal)
                .shippingTotal(shippingTotal) // always 0 — free shipping
                // Goods-only discounted total — this is what Mollie charges and the webhook verifies.
                .total(subtotal.subtract(discountAmount))
                .currency(listings.get(0).getCurrency())
                .notes(dto.getNotes());

        if (discount != null) {
            orderBuilder
                    .discountCode(discount.code().getCode())
                    .discountType(discount.type())
                    .discountPercent(discount.percent())
                    .discountAmount(discountAmount)                              // gross reduction
                    .platformDiscountAmount(discount.platformDiscountAmount())  // net absorption share
                    .brandDiscountAmount(discount.brandDiscountAmount());       // net absorption share
        }

        Order order = orderBuilder.build();

        Order saved = orderRepository.save(order);
        orderItems.forEach(saved::addItem);
        orderItemRepository.saveAll(orderItems);

        String redirectUrl = frontendBaseUrl + "/orders/" + saved.getOrderNumber() + "/confirmation";
        PaymentResult paymentResult;
        try {
            paymentResult = paymentProvider.createPayment(new CreatePaymentCommand(
                    saved.getTotal(),
                    saved.getCurrency(),
                    "Enunas order " + saved.getOrderNumber(),
                    redirectUrl));
        } catch (Exception e) {
            log.error("Payment creation failed for order {}: {}", saved.getOrderNumber(), e.getMessage());
            throw new PaymentException("Could not initiate payment. Please try again.", e);
        }

        // IMPORTANT: payment already created above. If this DB save fails and the
        // transaction rolls back, the provider-side payment is orphaned. Manual reconciliation
        // is required using the paymentId logged below.
        log.info("Payment created: paymentId={} for order={}",
                paymentResult.paymentId(), saved.getOrderNumber());
        paymentRepository.save(Payment.builder()
                .order(saved)
                .amount(saved.getTotal())
                .currency(saved.getCurrency())
                .transactionId(paymentResult.paymentId())
                .build());

        log.info("Order created: {} for buyer: {} (brands: {})",
                saved.getOrderNumber(), buyer.getEmail(), distinctBrandIds.size());
        return OrderResponseDto.from(saved, paymentResult.checkoutUrl());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('CUSTOMER')")
    public Page<OrderResponseDto> getMyOrders(User buyer, Pageable pageable) {
        return orderRepository.findByBuyerOrderByCreatedAtDesc(buyer, pageable).map(this::toDto);
    }

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

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public Page<OrderResponseDto> getMyBrandOrders(User brandPartner, Pageable pageable) {
        return orderRepository.findByBrandPartnerCreatorId(brandPartner.getId(), pageable)
                .map(this::toDto);
    }

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public OrderResponseDto confirmShipment(Long orderId, ShipmentConfirmationDto dto, User brandPartner) {
        Order order = findById(orderId);
        assertBrandOwnership(order, brandPartner);

        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException(
                    "Shipment can only be confirmed for PAID orders. Current status: " + order.getStatus());
        }

        order.setShippingCarrier(dto.getCarrier());
        order.setTrackingNumber(dto.getTrackingNumber());
        order.setShippedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.SHIPPED);

        emailService.sendPlainTextEmail(
                order.getBuyer().getEmail(),
                "Deine Bestellung " + order.getOrderNumber() + " wurde versendet",
                "Deine Bestellung ist unterwegs!\nVersanddienstleister: " + dto.getCarrier() +
                "\nTracking-Nummer: " + dto.getTrackingNumber() +
                (dto.getNote() != null && !dto.getNote().isBlank() ? "\nHinweis: " + dto.getNote() : ""));

        log.info("BrandPartner {} confirmed shipment for order {} via {}",
                brandPartner.getEmail(), order.getOrderNumber(), dto.getCarrier());
        return OrderResponseDto.from(orderRepository.save(order));
    }

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public OrderResponseDto reportShippingProblem(Long orderId, ShippingProblemDto dto, User brandPartner) {
        Order order = findById(orderId);
        assertBrandOwnership(order, brandPartner);

        if (order.getStatus() != OrderStatus.PAID) {
            throw new IllegalStateException(
                    "Can only report shipping problems for PAID orders. Current status: " + order.getStatus());
        }

        order.setProblemDescription(dto.getDescription());
        order.setProblemReportedAt(LocalDateTime.now());
        order.setProblemReportedBy(brandPartner.getEmail());
        order.setStatus(OrderStatus.SHIPPING_PROBLEM);

        log.warn("BrandPartner {} reported shipping problem for order {}: {}",
                brandPartner.getEmail(), order.getOrderNumber(), dto.getDescription());
        return OrderResponseDto.from(orderRepository.save(order));
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

        ledgerService.recordOrderPayment(order);

        log.info("Webhook: order {} PENDING → PAID", order.getOrderNumber());
    }

    // ===== Admin: forward flow =====

    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponseDto> getAllOrders(Pageable pageable) {
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable).map(this::toDto);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponseDto> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatus(status, pageable).map(this::toDto);
    }

    /**
     * Admin-driven status transitions:
     * PENDING           → PAID
     * PAID              → SHIPPED (fallback; BrandPartner normally does this via confirmShipment)
     * SHIPPED           → DELIVERED
     * SHIPPING_PROBLEM  → AWAITING_ADMIN | MANUAL_REVIEW | PAID | CANCELLED
     * AWAITING_ADMIN    → MANUAL_REVIEW | PAID | CANCELLED
     * MANUAL_REVIEW     → PAID | CANCELLED
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto updateOrderStatus(Long orderId, OrderStatus newStatus) {
        Order order = findById(orderId);
        OrderStatus current = order.getStatus();

        // Idempotent: already in target state — return without side effects (CB-3).
        if (current == newStatus) {
            return OrderResponseDto.from(order);
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
        if (postPaymentCancel) {
            restoreVariantStock(order);
        }

        // First (and only) time this order reaches DELIVERED — anchors the 14-day Widerruf window.
        if (newStatus == OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(LocalDateTime.now());
        }

        order.setStatus(newStatus);
        log.info("Order {} status: {} → {}", order.getOrderNumber(), current, newStatus);
        Order saved = orderRepository.save(order);

        // Record ledger entries for admin-forced payment confirmation.
        if (newStatus == OrderStatus.PAID && current == OrderStatus.PENDING) {
            ledgerService.recordOrderPayment(saved);
        }

        // Reverse brand ledger entries when a post-payment order is cancelled. The discount code
        // (if any) is released here too — a cancelled order no longer has a discounted sale to its
        // name, so the usage it reserved at checkout must go back.
        if (postPaymentCancel) {
            ledgerService.recordRefund(saved, saved.getTotal(), "ADMIN_CANCEL_" + saved.getId());
            releaseDiscountUsageOnce(saved);
        }

        return OrderResponseDto.from(saved);
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

        String body = "Deine Bestellung " + order.getOrderNumber() + " wurde storniert.\n" +
                "Grund: " + dto.getReason() +
                (dto.getNote() != null && !dto.getNote().isBlank() ? "\nHinweis: " + dto.getNote() : "");
        emailService.sendPlainTextEmail(
                order.getBuyer().getEmail(),
                "Bestellung " + order.getOrderNumber() + " storniert",
                body);

        log.info("Order {} cancelled by admin {} — reason: {}",
                order.getOrderNumber(), admin.getEmail(), dto.getReason());
        return OrderResponseDto.from(order);
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

    @Deprecated
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto approveReturnByOrder(Long orderId) {
        return approveReturn(resolveSoleReturn(orderId).getReturnNumber());
    }

    @Deprecated
    @PreAuthorize("hasRole('ADMIN')")
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
     * Recomputes the order-level status from its returns. {@link Order#status} is a single field
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
                .collect(java.util.stream.Collectors.toSet());
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
            case PENDING         -> to == OrderStatus.PAID;
            case PAID            -> to == OrderStatus.SHIPPED || to == OrderStatus.CANCELLED;
            case SHIPPED         -> to == OrderStatus.DELIVERED;
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

    private void assertOwnership(Order order, User buyer) {
        if (!order.getBuyer().getId().equals(buyer.getId())) {
            throw new SecurityException("You do not own this order");
        }
    }

    /** Brand owns the order if any line item's variant.product.creator is this user. */
    private void assertBrandOwnership(Order order, User brandPartner) {
        boolean isOwner = order.getItems().stream()
                .anyMatch(item -> item.getVariant().getProduct().getCreator().getId()
                        .equals(brandPartner.getId()));
        if (!isOwner) {
            throw new SecurityException("This order does not contain any of your products");
        }
    }

    private Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public OrderResponseDto getOrderById(Long orderId) {
        return toDto(findById(orderId));
    }

    private String generateOrderNumber() {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(ORDER_NUM_CHARS.charAt(RANDOM.nextInt(ORDER_NUM_CHARS.length())));
        }
        String year = String.valueOf(LocalDateTime.now().getYear());
        String candidate = "ENS-" + year + "-" + suffix;
        return orderRepository.findByOrderNumber(candidate).isPresent()
                ? generateOrderNumber()
                : candidate;
    }

    private String generateReturnNumber() {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(ORDER_NUM_CHARS.charAt(RANDOM.nextInt(ORDER_NUM_CHARS.length())));
        }
        String year = String.valueOf(LocalDateTime.now().getYear());
        String candidate = "RET-" + year + "-" + suffix;
        return returnOrderRepository.findByReturnNumber(candidate).isPresent()
                ? generateReturnNumber()
                : candidate;
    }

    /**
     * Maps an Order to its DTO, attaching every return on the order — one per brand — so the
     * customer sees which items go where. Each return carries its own snapshotted ship-to address;
     * nothing is re-derived from the order's items here.
     */
    private OrderResponseDto toDto(Order order) {
        if (order.getStatus() == OrderStatus.RETURN_REQUESTED
                || order.getStatus() == OrderStatus.RETURN_APPROVED
                || order.getStatus() == OrderStatus.RETURN_RECEIVED
                || order.getStatus() == OrderStatus.REFUNDED) {
            List<ReturnOrder> returns = returnOrderRepository.findByOrder_IdOrderByIdAsc(order.getId());
            if (!returns.isEmpty()) {
                return OrderResponseDto.withReturns(order, returns);
            }
        }
        return OrderResponseDto.from(order);
    }
}
