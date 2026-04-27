package com.enunas.backend.order;

import com.enunas.backend.exception.OrderNotFoundException;
import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReturnRequestDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productlisting.ProductListingRepository;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ORDER_NUM_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ProductListingRepository productListingRepository;
    private final PaymentRepository paymentRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final EmailService emailService;

    // ===== Customer =====

    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderResponseDto createOrder(CreateOrderDto dto, User buyer) {
        List<ProductListing> listings = resolveAndValidateListings(dto.getItems());

        for (OrderItemRequestDto itemDto : dto.getItems()) {
            int updated = productListingRepository.decrementStock(itemDto.getListingId(), itemDto.getQuantity());
            if (updated == 0) {
                ProductListing pl = productListingRepository.findById(itemDto.getListingId()).orElseThrow();
                throw new IllegalStateException(
                        "Insufficient stock for: " + pl.getProduct().getName() +
                        " (" + pl.getVariant().getColor() + " / " + pl.getVariant().getSize() + ")" +
                        " — requested: " + itemDto.getQuantity() + ", available: " + pl.getStock());
            }
        }

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal shippingTotal = BigDecimal.ZERO;

        for (int i = 0; i < dto.getItems().size(); i++) {
            OrderItemRequestDto itemDto = dto.getItems().get(i);
            ProductListing pl = listings.get(i);

            BigDecimal effectivePrice = pl.getDiscountPrice() != null ? pl.getDiscountPrice() : pl.getPrice();
            BigDecimal shippingCost = pl.getShippingCost() != null ? pl.getShippingCost() : BigDecimal.ZERO;
            BigDecimal lineTotal = effectivePrice.multiply(BigDecimal.valueOf(itemDto.getQuantity()));

            subtotal = subtotal.add(lineTotal);
            shippingTotal = shippingTotal.add(shippingCost);

            orderItems.add(OrderItem.builder()
                    .productListing(pl)
                    .productName(pl.getProduct().getName())
                    .variantSku(pl.getVariant().getSku())
                    .variantColor(pl.getVariant().getColor())
                    .variantSize(pl.getVariant().getSize())
                    .priceAtPurchase(pl.getPrice())
                    .discountPriceAtPurchase(pl.getDiscountPrice())
                    .shippingCostAtPurchase(shippingCost)
                    .quantity(itemDto.getQuantity())
                    .lineTotal(lineTotal)
                    .build());
        }

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

        paymentRepository.save(Payment.builder()
                .order(saved)
                .amount(saved.getTotal())
                .currency(saved.getCurrency())
                .build());

        log.info("Order created: {} for buyer: {}", saved.getOrderNumber(), buyer.getEmail());
        return OrderResponseDto.from(saved);
    }

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

        validateForwardTransition(current, newStatus);

        // If admin cancels an order that was already PAID (goods never shipped), restore stock
        if (newStatus == OrderStatus.CANCELLED &&
                (current == OrderStatus.PAID ||
                 current == OrderStatus.SHIPPING_PROBLEM ||
                 current == OrderStatus.AWAITING_ADMIN ||
                 current == OrderStatus.MANUAL_REVIEW)) {
            for (OrderItem item : order.getItems()) {
                if (item.getProductListing() != null) {
                    productListingRepository.restoreStock(item.getProductListing().getId(), item.getQuantity());
                }
            }
        }

        order.setStatus(newStatus);
        log.info("Order {} status: {} → {}", order.getOrderNumber(), current, newStatus);
        return OrderResponseDto.from(orderRepository.save(order));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto cancelOrder(Long orderId, CancelOrderDto dto, User admin) {
        Order order = findById(orderId);

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException(
                    "Only PENDING orders can be cancelled. Current status: " + order.getStatus());
        }

        for (OrderItem item : order.getItems()) {
            if (item.getProductListing() != null) {
                productListingRepository.restoreStock(item.getProductListing().getId(), item.getQuantity());
            }
        }

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
            if (item.getOrderItem().getProductListing() != null) {
                productListingRepository.restoreStock(
                        item.getOrderItem().getProductListing().getId(),
                        item.getQuantityReturned());
            }
        }

        returnOrder.setStatus(ReturnStatus.RECEIVED);
        returnOrder.setReceivedAt(LocalDateTime.now());
        returnOrderRepository.save(returnOrder);

        order.setStatus(OrderStatus.RETURN_RECEIVED);
        log.info("Return received for order {} — stock restored", order.getOrderNumber());
        return OrderResponseDto.from(orderRepository.save(order));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public OrderResponseDto processRefund(Long orderId, BigDecimal refundAmount) {
        Order order = findById(orderId);

        if (order.getStatus() != OrderStatus.RETURN_RECEIVED) {
            throw new IllegalStateException(
                    "Can only process refund after return is received. Current: " + order.getStatus());
        }

        ReturnOrder returnOrder = findReturnOrder(order);
        returnOrder.setStatus(ReturnStatus.REFUNDED);
        returnOrder.setRefundedAt(LocalDateTime.now());
        returnOrder.setRefundAmount(refundAmount);
        returnOrderRepository.save(returnOrder);

        order.setStatus(OrderStatus.REFUNDED);
        log.info("Refund of {} processed for order {}", refundAmount, order.getOrderNumber());
        return OrderResponseDto.from(orderRepository.save(order));
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
            if (pl.getStock() < item.getQuantity()) {
                throw new IllegalStateException(
                        "Insufficient stock for listing " + pl.getId() +
                        " — requested: " + item.getQuantity() + ", available: " + pl.getStock());
            }

            listings.add(pl);
        }
        return listings;
    }

    private void validateForwardTransition(OrderStatus from, OrderStatus to) {
        boolean valid = switch (from) {
            case PENDING         -> to == OrderStatus.PAID;
            case PAID            -> to == OrderStatus.SHIPPED;
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

    private void assertBrandOwnership(Order order, User brandPartner) {
        boolean isOwner = order.getItems().stream()
                .anyMatch(item -> item.getProductListing() != null &&
                        item.getProductListing().getProduct().getCreator().getId()
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
