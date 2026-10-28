package com.talentai.auth.service;

import com.talentai.auth.dto.AuthDtos.*;
import com.talentai.auth.security.AppUserDetailsService;
import com.talentai.auth.security.JwtService;
import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final AppUserDetailsService userDetailsService;

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        AppUser user = appUserRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new IllegalStateException("User not found after authentication"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtService.generateToken(userDetails);

        return AuthResponse.builder()
                .token(token)
                .username(user.getUsername())
                .fullName(user.getFullName())
                .role(user.getRole())
                .build();
    }

    public MeResponse me(String username) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return MeResponse.builder().username(user.getUsername()).fullName(user.getFullName()).role(user.getRole()).build();
    }

    public void changePassword(String username, ChangePasswordRequest request) {
        AppUser user = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("User not found"));
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("The current password is not correct.");
        }
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        appUserRepository.save(user);
    }
}
