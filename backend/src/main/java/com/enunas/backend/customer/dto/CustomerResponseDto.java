package com.enunas.backend.customer.dto;

import com.enunas.backend.customer.Customer;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class CustomerResponseDto {

    private Long id;
    private Long userId;
    private String email;

    private String firstName;
    private String lastName;
    private String username;
    private String profileImageUrl;

    private String country;
    private String city;

    private String preferredSizeTop;
    private String preferredSizeBottom;
    private String preferredSizeShoes;
    private Integer heightCm;
    private Integer weightKg;

    private List<String> preferredStyles;
    private List<String> favoriteBrands;
    private List<String> favoriteCategories;

    private Integer totalOrders;
    private BigDecimal totalSpent;

    private LocalDateTime createdAt;

    public static CustomerResponseDto from(Customer customer) {
        return CustomerResponseDto.builder()
                .id(customer.getId())
                .userId(customer.getUser() != null ? customer.getUser().getId() : null)
                .email(customer.getUser() != null ? customer.getUser().getEmail() : null)
                .firstName(customer.getFirstName())
                .lastName(customer.getLastName())
                .username(customer.getUsername())
                .profileImageUrl(customer.getProfileImageUrl())
                .country(customer.getCountry())
                .city(customer.getCity())
                .preferredSizeTop(customer.getPreferredSizeTop())
                .preferredSizeBottom(customer.getPreferredSizeBottom())
                .preferredSizeShoes(customer.getPreferredSizeShoes())
                .heightCm(customer.getHeightCm())
                .weightKg(customer.getWeightKg())
                .preferredStyles(customer.getPreferredStyles())
                .favoriteBrands(customer.getFavoriteBrands())
                .favoriteCategories(customer.getFavoriteCategories())
                .totalOrders(customer.getTotalOrders())
                .totalSpent(customer.getTotalSpent())
                .createdAt(customer.getCreatedAt())
                .build();
    }
}
