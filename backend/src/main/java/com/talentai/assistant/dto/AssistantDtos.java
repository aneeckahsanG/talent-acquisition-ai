package com.talentai.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AssistantDtos {

    private AssistantDtos() {
    }

    public record ChatRequest(
            @NotBlank @Size(max = 4000) String message,
            @Size(max = 100) String currentPage
    ) {
    }

    public record ChatResponse(String answer) {
    }
}
