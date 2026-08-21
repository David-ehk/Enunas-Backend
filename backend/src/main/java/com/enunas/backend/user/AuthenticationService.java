package com.enunas.backend.user;

import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.user.dto.ChangePasswordDto;
import com.enunas.backend.user.dto.ForgotPasswordRequestDto;
import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.RegisterUserDto;
import com.enunas.backend.user.dto.ResetPasswordDto;
import com.enunas.backend.user.dto.SetPasswordDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final CustomerService customerService;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * Customer signup. Always creates a CUSTOMER, immediately active, with an empty
     * Customer profile created in the same transaction. Brand applications use a
     * separate flow ({@code POST /brandpartner/apply}) and never touch this method.
     */
    @Transactional
    public User signup(RegisterUserDto input) {
        // Normalize BEFORE the duplicate check: users.email is case-sensitive in Postgres, and the
        // Google flow already stores normalized emails — without this, "John@Example.com" would
        // slip past existsByEmail and create a second row for the same person.
        String normalizedEmail = EmailNormalizer.normalize(input.getEmail());
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Signup failed: email already registered: {}", normalizedEmail);
            throw new IllegalArgumentException("Email already registered");
        }

        User user = User.builder()
                .email(normalizedEmail)
                .password(passwordEncoder.encode(input.getPassword()))
                .role(Role.CUSTOMER)
                .enabled(true)
                .adminApproved(true)
                .build();
        userRepository.save(user);

        customerService.createForUser(user);

        // Best-effort welcome email, dispatched AFTER_COMMIT — never blocks/rolls back the signup.
        applicationEventPublisher.publishEvent(new WelcomeEmailEvent(user.getEmail()));
        log.info("Customer registered and active: {}", user.getEmail());
        return user;
    }

    public User login(LoginUserDto input) {
        // Normalize once and reuse for BOTH calls below: DaoAuthenticationProvider's
        // UserDetailsService (see ApplicationConfiguration) also does findByEmail — if these two
        // calls disagreed on the string, authentication could succeed against one account while
        // this method resolves a different one.
        String normalizedEmail = EmailNormalizer.normalize(input.getEmail());
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(normalizedEmail, input.getPassword())
        );

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        assertAccountActive(user);

        log.info("User logged in: {} with role: {}", user.getEmail(), user.getRole());
        return user;
    }

    /**
     * Gate all roles on enabled + adminApproved — not just BRAND_PARTNER. Applies to EVERY
     * authentication path that resolves an existing account (password login AND Google Sign-In),
     * so a disabled or not-yet-approved account can never obtain a JWT through a side door.
     */
    private void assertAccountActive(User user) {
        if (!user.isEnabled()) {
            log.warn("Login failed: account disabled for {}", user.getEmail());
            if (user.getRole() == Role.BRAND_PARTNER) {
                throw new IllegalStateException("Please verify your email first. Check your inbox for the verification code.");
            }
            throw new IllegalStateException("Your account has been disabled. Please contact support.");
        }
        if (!user.isAdminApproved()) {
            log.warn("Login failed: admin approval pending for {}", user.getEmail());
            throw new IllegalStateException("Your account is pending admin approval. You will receive an email once approved.");
        }
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

    /** Lets a Google-only account (password == null) add a password, enabling email/password
     *  login alongside Google Sign-In. A user who already has a password uses changePassword
     *  instead (which requires knowing the current one — a different, already-correct flow). */
    @Transactional
    public void setPassword(User currentUser, SetPasswordDto dto) {
        if (currentUser.getPassword() != null) {
            throw new IllegalStateException(
                    "This account already has a password — use change-password to update it.");
        }
        currentUser.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(currentUser);
        log.info("Password set for Google-only account: {}", currentUser.getEmail());
    }

    @Transactional
    public void forgotPassword(ForgotPasswordRequestDto dto) {
        userRepository.findByEmail(EmailNormalizer.normalize(dto.getEmail())).ifPresent(user -> {
            String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
            user.setPasswordResetToken(code);
            user.setPasswordResetExpiresAt(LocalDateTime.now().plusMinutes(15));
            userRepository.save(user);
            // Best-effort reset email, dispatched AFTER_COMMIT — never blocks/rolls back the token write.
            applicationEventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getEmail(), code));
            log.info("Password reset code requested for: {}", user.getEmail());
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

    /**
     * Resolves a verified Google identity to a {@link User}, in priority order: (1) a returning
     * Google identity (matched by provider + providerUserId — Google's stable {@code sub} claim,
     * which never changes even if the person changes their Google email) — this path trusts the
     * previously-established link and deliberately does NOT re-check {@code email_verified};
     * (2) otherwise Google's own {@code email_verified} claim must be true, gating BOTH remaining
     * paths: an unverified Google email may neither auto-link to an existing account nor create a
     * brand-new one (otherwise someone holding an unverified Google address could pre-empt an
     * account at an address they don't control); (3) an existing account with a matching
     * normalized email is linked; (4) otherwise, a brand-new signup. Always creates
     * {@code role = CUSTOMER}, never derived from the token — Google Sign-In can never create a
     * BRAND_PARTNER or ADMIN account. Every path that returns an EXISTING user is additionally
     * gated on {@code enabled} + {@code adminApproved}, exactly like password login.
     */
    @Transactional
    public User loginWithGoogle(GoogleTokenPayload payload) {
        Optional<OAuthAccount> existingLink =
                oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, payload.subject());
        if (existingLink.isPresent()) {
            User linkedUser = existingLink.get().getUser();
            assertAccountActive(linkedUser);
            log.info("Google login: returning user via existing link, subject={}", payload.subject());
            return linkedUser;
        }

        String normalizedEmail = EmailNormalizer.normalize(payload.email());

        // Gates BOTH the link-to-existing-account path AND the brand-new-signup path.
        if (!payload.emailVerified()) {
            log.warn("Google login: refusing unverified Google email {}", normalizedEmail);
            throw new IllegalArgumentException(
                    "Google account email is not verified — cannot sign in or link an account");
        }

        Optional<User> existingUser = userRepository.findByEmail(normalizedEmail);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            assertAccountActive(user);
            oAuthAccountRepository.save(OAuthAccount.builder()
                    .user(user)
                    .provider(OAuthProvider.GOOGLE)
                    .providerUserId(payload.subject())
                    .build());
            log.info("Google login: linked new Google identity to existing user {}", user.getEmail());
            return user;
        }

        User user = User.builder()
                .email(normalizedEmail)
                .password(null)
                .role(Role.CUSTOMER)
                .enabled(true)
                .adminApproved(true)
                .build();
        userRepository.save(user);

        customerService.createForUser(user, payload.firstName(), payload.lastName(), payload.pictureUrl());

        oAuthAccountRepository.save(OAuthAccount.builder()
                .user(user)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId(payload.subject())
                .build());

        // Best-effort welcome email, dispatched AFTER_COMMIT — never blocks/rolls back the signup.
        applicationEventPublisher.publishEvent(new WelcomeEmailEvent(user.getEmail()));
        log.info("Customer registered via Google: {}", user.getEmail());
        return user;
    }
}
