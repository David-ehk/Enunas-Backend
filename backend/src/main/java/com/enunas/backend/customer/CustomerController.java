package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.CustomerResponseDto;
import com.enunas.backend.customer.dto.UpdateCustomerProfileDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/customer")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping("/me")
    public ResponseEntity<CustomerResponseDto> getMyProfile(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(customerService.getMyProfile(user));
    }

    @PatchMapping("/me")
    public ResponseEntity<CustomerResponseDto> updateMyProfile(
            @Valid @RequestBody UpdateCustomerProfileDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(customerService.updateMyProfile(dto, user));
    }
}
