package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.CustomerBrandSpendingDto;
import com.enunas.backend.customer.dto.CustomerOrderStatsDto;
import com.enunas.backend.customer.dto.CustomerResponseDto;
import com.enunas.backend.customer.dto.UpdateCustomerProfileDto;
import com.enunas.backend.exception.CustomerNotFoundException;
import com.enunas.backend.order.OrderItemRepository;
import com.enunas.backend.order.OrderRepository;
import com.enunas.backend.order.OrderStatus;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

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
        return customerRepository.findAll(pageable)
                .map(customer -> CustomerResponseDto.from(customer, orderStatsFor(customer)));
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
        List<OrderStatus> paidStatuses = List.of(
                OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED,
                OrderStatus.RETURN_REQUESTED, OrderStatus.RETURN_APPROVED,
                OrderStatus.RETURN_RECEIVED, OrderStatus.REFUNDED);
        return orderItemRepository.findBrandSpendingByUserId(customer.getUser().getId(), paidStatuses);
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
     *  OrderRepository.getOrderStatsByBuyer for the exact status semantics. */
    private CustomerOrderStatsDto orderStatsFor(Customer customer) {
        return orderRepository.getOrderStatsByBuyer(customer.getUser());
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
