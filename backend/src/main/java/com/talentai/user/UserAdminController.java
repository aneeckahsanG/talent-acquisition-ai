package com.talentai.user;

import com.talentai.user.UserDtos.CreateUserRequest;
import com.talentai.user.UserDtos.ResetPasswordRequest;
import com.talentai.user.UserDtos.UpdateUserRequest;
import com.talentai.user.UserDtos.UserCredentialsResponse;
import com.talentai.user.UserDtos.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/** Administrator-only account management. The URL rule in SecurityConfig restricts /api/users/** to ROLE_ADMIN. */
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserAdminController {

    private final UserAdminService userAdminService;

    @GetMapping
    public List<UserResponse> list() {
        return userAdminService.list();
    }

    @GetMapping("/roles")
    public List<String> roles() {
        return UserAdminService.ROLES;
    }

    @PostMapping
    public ResponseEntity<UserCredentialsResponse> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.ok(userAdminService.create(request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<UserResponse> update(@PathVariable Long id, @RequestBody UpdateUserRequest request,
                                               Principal principal) {
        return ResponseEntity.ok(userAdminService.update(id, request, principal.getName()));
    }

    @PostMapping("/{id}/reset-password")
    public ResponseEntity<UserCredentialsResponse> resetPassword(@PathVariable Long id,
                                                                 @RequestBody(required = false) ResetPasswordRequest request) {
        return ResponseEntity.ok(userAdminService.resetPassword(id, request));
    }
}
