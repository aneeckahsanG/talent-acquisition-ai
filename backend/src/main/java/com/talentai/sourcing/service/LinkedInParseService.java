package com.talentai.sourcing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.talentai.common.client.ClaudeApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LinkedInParseService {

    private final ClaudeApiClient claudeApiClient;
    private final ObjectMapper objectMapper;

    private static final String SYSTEM_PROMPT = """
            You are a talent data extractor. Given LinkedIn profile text, extract structured candidate information.
            Return ONLY a JSON object with these exact keys (use empty string if not found):
            {
              "fullName": "",
              "email": "",
              "headline": "",
              "location": "",
              "skills": "",
              "resumeText": ""
            }
            - "headline": their current title and company, e.g. "Senior Engineer at Grab"
            - "skills": comma-separated list of technical skills found anywhere in the profile
            - "resumeText": a concise 3-5 sentence summary of their experience and background
            No markdown fences. No explanation. Pure JSON only.
            """;

    /**
     * Attempts to fetch a LinkedIn public profile page and extract text.
     * Falls back gracefully if blocked — returns null so caller can prompt user to paste.
     */
    public String fetchProfileText(String url) {
        try {
            String html = WebClient.builder()
                    .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                    .defaultHeader("Accept", "text/html,application/xhtml+xml")
                    .defaultHeader("Accept-Language", "en-US,en;q=0.9")
                    .build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();

            if (html == null || html.isBlank()) return null;

            // Check if LinkedIn returned a login wall
            if (html.contains("authwall") || html.contains("login") && html.contains("join") && html.length() < 15000) {
                log.warn("LinkedIn returned auth wall for URL: {}", url);
                return null;
            }

            // Strip HTML tags, collapse whitespace
            String text = html
                    .replaceAll("<script[^>]*>[\\s\\S]*?</script>", " ")
                    .replaceAll("<style[^>]*>[\\s\\S]*?</style>", " ")
                    .replaceAll("<[^>]+>", " ")
                    .replaceAll("&amp;", "&")
                    .replaceAll("&lt;", "<")
                    .replaceAll("&gt;", ">")
                    .replaceAll("&nbsp;", " ")
                    .replaceAll("\\s{2,}", " ")
                    .trim();

            return text.length() > 200 ? text.substring(0, Math.min(text.length(), 6000)) : null;

        } catch (Exception e) {
            log.warn("Failed to fetch LinkedIn profile {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Parses raw LinkedIn profile text (from URL fetch or user paste) into structured fields using Claude.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> parseProfileText(String profileText) {
        try {
            String userPrompt = "Extract candidate info from this LinkedIn profile text:\n\n" + profileText;
            String raw = claudeApiClient.sendPrompt(SYSTEM_PROMPT, userPrompt);
            raw = raw.replaceAll("(?s)```[a-z]*\\s*", "").replaceAll("```", "").trim();
            return objectMapper.readValue(raw, Map.class);
        } catch (Exception e) {
            log.error("Failed to parse LinkedIn profile text: {}", e.getMessage());
            return Map.of("fullName", "", "email", "", "headline", "", "location", "", "skills", "", "resumeText", "");
        }
    }
}
