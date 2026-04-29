package com.enunas.backend.wardrobe;

import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "wardrobe_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WardrobeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private String imageUrl;

    /** Loose category string (hoodie, pants, shoes, ...). Free-form for MVP. */
    private String category;

    private String color;

    private String brand;

    /** Style tag (streetwear, vintage, minimal, ...). Free-form for MVP. */
    private String styleTag;

    @Builder.Default
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
