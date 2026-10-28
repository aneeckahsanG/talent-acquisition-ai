package com.talentai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Resume Drop Zone settings API for the SharePoint source: saving, secrecy of the secret, and validation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CvInboxSettingsApiTest {

    private static final String LINK = "https://contoso.sharepoint.com/sites/HR/Shared%20Documents/CV%20Inbox";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String token() throws Exception {
        String body = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"recruiter1\",\"password\":\"password123\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("token").asText();
    }

    private org.springframework.test.web.servlet.ResultActions save(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/cv-inbox/settings").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void sharePointSettingsAreSavedAndTheSecretIsNeverReturned() throws Exception {
        String t = token();

        String saved = save(t, Map.of("mode", "SHAREPOINT", "sharepointTenantId", " tenant-x ", "sharepointClientId", "client-x",
                "sharepointClientSecret", "s3cr3t-value", "sharepointFolderLink", LINK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SHAREPOINT"))
                .andExpect(jsonPath("$.sharepointTenantId").value("tenant-x"))
                .andExpect(jsonPath("$.sharepointClientId").value("client-x"))
                .andExpect(jsonPath("$.sharepointFolderLink").value(LINK))
                .andExpect(jsonPath("$.sharepointSecretSet").value(true))
                .andReturn().getResponse().getContentAsString();
        assertFalse(saved.contains("s3cr3t-value"), "the secret must not be echoed back");

        // A blank secret on a later save keeps the stored one.
        save(t, Map.of("mode", "SHAREPOINT", "sharepointClientSecret", "", "sharepointFolderLink", LINK + "/Sub"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sharepointSecretSet").value(true))
                .andExpect(jsonPath("$.sharepointFolderLink").value(LINK + "/Sub"));

        mvc.perform(get("/api/cv-inbox/connection-info").header("Authorization", "Bearer " + t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SHAREPOINT"))
                .andExpect(jsonPath("$.sharepointFolderLink").value(LINK + "/Sub"));
    }

    @Test
    void aLinkThatCannotWorkIsRefusedWithAReadableMessage() throws Exception {
        String t = token();

        save(t, Map.of("mode", "SHAREPOINT", "sharepointFolderLink", "not a link"))
                .andExpect(status().isBadRequest());
        save(t, Map.of("mode", "SHAREPOINT", "sharepointFolderLink", "https://contoso.sharepoint.com/sites/HR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("document library")));
    }

    @Test
    void testConnectionWithIncompleteSettingsSaysWhatIsMissingWithoutCallingMicrosoft() throws Exception {
        String t = token();

        mvc.perform(post("/api/cv-inbox/settings/test-connection").header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("mode", "SHAREPOINT", "sharepointFolderLink", LINK))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("tenant ID")));
    }
}
