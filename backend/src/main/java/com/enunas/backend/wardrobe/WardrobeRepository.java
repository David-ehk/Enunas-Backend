package com.enunas.backend.wardrobe;

import com.enunas.backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WardrobeRepository extends JpaRepository<WardrobeItem, Long> {

    List<WardrobeItem> findByUserOrderByCreatedAtDesc(User user);

    Optional<WardrobeItem> findByIdAndUser(Long id, User user);
}
