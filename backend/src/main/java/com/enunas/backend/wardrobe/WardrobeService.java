package com.enunas.backend.wardrobe;

import com.enunas.backend.user.User;
import com.enunas.backend.wardrobe.dto.CreateWardrobeItemDto;
import com.enunas.backend.wardrobe.dto.WardrobeItemResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WardrobeService {

    private final WardrobeRepository wardrobeRepository;

    @Transactional
    public WardrobeItemResponseDto createItem(CreateWardrobeItemDto dto, User user) {
        WardrobeItem item = WardrobeItem.builder()
                .user(user)
                .imageUrl(dto.getImageUrl())
                .category(dto.getCategory())
                .color(dto.getColor())
                .brand(dto.getBrand())
                .styleTag(dto.getStyleTag())
                .isPublic(dto.isPublic())
                .build();
        return WardrobeItemResponseDto.from(wardrobeRepository.save(item));
    }

    @Transactional(readOnly = true)
    public List<WardrobeItemResponseDto> getMyWardrobe(User user) {
        return wardrobeRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(WardrobeItemResponseDto::from)
                .toList();
    }

    /** Ownership-enforced delete. Throws AccessDeniedException → 403 if item belongs to another user. */
    @Transactional
    public void deleteItem(Long itemId, User user) {
        WardrobeItem item = wardrobeRepository.findById(itemId)
                .orElseThrow(() -> new IllegalArgumentException("Wardrobe item not found: " + itemId));
        if (!item.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("You may only delete your own wardrobe items");
        }
        wardrobeRepository.delete(item);
    }
}
