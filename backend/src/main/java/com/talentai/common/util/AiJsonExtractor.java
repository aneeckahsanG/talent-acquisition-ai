package com.talentai.common.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

/**
 * Parses a Claude chat response's raw text into a DTO ourselves, instead of Spring AI's
 * {@code ChatClient.entity(Class)} / {@code BeanOutputConverter}. claude-haiku-5-5 now returns a
 * "thinking" content block ahead of the JSON "text" block on every call (even unrequested), and
 * Spring AI 1.1.8's AnthropicChatModel puts each of those two blocks into its own separate
 * {@link Generation} instead of merging them into one message — so {@code ChatResponse.getResult()}
 * (first generation only) sometimes returns the empty "thinking" generation while the real JSON sits
 * in the next one. {@link #extractText} works around that by concatenating every generation's text.
 */
public final class AiJsonExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiJsonExtractor() {
    }

    public static String extractText(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Generation generation : response.getResults()) {
            String text = generation.getOutput() != null ? generation.getOutput().getText() : null;
            if (text != null && !text.isBlank()) {
                sb.append(text);
            }
        }
        return sb.toString();
    }

    public static <T> T parse(String content, Class<T> type) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("Empty response body");
        }
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            int lastFence = json.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                json = json.substring(firstNewline + 1, lastFence).trim();
            }
        }
        if (json.isBlank()) {
            throw new IllegalStateException("Empty response body");
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse AI response as JSON: " + e.getMessage(), e);
        }
    }
}
