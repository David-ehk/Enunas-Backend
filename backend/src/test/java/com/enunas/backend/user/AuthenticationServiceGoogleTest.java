package com.enunas.backend.user;

import com.enunas.backend.customer.Customer;
import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.user.dto.ForgotPasswordRequestDto;
import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.RegisterUserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class AuthenticationServiceGoogleTest {

    private UserRepository userRepository;
    private OAuthAccountRepository oAuthAccountRepository;
    private CustomerService customerService;
    private AuthenticationManager authenticationManager;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        oAuthAccountRepository = mock(OAuthAccountRepository.class);
        customerService = mock(CustomerService.class);
        authenticationManager = mock(AuthenticationManager.class);
        authenticationService = new AuthenticationService(
                userRepository,
                mock(BCryptPasswordEncoder.class),
                authenticationManager,
                mock(EmailService.class),
                customerService,
                oAuthAccountRepository);
    }

    @Test
    void returningGoogleIdentity_returnsLinkedUser_noNewRowsCreated() {
        User existingUser = User.builder().id(1L).email("jane@example.com").role(Role.CUSTOMER)
                .enabled(true).adminApproved(true).build();
        OAuthAccount link = OAuthAccount.builder().user(existingUser).provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123").build();
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(link));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-123", "jane@example.com", true, "Jane", "Doe", null);
        User result = authenticationService.loginWithGoogle(payload);

        assertThat(result).isEqualTo(existingUser);
        verify(userRepository, never()).save(any());
        verify(oAuthAccountRepository, never()).save(any());
    }

    @Test
    void newGoogleIdentity_matchingExistingEmail_linksToExistingUser() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-456"))
                .thenReturn(Optional.empty());
        User existingUser = User.builder().id(2L).email("john@example.com").password("hashed").role(Role.CUSTOMER)
                .enabled(true).adminApproved(true).build();
        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(existingUser));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-456", "John@Example.com", true, "John", "Doe", null);
        User result = authenticationService.loginWithGoogle(payload);

        assertThat(result).isEqualTo(existingUser);
        verify(oAuthAccountRepository).save(argThat(a ->
                a.getUser() == existingUser && a.getProvider() == OAuthProvider.GOOGLE
                        && a.getProviderUserId().equals("google-sub-456")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void newGoogleIdentity_matchingExistingEmail_unverifiedEmail_throws() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-789"))
                .thenReturn(Optional.empty());
        User existingUser = User.builder().id(3L).email("unverified@example.com").role(Role.CUSTOMER).build();
        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(existingUser));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-789", "unverified@example.com", false, "A", "B", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalArgumentException.class);
        verify(oAuthAccountRepository, never()).save(any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    void brandNewGoogleUser_createsCustomerRoleOnly_passwordNull_emailNormalized() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-999"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customerService.createForUser(any(User.class), any(), any(), any()))
                .thenReturn(mock(Customer.class));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-999", "  New@Example.com  ", true, "New", "User", "https://pic");
        User result = authenticationService.loginWithGoogle(payload);

        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(result.getPassword()).isNull();
        assertThat(result.isEnabled()).isTrue();
        verify(customerService).createForUser(result, "New", "User", "https://pic");
        verify(oAuthAccountRepository).save(argThat(a ->
                a.getProvider() == OAuthProvider.GOOGLE && a.getProviderUserId().equals("google-sub-999")));
    }

    // ===== C2: email_verified gates the brand-new-signup path too, not just the link path =====

    @Test
    void brandNewGoogleUser_unverifiedEmail_throws_andCreatesNothing() {
        // Without this gate, someone holding an UNVERIFIED Google address could pre-empt an
        // account at an email they don't control, then /auth/set-password to own it permanently.
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-evil"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("victim@example.com")).thenReturn(Optional.empty());

        GoogleTokenPayload payload =
                new GoogleTokenPayload("google-sub-evil", "victim@example.com", false, "E", "V", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not verified");
        verify(userRepository, never()).save(any());
        verify(oAuthAccountRepository, never()).save(any());
        verify(customerService, never()).createForUser(any(), any(), any(), any());
    }

    @Test
    void returningGoogleIdentity_unverifiedEmailClaim_stillSignsIn() {
        // The link was established from a previously-verified email; a returning login must NOT
        // re-require email_verified (the sub is the stronger, already-trusted signal).
        User existingUser = User.builder().id(9L).email("linked@example.com").role(Role.CUSTOMER)
                .enabled(true).adminApproved(true).build();
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-linked"))
                .thenReturn(Optional.of(OAuthAccount.builder().user(existingUser)
                        .provider(OAuthProvider.GOOGLE).providerUserId("google-sub-linked").build()));

        GoogleTokenPayload payload =
                new GoogleTokenPayload("google-sub-linked", "linked@example.com", false, "L", "U", null);

        assertThat(authenticationService.loginWithGoogle(payload)).isEqualTo(existingUser);
    }

    // ===== C1: enabled/adminApproved gates apply to every path returning an EXISTING user =====

    @Test
    void returningGoogleIdentity_disabledAccount_throws() {
        User disabled = User.builder().id(10L).email("disabled@example.com").role(Role.CUSTOMER)
                .enabled(false).adminApproved(true).build();
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "sub-disabled"))
                .thenReturn(Optional.of(OAuthAccount.builder().user(disabled)
                        .provider(OAuthProvider.GOOGLE).providerUserId("sub-disabled").build()));

        GoogleTokenPayload payload =
                new GoogleTokenPayload("sub-disabled", "disabled@example.com", true, "D", "U", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    void returningGoogleIdentity_pendingAdminApproval_throws() {
        User pending = User.builder().id(11L).email("pending@example.com").role(Role.BRAND_PARTNER)
                .enabled(true).adminApproved(false).build();
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "sub-pending"))
                .thenReturn(Optional.of(OAuthAccount.builder().user(pending)
                        .provider(OAuthProvider.GOOGLE).providerUserId("sub-pending").build()));

        GoogleTokenPayload payload =
                new GoogleTokenPayload("sub-pending", "pending@example.com", true, "P", "U", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending admin approval");
    }

    @Test
    void linkByEmail_disabledAccount_throws_andLinksNothing() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "sub-link-disabled"))
                .thenReturn(Optional.empty());
        User disabled = User.builder().id(12L).email("banned@example.com").password("hashed").role(Role.CUSTOMER)
                .enabled(false).adminApproved(true).build();
        when(userRepository.findByEmail("banned@example.com")).thenReturn(Optional.of(disabled));

        GoogleTokenPayload payload =
                new GoogleTokenPayload("sub-link-disabled", "Banned@Example.com", true, "B", "U", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");
        verify(oAuthAccountRepository, never()).save(any());
    }

    @Test
    void linkByEmail_brandPartnerPendingApproval_throws_andLinksNothing() {
        // The exploit: POST /brandpartner/apply creates enabled=true, adminApproved=false.
        // login() blocks it; /auth/google must block it too, not hand out a BRAND_PARTNER JWT.
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "sub-link-pending"))
                .thenReturn(Optional.empty());
        User applicant = User.builder().id(13L).email("applicant@example.com").password("hashed")
                .role(Role.BRAND_PARTNER).enabled(true).adminApproved(false).build();
        when(userRepository.findByEmail("applicant@example.com")).thenReturn(Optional.of(applicant));

        GoogleTokenPayload payload =
                new GoogleTokenPayload("sub-link-pending", "applicant@example.com", true, "A", "U", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending admin approval");
        verify(oAuthAccountRepository, never()).save(any());
    }

    // ===== I4: signup normalizes email before the duplicate check =====

    @Test
    void signup_differentlyCasedEmail_isRejectedAsDuplicate_notCreatedTwice() {
        // A Google account already exists at the normalized "test@example.com". A later password
        // signup as "Test@Example.com" must collide, not silently create a second row.
        when(userRepository.existsByEmail("test@example.com")).thenReturn(true);
        RegisterUserDto dto = new RegisterUserDto();
        dto.setEmail("  Test@Example.com  ");
        dto.setPassword("somePassword1");

        assertThatThrownBy(() -> authenticationService.signup(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Email already registered");
        verify(userRepository, never()).save(any());
    }

    @Test
    void signup_persistsNormalizedEmail() {
        when(userRepository.existsByEmail("mixed@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        RegisterUserDto dto = new RegisterUserDto();
        dto.setEmail("  Mixed@Example.COM ");
        dto.setPassword("somePassword1");

        User created = authenticationService.signup(dto);

        assertThat(created.getEmail()).isEqualTo("mixed@example.com");
    }

    // ===== N1: login/forgotPassword normalize email on READ, matching I4's normalize-on-WRITE =====

    @Test
    void login_mixedCaseAndPaddedEmail_authenticatesAndLooksUpWithNormalizedEmail() {
        // A user stored as "normalized@example.com" (I4) logging in with the exact string they
        // registered with, "  Normalized@Example.com  ", must succeed -- both the
        // AuthenticationManager call (which DaoAuthenticationProvider's UserDetailsService also
        // resolves via findByEmail) and this method's own findByEmail must agree on one
        // normalized string, or they'd resolve to mismatched/nonexistent accounts.
        User user = User.builder().id(1L).email("normalized@example.com").role(Role.CUSTOMER)
                .enabled(true).adminApproved(true).build();
        when(userRepository.findByEmail("normalized@example.com")).thenReturn(Optional.of(user));

        LoginUserDto dto = new LoginUserDto();
        dto.setEmail("  Normalized@Example.com  ");
        dto.setPassword("somePassword1");

        User result = authenticationService.login(dto);

        assertThat(result).isEqualTo(user);
        verify(authenticationManager).authenticate(argThat(token ->
                "normalized@example.com".equals(token.getPrincipal())));
    }

    @Test
    void forgotPassword_mixedCaseEmail_looksUpByNormalizedEmail() {
        User user = User.builder().id(2L).email("reset@example.com").role(Role.CUSTOMER)
                .enabled(true).adminApproved(true).build();
        when(userRepository.findByEmail("reset@example.com")).thenReturn(Optional.of(user));

        ForgotPasswordRequestDto dto = new ForgotPasswordRequestDto();
        ReflectionTestUtils.setField(dto, "email", "  Reset@Example.com  ");

        authenticationService.forgotPassword(dto);

        verify(userRepository).findByEmail("reset@example.com");
        verify(userRepository, never()).findByEmail("  Reset@Example.com  ");
        verify(userRepository).save(user);
    }
}
