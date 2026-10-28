package com.talentai.auth.controller;

import com.talentai.auth.dto.AuthDtos.*;
import com.talentai.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** Accounts are created by an administrator (see UserAdminController); there is no self-registration. */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(java.security.Principal principal) {
        return ResponseEntity.ok(authService.me(principal.getName()));
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               java.security.Principal principal) {
        authService.changePassword(principal.getName(), request);
        return ResponseEntity.noContent().build();
    }
}
