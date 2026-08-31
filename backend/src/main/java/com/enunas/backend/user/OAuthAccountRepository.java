package com.enunas.backend.user;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    @EntityGraph(attributePaths = "user")
    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    boolean existsByUser(User user);

    /** Erasure (DSGVO Art. 17). Without this an erased account is not actually erased: the Google
     *  link survives, and the next "sign in with Google" resolves the same provider id straight
     *  back onto the anonymised user, reviving it under the tombstone address. */
    void deleteByUser(User user);
}
