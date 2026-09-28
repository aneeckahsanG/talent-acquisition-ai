package com.talentai.sourcing.service;

import com.talentai.common.service.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class LinkedInParseService {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    private record ParsedLinkedInProfile(
            String fullName, String email, String headline,
            String location, String skills, String resumeText) {}

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
    public Map<String, String> parseProfileText(String profileText) {
        try {
            ParsedLinkedInProfile parsed = chatClient.prompt()
                    .system(promptLoader.load("linkedin-parse-agent"))
                    .user("Extract candidate info from this LinkedIn profile text:\n\n" + profileText)
                    .call()
                    .entity(ParsedLinkedInProfile.class);
            Map<String, String> result = new HashMap<>();
            result.put("fullName",   parsed.fullName()   != null ? parsed.fullName()   : "");
            result.put("email",      parsed.email()      != null ? parsed.email()      : "");
            result.put("headline",   parsed.headline()   != null ? parsed.headline()   : "");
            result.put("location",   parsed.location()   != null ? parsed.location()   : "");
            result.put("skills",     parsed.skills()     != null ? parsed.skills()     : "");
            result.put("resumeText", parsed.resumeText() != null ? parsed.resumeText() : "");
            return result;
        } catch (Exception e) {
            log.error("Failed to parse LinkedIn profile text: {}", e.getMessage());
            Map<String, String> empty = new HashMap<>();
            empty.put("fullName", ""); empty.put("email", ""); empty.put("headline", "");
            empty.put("location", ""); empty.put("skills", ""); empty.put("resumeText", "");
            return empty;
        }
    }
}
