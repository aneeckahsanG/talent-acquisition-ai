package com.talentai.assistant.service;

import com.talentai.assistant.dto.AssistantDtos.ChatRequest;
import com.talentai.assistant.dto.AssistantDtos.ChatResponse;
import com.talentai.common.service.PromptLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AssistantService {

    private final ChatClient chatClient;
    private final PromptLoader promptLoader;

    public ChatResponse chat(ChatRequest request) {
        String page = request.currentPage() == null || request.currentPage().isBlank()
                ? "unknown"
                : request.currentPage();
        String userPrompt = """
                Current application area: %s

                User question:
                %s
                """.formatted(page, request.message().trim());

        try {
            String answer = chatClient.prompt()
                    .system(promptLoader.load("assistant"))
                    .user(userPrompt)
                    .call()
                    .content();
            return new ChatResponse(answer == null || answer.isBlank()
                    ? "I could not generate an answer. Please try rephrasing your question."
                    : answer.trim());
        } catch (Exception e) {
            log.error("AI assistant request failed", e);
            throw new IllegalStateException("The AI assistant is temporarily unavailable.");
        }
    }
}
