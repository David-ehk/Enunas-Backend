package com.enunas.backend.user;

import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.RegisterUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;

    /**
     * Customer signup. Always creates a CUSTOMER, immediately active. Brand applications
     * use a separate flow ({@code POST /brandpartner/apply}) and never touch this method.
     */
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

        // Brand-partner gating: blocked until email-verified AND admin-approved.
        if (user.getRole() == Role.BRAND_PARTNER) {
            if (!user.isEnabled()) {
                log.warn("Login failed: email not verified for {}", input.getEmail());
                throw new IllegalStateException("Please verify your email first. Check your inbox for the verification code.");
            }
            if (!user.isAdminApproved()) {
                log.warn("Login failed: admin approval pending for {}", input.getEmail());
                throw new IllegalStateException("Your account is pending admin approval. You will receive an email once approved.");
            }
        }

        log.info("User logged in: {} with role: {}", user.getEmail(), user.getRole());
        return user;
    }
}
