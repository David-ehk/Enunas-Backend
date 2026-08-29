package com.enunas.backend.customer;

import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "customers")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    // Basic profile — populated by the user via PATCH /customer/me; nullable at signup.
    private String firstName;
    private String lastName;
    private String username;
    private String profileImageUrl;

    // Location
    private String country;
    private String city;

    // Size & fit (fashion-specific)
    private String preferredSizeTop;
    private String preferredSizeBottom;
    private String preferredSizeShoes;
    private Integer heightCm;
    private Integer weightKg;

    // Preferences — surfaced to recommendation logic later
    @ElementCollection
    @CollectionTable(name = "customer_preferred_styles", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "style")
    @Builder.Default
    private List<String> preferredStyles = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "customer_favorite_brands", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "brand")
    @Builder.Default
    private List<String> favoriteBrands = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "customer_favorite_categories", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "category")
    @Builder.Default
    private List<String> favoriteCategories = new ArrayList<>();

    // Behavior stats (order count, total spent) are computed on read from Order rows — see
    // CustomerService — never stored on this entity. A denormalized totalOrders/totalSpent pair
    // used to live here but was never wired up to actually update (V26 dropped the dead columns);
    // computing on read has no drift to go stale in the first place.
    //
    // Orders are queried via OrderRepository.findByBuyer(customer.getUser())
    // — no direct FK from Order to Customer, so no @OneToMany mapping here.

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
