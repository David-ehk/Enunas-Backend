package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.exception.OrderNotFoundException;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReturnRequestDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
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
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final BrandShippingProfileRepository brandShippingProfileRepository;
    private final PaymentRepository paymentRepository;
    private final BrandEconomicsRepository brandEconomicsRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final EmailService emailService;
    private final PaymentProvider paymentProvider;
    private final LedgerService ledgerService;
    private final RefundPersistenceHelper refundPersistenceHelper;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${enunas.platform.commission-rate:0.18}")
    private BigDecimal globalCommissionRate;

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

            BigDecimal effectivePrice = pl.getDiscountPrice() != null ? pl.getDiscountPrice() : pl.getPrice();
            BigDecimal lineTotal = effectivePrice.multiply(BigDecimal.valueOf(itemDto.getQuantity()));
            subtotal = subtotal.add(lineTotal);

            BrandPartner brand = pl.getProduct().getBrand();
            if (brand != null) {
                distinctBrandIds.add(brand.getId());
                brandSubtotals.merge(brand.getId(), lineTotal, BigDecimal::add);
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
                    .lineTotal(lineTotal)
                    .build();
            if (brand != null) {
                item.applyCommissionSnapshot(getBrandCommissionRate(brand.getId()));
            }
            orderItems.add(item);
        }

        // !! 4. Compute shipping per brand (one charge per brand, not per item). Has to change to Order not Brand
        BigDecimal shippingTotal = BigDecimal.ZERO;
        for (Long brandId : distinctBrandIds) {
            shippingTotal = shippingTotal.add(shippingCostForBrand(brandId));
        }

        // 5. Build & persist order.
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

        Order order = Order.builder()
                .orderNumber(generateOrderNumber())
                .buyer(buyer)
                .status(OrderStatus.PENDING)
                .shippingAddress(address)
                .subtotal(subtotal)
                .shippingTotal(shippingTotal)
                .total(subtotal.add(shippingTotal))
                .currency(listings.get(0).getCurrency())
                .notes(dto.getNotes())
                .build();

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
        return orderRepository.findByBuyerOrderByCreatedAtDesc(buyer, pageable)
                .map(OrderResponseDto::from);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    public OrderResponseDto getMyOrderById(Long orderId, User buyer) {
        Order order = findById(orderId);
        assertOwnership(order, buyer);
        return OrderResponseDto.from(order);
    }

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderResponseDto requestReturn(Long orderId, ReturnRequestDto dto, User buyer) {
        Order order = findById(orderId);
        assertOwnership(order, buyer);

        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new IllegalStateException(
                    "Return can only be requested for DELIVERED orders. Current status: " + order.getStatus());
        }

        if (returnOrderRepository.existsByOrder(order)) {
            throw new IllegalStateException(
                    "A return has already been requested for order " + order.getOrderNumber());
        }

        ReturnOrder returnOrder = ReturnOrder.builder()
                .returnNumber(generateReturnNumber())
                .order(order)
                .user(buyer)
                .reason(dto.reason())
                .description(dto.description())
                .build();

        ReturnOrder savedReturn = returnOrderRepository.save(returnOrder);

        if (dto.orderItemId() == null) {
            for (OrderItem item : order.getItems()) {
                savedReturn.addItem(ReturnItem.builder()
                        .orderItem(item)
                        .quantityReturned(item.getQuantity())
                        .build());
            }
        } else {
            OrderItem target = order.getItems().stream()
                    .filter(i -> i.getId().equals(dto.orderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "OrderItem " + dto.orderItemId() + " does not belong to this order"));
            savedReturn.addItem(ReturnItem.builder()
                    .orderItem(target)
                    .quantityReturned(target.getQuantity())
                    .build());
        }

        returnOrderRepository.save(savedReturn);
        order.setStatus(OrderStatus.RETURN_REQUESTED);
        log.info("Return requested for order {} by {}", order.getOrderNumber(), buyer.getEmail());
        return OrderResponseDto.from(orderRepository.save(order));
    }

    // ===== BrandPartner =====

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public Page<OrderResponseDto> getMyBrandOrders(User brandPartner, Pageable pageable) {
        return orderRepository.findByBrandPartnerCreatorId(brandPartner.getId(), pageable)
                .map(OrderResponseDto::from);
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
        Order order = findById(orderId);
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
        return orderRepository.findAllByOrderByCreatedAtDesc(pageable).map(OrderResponseDto::from);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public Page<OrderResponseDto> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatus(status, pageable).map(OrderResponseDto::from);
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

        order.setStatus(newStatus);
        log.info("Order {} status: {} → {}", order.getOrderNumber(), current, newStatus);
        Order saved = orderRepository.save(order);

        // Record ledger entries for admin-forced payment confirmation.
        if (newStatus == OrderStatus.PAID && current == OrderStatus.PENDING) {
            ledgerService.recordOrderPayment(saved);
        }

        // Reverse brand ledger entries when a post-payment order is cancelled.
        if (postPaymentCancel) {
            ledgerService.recordRefund(saved, saved.getTotal(), "ADMIN_CANCEL_" + saved.getId());
        }

        return OrderResponseDto.from(saved);
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
    public OrderResponseDto approveReturn(Long orderId) {
        Order order = findById(orderId);

        if (order.getStatus() != OrderStatus.RETURN_REQUESTED) {
            throw new IllegalStateException(
                    "Can only approve returns in RETURN_REQUESTED status. Current: " + order.getStatus());
        }

        ReturnOrder returnOrder = findReturnOrder(order);
        returnOrder.setStatus(ReturnStatus.APPROVED);
        returnOrder.setApprovedAt(LocalDateTime.now());
        returnOrder.setReturnLabel("PENDING");
        returnOrderRepository.save(returnOrder);

        order.setStatus(OrderStatus.RETURN_APPROVED);
        log.info("Return approved for order {}", order.getOrderNumber());
        return OrderResponseDto.from(orderRepository.save(order));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto receiveReturn(Long orderId) {
        Order order = findById(orderId);

        if (order.getStatus() != OrderStatus.RETURN_APPROVED) {
            throw new IllegalStateException(
                    "Can only receive returns in RETURN_APPROVED status. Current: " + order.getStatus());
        }

        ReturnOrder returnOrder = findReturnOrder(order);

        for (ReturnItem item : returnOrder.getItems()) {
            ProductVariant variant = item.getOrderItem().getVariant();
            productVariantRepository.restoreStock(variant.getId(), item.getQuantityReturned());
        }

        returnOrder.setStatus(ReturnStatus.RECEIVED);
        returnOrder.setReceivedAt(LocalDateTime.now());
        returnOrderRepository.save(returnOrder);

        order.setStatus(OrderStatus.RETURN_RECEIVED);
        log.info("Return received for order {} — variant stock restored", order.getOrderNumber());
        return OrderResponseDto.from(orderRepository.save(order));
    }

    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto processRefund(Long orderId, BigDecimal refundAmount) {
        // Validate state before touching Mollie or the DB.
        Order order = findById(orderId);
        if (order.getStatus() != OrderStatus.RETURN_RECEIVED) {
            throw new IllegalStateException(
                    "Can only process refund after return is received. Current: " + order.getStatus());
        }
        if (refundAmount == null || refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        if (refundAmount.compareTo(order.getTotal()) > 0) {
            throw new IllegalArgumentException(
                    "Refund amount " + refundAmount + " exceeds order total " + order.getTotal());
        }

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("No payment record for order " + orderId));

        // Guard: ensure ReturnOrder exists before touching Mollie — an orphaned Mollie
        // refund cannot be rolled back, so we fail fast here instead of inside persist().
        returnOrderRepository.findByOrder_Id(orderId)
                .orElseThrow(() -> new IllegalStateException("No return found for order: " + order.getOrderNumber()));

        // 1. Call the payment provider OUTSIDE any transaction — avoids holding a DB connection
        //    during an HTTP call and separates the external side-effect from the atomic DB commit.
        String refundId;
        try {
            refundId = paymentProvider.refundPayment(new RefundCommand(
                    payment.getTransactionId(),
                    refundAmount,
                    "Refund for order " + order.getOrderNumber())).refundId();
        } catch (Exception e) {
            log.error("Refund failed for order {}: {}", order.getOrderNumber(), e.getMessage());
            throw new PaymentException("Could not process refund: " + e.getMessage(), e);
        }

        // 2. Persist all DB changes atomically in a single @Transactional block.
        //    The refund ID is the idempotency key — duplicate calls are safe.
        return refundPersistenceHelper.persist(orderId, refundAmount, refundId);
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

    private BigDecimal shippingCostForBrand(Long brandId) {
        return brandShippingProfileRepository.findAll().stream()
                .filter(p -> p.getBrandPartner() != null && brandId.equals(p.getBrandPartner().getId()))
                .findFirst()
                .map(BrandShippingProfile::getShippingCost)
                .filter(c -> c != null)
                .orElse(BigDecimal.ZERO);
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

    private ReturnOrder findReturnOrder(Order order) {
        return returnOrderRepository.findByOrder(order)
                .orElseThrow(() -> new IllegalStateException(
                        "No return found for order: " + order.getOrderNumber()));
    }

    private Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));
    }

    @Transactional(readOnly = true)
    public OrderResponseDto getOrderById(Long orderId) {
        Order order = findById(orderId);
        return OrderResponseDto.from(order);
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
}
