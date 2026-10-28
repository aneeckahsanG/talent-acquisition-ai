package com.talentai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real logins against the real security rules (seeded demo users, in-memory database).
 * Proves who can reach what: no self-registration, admin-only user management, and an
 * interviewer fenced into their own interviews.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessRulesIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    /** GET as the holder of {@code token}; an empty token means not signed in. */
    private ResultActions getAs(String path, String token) throws Exception {
        var req = get(path);
        if (!token.isEmpty()) req.header("Authorization", "Bearer " + token);
        return mvc.perform(req);
    }

    private ResultActions send(String method, String path, String token, Object body) throws Exception {
        var req = request(org.springframework.http.HttpMethod.valueOf(method), path)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(json.writeValueAsString(body));
        return mvc.perform(req);
    }

    @Test
    void selfRegistrationIsGone() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"email\":\"m@x.com\",\"password\":\"password123\",\"fullName\":\"M\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void onlyAnAdministratorCanManageUsers() throws Exception {
        getAs("/api/users", "").andExpect(status().isUnauthorized());
        getAs("/api/users", login("recruiter1", "password123")).andExpect(status().isForbidden());
        getAs("/api/users", login("hiringmgr1", "password123")).andExpect(status().isForbidden());
        getAs("/api/users", login("interviewer1", "password123")).andExpect(status().isForbidden());
        getAs("/api/users", login("admin", "password123")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username=='interviewer1')].role").value("INTERVIEWER"));
    }

    @Test
    void anInterviewerIsFencedIntoTheirOwnInterviews() throws Exception {
        String t = login("interviewer1", "password123");

        getAs("/api/interviewer/interviews", t).andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        getAs("/api/auth/me", t).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("INTERVIEWER"));

        // The whole recruiting app is off limits: pipeline, candidates, offers, requisitions, calendar, AI.
        for (String path : new String[]{"/api/requisitions", "/api/orchestrator/dashboard", "/api/candidates/search?q=aa",
                "/api/calendar/interviewers", "/api/cv-inbox/items?decision=CONSIDERED", "/api/intelligence/attention",
                "/api/orchestrator/scorecard-criteria"}) {
            getAs(path, t).andExpect(status().isForbidden());
        }
        // An interview that is not theirs, or does not exist, is refused the same way.
        getAs("/api/interviewer/interviews/999999", t).andExpect(status().isForbidden());
        send("POST", "/api/interviewer/interviews/999999/scorecard", t, Map.of("verdict", "YES")).andExpect(status().isForbidden());
        // ...and the recruiter-side scorecard endpoint is not an alternative route.
        send("POST", "/api/orchestrator/interviews/999999/scorecard", t, Map.of("verdict", "YES")).andExpect(status().isForbidden());
    }

    @Test
    void otherRolesKeepTheirAccessToTheRecruitingApp() throws Exception {
        for (String user : new String[]{"admin", "recruiter1", "hiringmgr1"}) {
            String t = login(user, "password123");
            getAs("/api/requisitions", t).andExpect(status().isOk());
            getAs("/api/auth/me", t).andExpect(status().isOk());
        }
    }

    @Test
    void anAdministratorCanCreateAnInterviewerWhoThenSignsInAndCanBeDisabled() throws Exception {
        String admin = login("admin", "password123");
        String username = "iv" + System.nanoTime() % 1_000_000;

        String created = send("POST", "/api/users", admin, Map.of(
                "username", username, "email", username + "@example.com", "fullName", "New Interviewer", "role", "INTERVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("INTERVIEWER"))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(created);
        String tempPassword = node.get("temporaryPassword").asText();
        long id = node.get("user").get("id").asLong();

        // The generated password works, and the new account is an interviewer.
        String token = login(username, tempPassword);
        getAs("/api/interviewer/interviews", token).andExpect(status().isOk());
        getAs("/api/requisitions", token).andExpect(status().isForbidden());

        // They can change their own password.
        send("POST", "/api/auth/change-password", token, Map.of("currentPassword", tempPassword, "newPassword", "a-better-pass-1"))
                .andExpect(status().isNoContent());
        send("POST", "/api/auth/change-password", token, Map.of("currentPassword", "wrong", "newPassword", "another-pass-2"))
                .andExpect(status().isBadRequest());
        String after = login(username, "a-better-pass-1");

        // Disabling cuts off the live token at once and blocks new sign-ins.
        send("PATCH", "/api/users/" + id, admin, Map.of("active", false)).andExpect(status().isOk());
        getAs("/api/interviewer/interviews", after).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", "a-better-pass-1"))))
                .andExpect(status().isUnauthorized());
        // A disabled account no longer appears as someone who can be assigned an interview.
        getAs("/api/calendar/interviewers", admin).andExpect(jsonPath("$[?(@.fullName=='New Interviewer')]").isEmpty());

        // Re-enable and reset the password.
        send("PATCH", "/api/users/" + id, admin, Map.of("active", true)).andExpect(status().isOk());
        String reset = send("POST", "/api/users/" + id + "/reset-password", admin, null).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertNotNull(json.readTree(reset).get("temporaryPassword").asText());
    }

    @Test
    void anAdministratorCannotLockThemselvesOut() throws Exception {
        String admin = login("admin", "password123");
        long adminId = json.readTree(getAs("/api/users", admin).andReturn().getResponse().getContentAsString())
                .findParents("username").stream().filter(n -> "admin".equals(n.get("username").asText()))
                .findFirst().orElseThrow().get("id").asLong();

        send("PATCH", "/api/users/" + adminId, admin, Map.of("active", false)).andExpect(status().isBadRequest());
        send("PATCH", "/api/users/" + adminId, admin, Map.of("role", "RECRUITER")).andExpect(status().isBadRequest());
    }

    @Test
    void duplicateUsernamesAndUnknownRolesAreRejected() throws Exception {
        String admin = login("admin", "password123");
        send("POST", "/api/users", admin, Map.of("username", "Recruiter1", "email", "dup@example.com", "fullName", "Dup", "role", "RECRUITER"))
                .andExpect(status().isBadRequest());
        send("POST", "/api/users", admin, Map.of("username", "newbie", "email", "newbie@example.com", "fullName", "N", "role", "GOD"))
                .andExpect(status().isBadRequest());
    }
}
