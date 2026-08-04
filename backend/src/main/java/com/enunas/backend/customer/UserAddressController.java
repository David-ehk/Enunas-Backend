package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.UserAddressDto;
import com.enunas.backend.customer.dto.UserAddressResponseDto;
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
@RequestMapping("/customer/addresses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class UserAddressController {

    private final UserAddressService userAddressService;

    @GetMapping
    public ResponseEntity<List<UserAddressResponseDto>> getMyAddresses(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userAddressService.getMyAddresses(user));
    }

    @PostMapping
    public ResponseEntity<UserAddressResponseDto> createAddress(
            @Valid @RequestBody UserAddressDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAddressService.createAddress(dto, user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserAddressResponseDto> updateAddress(
            @PathVariable Long id,
            @Valid @RequestBody UserAddressDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userAddressService.updateAddress(id, dto, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        userAddressService.deleteAddress(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<UserAddressResponseDto> setDefault(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userAddressService.setDefault(id, user));
    }
}
