package com.enunas.backend.config;

import com.enunas.backend.user.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
public class ApplicationConfiguration {

    private final UserRepository userRepository;

    public ApplicationConfiguration(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Programmatic transaction control, for the cases where a method must COMMIT partway through
     * rather than run start-to-finish in one transaction. OrderService.createOrder is the reason it
     * exists: it has to make the order durable before calling Mollie, and @Transactional can only
     * wrap a whole method — a self-call to a second @Transactional method on the same bean is not
     * intercepted by the proxy at all.
     */
    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    AuthenticationProvider authenticationProvider() {
        // UserDetailsService DIREKT im Konstruktor übergeben, wegen SpringSecurity 6.5+!
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService());
        authProvider.setPasswordEncoder(passwordEncoder());
        // Skip DaoAuthenticationProvider's own default account-status pre-check
        // (AccountStatusUserDetailsChecker). It runs BEFORE password verification and would throw
        // DisabledException for user.isEnabled() == false (e.g. an unverified brand partner) —
        // GlobalExceptionHandler has no handler for that Spring Security type, so it fell through
        // to a bare 500, and it pre-empted AuthenticationService.assertAccountActive's own,
        // already-written, role-aware "please verify your email" message, which could never fire.
        // Password verification still happens either way (an unverified account with a WRONG
        // password still correctly gets BadCredentialsException, never silently authenticated) —
        // this only defers the enabled/approved decision to assertAccountActive, which runs right
        // after authenticate() succeeds and produces the correct message and status.
        authProvider.setPreAuthenticationChecks(userDetails -> { /* no-op — see comment above */ });
        return authProvider;
    }
}
