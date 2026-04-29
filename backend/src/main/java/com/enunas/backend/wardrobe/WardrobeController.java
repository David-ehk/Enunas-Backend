package com.enunas.backend.wardrobe;

import com.enunas.backend.user.User;
import com.enunas.backend.wardrobe.dto.CreateWardrobeItemDto;
import com.enunas.backend.wardrobe.dto.WardrobeItemResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/wardrobe")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class WardrobeController {

    private final WardrobeService wardrobeService;

    @PostMapping
    public ResponseEntity<WardrobeItemResponseDto> createItem(
            @Valid @RequestBody CreateWardrobeItemDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(wardrobeService.createItem(dto, user));
    }

    @GetMapping
    public ResponseEntity<List<WardrobeItemResponseDto>> getMyWardrobe(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(wardrobeService.getMyWardrobe(user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteItem(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        wardrobeService.deleteItem(id, user);
        return ResponseEntity.noContent().build();
    }
}
