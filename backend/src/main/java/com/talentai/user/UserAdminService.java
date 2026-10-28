package com.talentai.user;

import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.user.UserDtos.CreateUserRequest;
import com.talentai.user.UserDtos.ResetPasswordRequest;
import com.talentai.user.UserDtos.UpdateUserRequest;
import com.talentai.user.UserDtos.UserCredentialsResponse;
import com.talentai.user.UserDtos.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;

/** Account management for administrators: create, change role, disable, reset password. */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAdminService {

    public static final List<String> ROLES = List.of("ADMIN", "RECRUITER", "HIRING_MANAGER", "INTERVIEWER");
    static final int MIN_PASSWORD_LENGTH = 8;
    private static final int GENERATED_PASSWORD_LENGTH = 12;
    /** No look-alike characters (0/O, 1/l/I) so a password read out or typed from a screen is not mistyped. */
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom random = new SecureRandom();

    public List<UserResponse> list() {
        return users.findAll().stream()
                .sorted(Comparator.comparing(AppUser::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(UserAdminService::toResponse)
                .toList();
    }

    public UserCredentialsResponse create(CreateUserRequest req) {
        String username = req.getUsername().trim();
        String email = req.getEmail().trim();
        String role = validRole(req.getRole());
        if (users.existsByUsernameIgnoreCase(username)) throw new IllegalArgumentException("That username is already taken.");
        if (users.existsByEmailIgnoreCase(email)) throw new IllegalArgumentException("Another account already uses that email address.");

        boolean generated = req.getPassword() == null || req.getPassword().isBlank();
        String password = generated ? generatePassword() : checkedPassword(req.getPassword());

        AppUser saved = users.save(AppUser.builder()
                .username(username)
                .email(email)
                .fullName(req.getFullName().trim())
                .role(role)
                .passwordHash(passwordEncoder.encode(password))
                .build());
        log.info("User '{}' created with role {}", saved.getUsername(), saved.getRole());
        return UserCredentialsResponse.builder().user(toResponse(saved)).temporaryPassword(generated ? password : null).build();
    }

    public UserResponse update(Long id, UpdateUserRequest req, String actingUsername) {
        AppUser user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
        boolean self = user.getUsername().equals(actingUsername);

        String newRole = req.getRole() == null ? user.getRole() : validRole(req.getRole());
        boolean newActive = req.getActive() == null ? user.isActive() : req.getActive();

        boolean roleChanges = !newRole.equals(user.getRole());
        boolean deactivates = user.isActive() && !newActive;
        if (self && (roleChanges || deactivates)) {
            throw new IllegalArgumentException("You can't change the role of, or disable, your own account.");
        }
        // The system must always keep at least one administrator who can sign in.
        boolean stopsBeingActiveAdmin = "ADMIN".equals(user.getRole()) && user.isActive()
                && (roleChanges || deactivates);
        if (stopsBeingActiveAdmin && users.countByRoleAndActiveTrue("ADMIN") <= 1) {
            throw new IllegalStateException("At least one active administrator is required.");
        }

        if (req.getFullName() != null) {
            if (req.getFullName().isBlank()) throw new IllegalArgumentException("Name cannot be empty.");
            user.setFullName(req.getFullName().trim());
        }
        if (req.getEmail() != null) {
            String email = req.getEmail().trim();
            if (!email.matches("^[^@\\s]+@[^@\\s]+$")) throw new IllegalArgumentException("Enter a valid email address.");
            users.findByEmailIgnoreCase(email).filter(other -> !other.getId().equals(user.getId())).ifPresent(other -> {
                throw new IllegalArgumentException("Another account already uses that email address.");
            });
            user.setEmail(email);
        }
        user.setRole(newRole);
        user.setActive(newActive);
        AppUser saved = users.save(user);
        log.info("User '{}' updated by '{}': role={}, active={}", saved.getUsername(), actingUsername, saved.getRole(), saved.isActive());
        return toResponse(saved);
    }

    public UserCredentialsResponse resetPassword(Long id, ResetPasswordRequest req) {
        AppUser user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
        boolean generated = req == null || req.getPassword() == null || req.getPassword().isBlank();
        String password = generated ? generatePassword() : checkedPassword(req.getPassword());
        user.setPasswordHash(passwordEncoder.encode(password));
        users.save(user);
        log.info("Password reset for user '{}'", user.getUsername());
        return UserCredentialsResponse.builder().user(toResponse(user)).temporaryPassword(generated ? password : null).build();
    }

    String generatePassword() {
        StringBuilder sb = new StringBuilder(GENERATED_PASSWORD_LENGTH);
        for (int i = 0; i < GENERATED_PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String checkedPassword(String password) {
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("The password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        return password;
    }

    private static String validRole(String role) {
        String r = role == null ? "" : role.trim().toUpperCase();
        if (!ROLES.contains(r)) throw new IllegalArgumentException("Role must be one of " + String.join(", ", ROLES) + ".");
        return r;
    }

    private static UserResponse toResponse(AppUser u) {
        return UserResponse.builder().id(u.getId()).username(u.getUsername()).fullName(u.getFullName())
                .email(u.getEmail()).role(u.getRole()).active(u.isActive()).createdAt(u.getCreatedAt()).build();
    }
}
