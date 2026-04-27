package com.enunas.backend.media;

import com.enunas.backend.media.dto.*;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products/{productId}/media")
@RequiredArgsConstructor
public class MediaController {

    private final MediaService mediaService;

    @PostMapping("/images")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductImageResponseDto> addImage(
            @PathVariable Long productId,
            @Valid @RequestBody ProductImageDto dto,
            @AuthenticationPrincipal User owner) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.addImage(productId, dto, owner));
    }

    @GetMapping("/images")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<List<ProductImageResponseDto>> getImages(@PathVariable Long productId) {
        return ResponseEntity.ok(mediaService.getImages(productId));
    }

    @DeleteMapping("/images/{imageId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<Void> deleteImage(
            @PathVariable Long productId,
            @PathVariable Long imageId,
            @AuthenticationPrincipal User owner) {
        mediaService.deleteImage(imageId, owner);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/videos")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductVideoResponseDto> addVideo(
            @PathVariable Long productId,
            @Valid @RequestBody ProductVideoDto dto,
            @AuthenticationPrincipal User owner) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mediaService.addVideo(productId, dto, owner));
    }

    @GetMapping("/videos")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BRAND_PARTNER')")
    public ResponseEntity<List<ProductVideoResponseDto>> getVideos(@PathVariable Long productId) {
        return ResponseEntity.ok(mediaService.getVideos(productId));
    }

    @DeleteMapping("/videos/{videoId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<Void> deleteVideo(
            @PathVariable Long productId,
            @PathVariable Long videoId,
            @AuthenticationPrincipal User owner) {
        mediaService.deleteVideo(videoId, owner);
        return ResponseEntity.noContent().build();
    }
}
