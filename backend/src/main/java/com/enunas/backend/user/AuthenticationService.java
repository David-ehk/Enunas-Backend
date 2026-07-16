package com.enunas.backend.user;

import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.user.dto.ChangePasswordDto;
import com.enunas.backend.user.dto.ForgotPasswordRequestDto;
import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.RegisterUserDto;
import com.enunas.backend.user.dto.ResetPasswordDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final CustomerService customerService;

    /**
     * Customer signup. Always creates a CUSTOMER, immediately active, with an empty
     * Customer profile created in the same transaction. Brand applications use a
     * separate flow ({@code POST /brandpartner/apply}) and never touch this method.
     */
    @Transactional
    public User signup(RegisterUserDto input) {
        if (userRepository.existsByEmail(input.getEmail())) {
            log.warn("Signup failed: email already registered: {}", input.getEmail());
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .email(input.getEmail())
                .password(passwordEncoder.encode(input.getPassword()))
                .role(Role.CUSTOMER)
                .enabled(true)
                .adminApproved(true)
                .build();
        userRepository.save(user);

        customerService.createForUser(user);

        emailService.sendWelcomeEmail(user.getEmail(), user.getEmail());
        log.info("Customer registered and active: {}", user.getEmail());
        return user;
    }

    public User login(LoginUserDto input) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(input.getEmail(), input.getPassword())
        );

        User user = userRepository.findByEmail(input.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        // Gate all roles on enabled + adminApproved — not just BRAND_PARTNER.
        if (!user.isEnabled()) {
            log.warn("Login failed: account disabled for {}", input.getEmail());
            if (user.getRole() == Role.BRAND_PARTNER) {
                throw new IllegalStateException("Please verify your email first. Check your inbox for the verification code.");
            }
            throw new IllegalStateException("Your account has been disabled. Please contact support.");
        }
        if (!user.isAdminApproved()) {
            log.warn("Login failed: admin approval pending for {}", input.getEmail());
            throw new IllegalStateException("Your account is pending admin approval. You will receive an email once approved.");
        }

        log.info("User logged in: {} with role: {}", user.getEmail(), user.getRole());
        return user;
    }

    @Transactional
    public void changePassword(User currentUser, ChangePasswordDto dto) {
        if (!passwordEncoder.matches(dto.getCurrentPassword(), currentUser.getPassword())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        currentUser.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(currentUser);
        log.info("Password changed for user: {}", currentUser.getEmail());
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequestDto dto) {
        userRepository.findByEmail(dto.getEmail()).ifPresent(user -> {
            String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            user.setPasswordResetToken(code);
            user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(15));
            userRepository.save(user);
            emailService.sendPasswordResetEmail(user.getEmail(), code);
            log.info("Password reset code sent to: {}", user.getEmail());
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordDto dto) {
        User user = userRepository.findByPasswordResetToken(dto.getToken())
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired reset token"));
        if (user.getPasswordResetExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Reset token has expired");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setPasswordResetToken(null);
        user.setPasswordResetExpiresAt(null);
        userRepository.save(user);
        log.info("Password reset successfully for user: {}", user.getEmail());
    }
}
