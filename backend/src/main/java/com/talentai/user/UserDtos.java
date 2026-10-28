package com.talentai.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

public class UserDtos {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserResponse {
        private Long id;
        private String username;
        private String fullName;
        private String email;
        private String role;
        private boolean active;
        private LocalDateTime createdAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateUserRequest {
        @NotBlank
        @Size(min = 3, max = 100)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "Use letters, numbers, dots, dashes or underscores only")
        private String username;

        @NotBlank
        @Email
        private String email;

        @NotBlank
        @Size(max = 150)
        private String fullName;

        @NotBlank
        private String role;

        /** Optional. Left blank, a temporary password is generated and returned once. */
        private String password;
    }

    /** Every field is optional; only the ones sent are changed. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateUserRequest {
        private String fullName;
        private String email;
        private String role;
        private Boolean active;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResetPasswordRequest {
        /** Optional. Left blank, a temporary password is generated and returned once. */
        private String password;
    }

    /** The temporary password is present only when the server generated it, and is never shown again. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserCredentialsResponse {
        private UserResponse user;
        private String temporaryPassword;
    }
}
