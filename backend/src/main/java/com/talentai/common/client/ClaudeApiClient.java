package com.talentai.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

import java.time.Duration;

/**
 * Shared client for calling the Claude (Anthropic) Messages API.
 * Used by Sourcing, Screening, Referral, and Admin agents to
 * obtain LLM-based reasoning, scoring, and content generation.
 *
 * Uses the injected WebClient.Builder (configured in WebClientConfig)
 * so that timeout and connector settings are applied consistently.
 */
@Component
@Slf4j
public class ClaudeApiClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${claude.api.key}")
    private String apiKey;

    @Value("${claude.api.model}")
    private String model;

    @Value("${claude.api.version}")
    private String apiVersion;

    @Value("${claude.api.max-tokens}")
    private int maxTokens;

    public ClaudeApiClient(
            WebClient.Builder webClientBuilder,
            @Value("${claude.api.base-url}") String baseUrl) {
        // Uses the shared builder from WebClientConfig (includes 60s timeout + Reactor Netty connector)
        this.webClient = webClientBuilder
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Sends a single-turn prompt to Claude and returns the raw text response.
     * The system prompt should instruct Claude to respond in a specific format
     * (e.g., JSON only) for downstream parsing.
     */
    public String sendPrompt(String systemPrompt, String userPrompt) {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("CLAUDE_API_KEY is not configured.");
            throw new IllegalStateException("Claude API key is not configured. Set CLAUDE_API_KEY environment variable.");
        }

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("system", systemPrompt);

        ArrayNode messages = requestBody.putArray("messages");
        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);
        messages.add(userMessage);

        try {
            String response = webClient.post()
                    .uri("")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", apiVersion)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(1))
                            .filter(this::isRetryable))
                    .block(Duration.ofSeconds(60));

            return extractText(response);
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage(), e);
            throw new ClaudeApiException("Failed to call Claude API: " + e.getMessage(), e);
        }
    }

    private boolean isRetryable(Throwable throwable) {
        // Retry on transient/network errors; do not retry on 4xx client errors
        String message = throwable.getMessage();
        return message != null && (message.contains("503") || message.contains("529") || message.contains("timeout")
                || message.contains("Connection reset") || message.contains("Connection refused")
                || message.contains("Connection prematurely closed"));
    }

    private String extractText(String responseJson) {
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            JsonNode content = root.get("content");
            if (content != null && content.isArray() && !content.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
                return sb.toString();
            }
            log.warn("Unexpected Claude response structure: {}", responseJson);
            return "";
        } catch (Exception e) {
            log.error("Failed to parse Claude response: {}", responseJson, e);
            throw new ClaudeApiException("Failed to parse Claude response", e);
        }
    }

    /**
     * Strips markdown code fences from a response that should contain raw JSON.
     * Claude sometimes wraps JSON in ```json ... ``` blocks despite instructions.
     */
    public String stripJsonFences(String text) {
        if (text == null) return "";
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(json)?", "").trim();
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    public static class ClaudeApiException extends RuntimeException {
        public ClaudeApiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
