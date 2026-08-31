package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.CustomerBrandSpendingDto;
import com.enunas.backend.customer.dto.CustomerOrderStatsDto;
import com.enunas.backend.customer.dto.CustomerOrderStatsRow;
import com.enunas.backend.customer.dto.CustomerResponseDto;
import com.enunas.backend.customer.dto.UpdateCustomerProfileDto;
import com.enunas.backend.exception.CustomerNotFoundException;
import com.enunas.backend.order.OrderItemRepository;
import com.enunas.backend.order.OrderRepository;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.user.AccountErasedEvent;
import com.enunas.backend.user.OAuthAccountRepository;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    /** A customer who has never completed an order — what the batch stats query returns no row for. */
    private static final CustomerOrderStatsDto NO_ORDERS = new CustomerOrderStatsDto(0L, BigDecimal.ZERO);

    /** §355 BGB Widerruf window — an order delivered less than this long ago can still become a
     *  return, so the account behind it is not finished with. Mirrors OrderService's own guard. */
    private static final int RETURN_WINDOW_DAYS = 14;

    private final CustomerRepository customerRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final UserAddressRepository userAddressRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Server-side: create the matching Customer record when a CUSTOMER user signs up. */
    @Transactional
    public Customer createForUser(User user) {
        return createForUser(user, null, null, null);
    }

    /** Overload for signups that arrive with known profile data (e.g. Google OAuth). */
    @Transactional
    public Customer createForUser(User user, String firstName, String lastName, String profileImageUrl) {
        Customer customer = Customer.builder()
                .user(user)
                .firstName(firstName)
                .lastName(lastName)
                .profileImageUrl(profileImageUrl)
                .preferredStyles(new ArrayList<>())
                .favoriteBrands(new ArrayList<>())
                .favoriteCategories(new ArrayList<>())
                .build();
        return customerRepository.save(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponseDto getMyProfile(User user) {
        Customer customer = findByUser(user);
        return CustomerResponseDto.from(customer, orderStatsFor(customer));
    }

    /** Partial update by the authenticated customer. Only non-null fields are applied. */
    @Transactional
    public CustomerResponseDto updateMyProfile(UpdateCustomerProfileDto dto, User user) {
        Customer customer = findByUser(user);
        applyProfileUpdates(customer, dto);
        customer = customerRepository.save(customer);
        return CustomerResponseDto.from(customer, orderStatsFor(customer));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public Page<CustomerResponseDto> getAllCustomers(Pageable pageable) {
        Page<Customer> page = customerRepository.findAll(pageable);
        Map<Long, CustomerOrderStatsDto> statsByUserId = orderStatsFor(page.getContent());
        return page.map(customer -> CustomerResponseDto.from(
                customer, statsByUserId.getOrDefault(customer.getUser().getId(), NO_ORDERS)));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public CustomerResponseDto getCustomerById(Long id) {
        Customer customer = findById(id);
        return CustomerResponseDto.from(customer, orderStatsFor(customer));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<CustomerBrandSpendingDto> getCustomerBrandSpending(Long customerId) {
        Customer customer = findById(customerId);
        // Same status set the profile totals use, so the per-brand breakdown always sums to the
        // totalSpent shown on the customer. The hand-written list this replaces did not: it was
        // missing PARTIALLY_SHIPPED until recently, and is still missing all three escalation
        // statuses, either of which made the breakdown quietly total less than the profile figure.
        return orderItemRepository.findBrandSpendingByUserId(
                customer.getUser().getId(), OrderStatus.PAID_ORDER_STATUSES);
    }

    /**
     * Erases the authenticated customer's personal data (DSGVO Art. 17) while keeping the order
     * history the law requires us to keep (§257 HGB / §147 AO: 10 years for the commercial and tax
     * record). The two obligations do not actually collide here, because order-time identity is
     * already frozen: {@code Order.shippingAddress} is an embedded snapshot taken at checkout, and
     * a return carries its own. Wiping the live profile therefore leaves the retained record
     * complete and readable — nothing downstream re-resolves a name or address from this entity.
     *
     * <p><b>Erased:</b> the Customer profile (name, username, avatar, location, sizes,
     * preferences), every saved address, every OAuth link, and the login identity on User — email
     * replaced with a tombstone, password and any outstanding verification / reset tokens cleared,
     * account disabled.
     *
     * <p><b>Kept:</b> orders, order items and their money snapshots, payments, ledger entries,
     * returns, and the address snapshots on each. That is the record §257 HGB obliges us to hold,
     * and Art. 17(3)(b) exempts it from erasure.
     *
     * <p>Irreversible, and deliberately so — an "anonymisation" a support tool could undo is not
     * anonymisation.
     */
    @Transactional
    @PreAuthorize("hasRole('CUSTOMER')")
    public void eraseMyAccount(User user) {
        Customer customer = findByUser(user);

        if (orderRepository.hasUnsettledOrders(user, LocalDateTime.now().minusDays(RETURN_WINDOW_DAYS))) {
            throw new IllegalStateException(
                    "Dein Konto kann nicht gelöscht werden, solange noch Bestellungen offen sind. "
                    + "Bitte warte, bis sie abgeschlossen, storniert oder erstattet sind.");
        }

        String originalEmail = user.getEmail();

        userAddressRepository.deleteByUser(user);
        oAuthAccountRepository.deleteByUser(user);
        anonymiseProfile(customer);
        customerRepository.save(customer);

        // Tombstone keyed on the user id, not a timestamp: unique by construction, so two erasures
        // in the same millisecond cannot collide on the unique email column. The domain is
        // .invalid (RFC 2606, reserved and unroutable) rather than one we own — a tombstone must
        // never be able to reach a real inbox.
        user.setEmail("geloescht+" + user.getId() + "@deleted.invalid");
        user.setPassword(null);
        user.setEnabled(false);
        user.setVerificationCode(null);
        user.setVerificationCodeExpiresAt(null);
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        userRepository.save(user);

        // Confirmation goes to the address they had before it was cleared, AFTER_COMMIT and
        // best-effort — a bounced mail must not undo an erasure we have already told them is done.
        eventPublisher.publishEvent(new AccountErasedEvent(originalEmail));

        log.info("ACCOUNT_ERASED userId={} customerId={} — profile, addresses and OAuth links removed;"
                + " order history retained per §257 HGB", user.getId(), customer.getId());
    }

    /** Clears every field on the profile that identifies or describes a person. Collections are
     *  emptied rather than nulled: they are {@code @ElementCollection}s, and a null would break the
     *  next read of an entity that is expected to outlive this call. */
    private void anonymiseProfile(Customer customer) {
        customer.setFirstName(null);
        customer.setLastName(null);
        customer.setUsername(null);
        customer.setProfileImageUrl(null);
        customer.setCountry(null);
        customer.setCity(null);
        customer.setPreferredSizeTop(null);
        customer.setPreferredSizeBottom(null);
        customer.setPreferredSizeShoes(null);
        customer.setHeightCm(null);
        customer.setWeightKg(null);
        customer.getPreferredStyles().clear();
        customer.getFavoriteBrands().clear();
        customer.getFavoriteCategories().clear();
    }

    /** Admin partial update — same field semantics as updateMyProfile, but addressed by id. */
    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public CustomerResponseDto updateCustomerByAdmin(Long id, UpdateCustomerProfileDto dto) {
        Customer customer = findById(id);
        applyProfileUpdates(customer, dto);
        customer = customerRepository.save(customer);
        return CustomerResponseDto.from(customer, orderStatsFor(customer));
    }

    /** Order count + total spent across every non-PENDING, non-CANCELLED order — see
     *  {@link OrderStatus#PAID_ORDER_STATUSES} for the exact status semantics. */
    private CustomerOrderStatsDto orderStatsFor(Customer customer) {
        return orderRepository.getOrderStatsByBuyer(customer.getUser(), OrderStatus.PAID_ORDER_STATUSES);
    }

    /**
     * The same figures for a whole page of customers in one query. The per-customer call above,
     * run inside a {@code page.map(...)}, was a query per row — a 20-customer admin page cost 21.
     * Customers with no qualifying orders are absent from the result by design (GROUP BY), so
     * callers fall back to {@link #NO_ORDERS}.
     */
    private Map<Long, CustomerOrderStatsDto> orderStatsFor(List<Customer> customers) {
        List<Long> userIds = customers.stream().map(c -> c.getUser().getId()).toList();
        if (userIds.isEmpty()) return Map.of();
        return orderRepository.getOrderStatsByBuyerIds(userIds, OrderStatus.PAID_ORDER_STATUSES).stream()
                .collect(Collectors.toMap(CustomerOrderStatsRow::userId,
                        row -> new CustomerOrderStatsDto(row.totalOrders(), row.totalSpent())));
    }

    Customer findByUser(User user) {
        return customerRepository.findByUser(user)
                .orElseThrow(() -> new CustomerNotFoundException(
                        "No customer profile found for user: " + user.getEmail()));
    }

    private Customer findById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new CustomerNotFoundException("Customer not found: " + id));
    }

    /** Single source of truth for partial updates — used by both customer and admin paths. */
    private void applyProfileUpdates(Customer customer, UpdateCustomerProfileDto dto) {
        if (dto.getFirstName() != null) customer.setFirstName(dto.getFirstName());
        if (dto.getLastName() != null) customer.setLastName(dto.getLastName());
        if (dto.getUsername() != null) customer.setUsername(dto.getUsername());
        if (dto.getProfileImageUrl() != null) customer.setProfileImageUrl(dto.getProfileImageUrl());

        if (dto.getCountry() != null) customer.setCountry(dto.getCountry());
        if (dto.getCity() != null) customer.setCity(dto.getCity());

        if (dto.getPreferredSizeTop() != null) customer.setPreferredSizeTop(dto.getPreferredSizeTop());
        if (dto.getPreferredSizeBottom() != null) customer.setPreferredSizeBottom(dto.getPreferredSizeBottom());
        if (dto.getPreferredSizeShoes() != null) customer.setPreferredSizeShoes(dto.getPreferredSizeShoes());
        if (dto.getHeightCm() != null) customer.setHeightCm(dto.getHeightCm());
        if (dto.getWeightKg() != null) customer.setWeightKg(dto.getWeightKg());

        if (dto.getPreferredStyles() != null) customer.setPreferredStyles(new ArrayList<>(dto.getPreferredStyles()));
        if (dto.getFavoriteBrands() != null) customer.setFavoriteBrands(new ArrayList<>(dto.getFavoriteBrands()));
        if (dto.getFavoriteCategories() != null) customer.setFavoriteCategories(new ArrayList<>(dto.getFavoriteCategories()));
    }
}
