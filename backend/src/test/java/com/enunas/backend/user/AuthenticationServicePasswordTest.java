package com.enunas.backend.user;

import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.SetPasswordDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthenticationServicePasswordTest {

    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private BCryptPasswordEncoder passwordEncoder;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        authenticationService = new AuthenticationService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                mock(CustomerService.class),
                mock(OAuthAccountRepository.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void login_googleOnlyAccount_failsCleanlyAsBadCredentials_notAnUnexpectedException() {
        // Simulates the real AuthenticationManager/BCryptPasswordEncoder contract: a null
        // encoded password makes matches() return false, which DaoAuthenticationProvider reports
        // as the same BadCredentialsException it uses for a wrong password (already mapped to
        // 401 by GlobalExceptionHandler.handleBadCredentials). AuthenticationService must
        // propagate it as-is -- no null-password special case, since distinguishing "no
        // password" from "wrong password" pre-authentication would let a caller enumerate which
        // accounts are Google-only.
        LoginUserDto dto = new LoginUserDto();
        dto.setEmail("google-only@example.com");
        dto.setPassword("anything");
        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThatThrownBy(() -> authenticationService.login(dto))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void setPassword_googleOnlyAccount_setsEncodedPassword() {
        User user = User.builder().id(1L).email("google-only@example.com").password(null).role(Role.CUSTOMER).build();
        SetPasswordDto dto = new SetPasswordDto();
        dto.setNewPassword("newSecurePassword1");
        when(passwordEncoder.encode("newSecurePassword1")).thenReturn("encoded-hash");

        authenticationService.setPassword(user, dto);

        assertThat(user.getPassword()).isEqualTo("encoded-hash");
        verify(userRepository).save(user);
    }

    @Test
    void setPassword_accountAlreadyHasPassword_throws() {
        User user = User.builder().id(2L).email("has-password@example.com").password("existing-hash").role(Role.CUSTOMER).build();
        SetPasswordDto dto = new SetPasswordDto();
        dto.setNewPassword("newSecurePassword1");

        assertThatThrownBy(() -> authenticationService.setPassword(user, dto))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any());
    }
}
