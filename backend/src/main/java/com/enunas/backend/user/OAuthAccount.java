package com.enunas.backend.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Links an external OAuth identity to an Enunas {@link User}. A user must never be left without
 * an authentication method (a password AND/OR at least one OAuthAccount). Nothing in this plan
 * removes a password or an OAuthAccount — no unlink/remove-password endpoint exists yet — so
 * that invariant holds today by construction. If an unlink-provider or remove-password endpoint
 * is ever built, it MUST check {@code user.getPassword() != null || oAuthAccountRepository
 * .existsByUser(user)} before allowing the removal, or an account could end up with zero ways to
 * log in.
 */
@Entity
@Table(name = "oauth_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
