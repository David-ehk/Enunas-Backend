package com.enunas.backend.user;

import com.enunas.backend.user.dto.LoginResponseDto;
import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.RegisterUserDto;
import com.enunas.backend.user.dto.UserResponseDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationService authenticationService;
    private final JwtService jwtService;

    /** Customer signup. Brand-partner applications go to POST /brandpartner/apply. */
    @PostMapping("/signup")
    public ResponseEntity<UserResponseDto> signup(@Valid @RequestBody RegisterUserDto dto) {
        User user = authenticationService.signup(dto);
        return ResponseEntity.ok(UserResponseDto.from(user));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponseDto> login(@Valid @RequestBody LoginUserDto dto) {
        User user = authenticationService.login(dto);

        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("role", user.getRole().name());

        String token = jwtService.generateToken(extraClaims, user);
        return ResponseEntity.ok(LoginResponseDto.builder()
                .token(token)
                .expiresIn(jwtService.getExpirationTime())
                .build());
    }
}
