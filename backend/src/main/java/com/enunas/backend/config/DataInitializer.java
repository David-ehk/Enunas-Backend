package com.enunas.backend.config;

import com.enunas.backend.user.EmailNormalizer;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.email}")
    private String adminEmail;

    @Value("${admin.password}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        // ADMIN_EMAIL is operator-supplied and may carry padding or uppercase. Everything else
        // (login, password reset, ...) looks users up by the normalized form, so seeding a raw
        // value would create a second, unreachable ADMIN row on every differently-cased restart.
        String normalizedAdminEmail = EmailNormalizer.normalize(adminEmail);
        if (!userRepository.existsByEmail(normalizedAdminEmail)) {
            User admin = User.builder()
                    .email(normalizedAdminEmail)
                    .password(passwordEncoder.encode(adminPassword))
                    .role(Role.ADMIN)
                    .enabled(true)
                    .adminApproved(true)
                    .build();
            userRepository.save(admin);
            log.info("Admin account created for {}", normalizedAdminEmail);
        } else {
            log.info("Admin account already exists for {}", normalizedAdminEmail);
        }
    }
}
