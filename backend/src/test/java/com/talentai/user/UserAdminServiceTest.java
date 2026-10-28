package com.talentai.user;

import com.talentai.common.entity.AppUser;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.user.UserDtos.CreateUserRequest;
import com.talentai.user.UserDtos.ResetPasswordRequest;
import com.talentai.user.UserDtos.UpdateUserRequest;
import com.talentai.user.UserDtos.UserCredentialsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserAdminServiceTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private UserAdminService service;

    @BeforeEach
    void setUp() {
        service = new UserAdminService(users, encoder);
        when(users.save(any(AppUser.class))).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            if (u.getId() == null) u.setId(100L);
            return u;
        });
    }

    private static AppUser user(long id, String username, String role, boolean active) {
        return AppUser.builder().id(id).username(username).email(username + "@x.com").fullName("User " + id)
                .role(role).passwordHash("hash").active(active).build();
    }

    private static CreateUserRequest create(String username, String role, String password) {
        return new CreateUserRequest(username, username + "@example.com", "Priya Nair", role, password);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void createGeneratesAReadablePasswordAndStoresOnlyItsHash() {
        UserCredentialsResponse result = service.create(create("priya", "interviewer", null));

        String temp = result.getTemporaryPassword();
        assertNotNull(temp);
        assertEquals(12, temp.length());
        assertTrue(temp.matches("[A-HJ-NP-Za-km-z2-9]+"), "no look-alike characters: " + temp);
        assertEquals("INTERVIEWER", result.getUser().getRole(), "role is normalised to upper case");
        assertTrue(result.getUser().isActive());

        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertNotEquals(temp, saved.getValue().getPasswordHash());
        assertTrue(encoder.matches(temp, saved.getValue().getPasswordHash()));
    }

    @Test
    void aPasswordTheAdminChoseIsNotEchoedBack() {
        UserCredentialsResponse result = service.create(create("priya", "RECRUITER", "ChosenPass1"));

        assertNull(result.getTemporaryPassword());
        var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertTrue(encoder.matches("ChosenPass1", saved.getValue().getPasswordHash()));
    }

    @Test
    void createRejectsShortPasswordsUnknownRolesAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> service.create(create("priya", "RECRUITER", "short")));
        assertThrows(IllegalArgumentException.class, () -> service.create(create("priya", "SUPERUSER", null)));

        when(users.existsByUsernameIgnoreCase("priya")).thenReturn(true);
        assertEquals("That username is already taken.",
                assertThrows(IllegalArgumentException.class, () -> service.create(create("priya", "RECRUITER", null))).getMessage());

        when(users.existsByUsernameIgnoreCase("priya")).thenReturn(false);
        when(users.existsByEmailIgnoreCase("priya@example.com")).thenReturn(true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.create(create("priya", "RECRUITER", null)))
                .getMessage().contains("email"));
        verify(users, never()).save(any());
    }

    @Test
    void generatedPasswordsDiffer() {
        assertNotEquals(service.generatePassword(), service.generatePassword());
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void anAdminCannotDisableOrDemoteThemselves() {
        when(users.findById(1L)).thenReturn(Optional.of(user(1, "boss", "ADMIN", true)));

        assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, new UpdateUserRequest(null, null, null, false), "boss"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(1L, new UpdateUserRequest(null, null, "RECRUITER", null), "boss"));
        verify(users, never()).save(any());
    }

    @Test
    void theLastActiveAdministratorCannotBeRemoved() {
        when(users.findById(2L)).thenReturn(Optional.of(user(2, "other", "ADMIN", true)));
        when(users.countByRoleAndActiveTrue("ADMIN")).thenReturn(1L);

        assertThrows(IllegalStateException.class,
                () -> service.update(2L, new UpdateUserRequest(null, null, null, false), "boss"));
        assertThrows(IllegalStateException.class,
                () -> service.update(2L, new UpdateUserRequest(null, null, "INTERVIEWER", null), "boss"));
    }

    @Test
    void anAdministratorCanBeDemotedWhenAnotherRemains() {
        when(users.findById(2L)).thenReturn(Optional.of(user(2, "other", "ADMIN", true)));
        when(users.countByRoleAndActiveTrue("ADMIN")).thenReturn(2L);

        var updated = service.update(2L, new UpdateUserRequest(null, null, "RECRUITER", null), "boss");

        assertEquals("RECRUITER", updated.getRole());
    }

    @Test
    void changesOnlyWhatWasSentAndCanReEnableAnAccount() {
        AppUser disabled = user(3, "priya", "INTERVIEWER", false);
        when(users.findById(3L)).thenReturn(Optional.of(disabled));

        var updated = service.update(3L, new UpdateUserRequest("Priya N.", null, null, true), "boss");

        assertTrue(updated.isActive());
        assertEquals("Priya N.", updated.getFullName());
        assertEquals("INTERVIEWER", updated.getRole());
        assertEquals("priya@x.com", updated.getEmail());
    }

    @Test
    void emailChangeMustNotCollideWithAnotherAccount() {
        when(users.findById(3L)).thenReturn(Optional.of(user(3, "priya", "INTERVIEWER", true)));
        when(users.findByEmailIgnoreCase("taken@x.com")).thenReturn(Optional.of(user(9, "someone", "RECRUITER", true)));

        assertThrows(IllegalArgumentException.class,
                () -> service.update(3L, new UpdateUserRequest(null, "taken@x.com", null, null), "boss"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(3L, new UpdateUserRequest(null, "not-an-email", null, null), "boss"));
        assertThrows(IllegalArgumentException.class,
                () -> service.update(3L, new UpdateUserRequest("  ", null, null, null), "boss"));
    }

    // ── reset ─────────────────────────────────────────────────────────────────

    @Test
    void resetGeneratesAPasswordAndReplacesTheHash() {
        AppUser u = user(3, "priya", "INTERVIEWER", true);
        when(users.findById(3L)).thenReturn(Optional.of(u));

        UserCredentialsResponse result = service.resetPassword(3L, new ResetPasswordRequest(null));

        assertNotNull(result.getTemporaryPassword());
        assertTrue(encoder.matches(result.getTemporaryPassword(), u.getPasswordHash()));
    }

    @Test
    void resetAcceptsABodylessRequestAndRejectsAShortPassword() {
        when(users.findById(3L)).thenReturn(Optional.of(user(3, "priya", "INTERVIEWER", true)));

        assertNotNull(service.resetPassword(3L, null).getTemporaryPassword());
        assertThrows(IllegalArgumentException.class, () -> service.resetPassword(3L, new ResetPasswordRequest("short")));
        assertThrows(IllegalArgumentException.class, () -> service.resetPassword(99L, null));
    }

    @Test
    void listIsSortedByName() {
        AppUser b = user(1, "b", "ADMIN", true); b.setFullName("bravo");
        AppUser a = user(2, "a", "ADMIN", true); a.setFullName("Alpha");
        when(users.findAll()).thenReturn(List.of(b, a));

        assertEquals(List.of("Alpha", "bravo"), service.list().stream().map(UserDtos.UserResponse::getFullName).toList());
    }
}
