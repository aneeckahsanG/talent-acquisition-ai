package com.talentai.assistant.controller;

import com.talentai.assistant.dto.AssistantDtos.ChatRequest;
import com.talentai.assistant.dto.AssistantDtos.ChatResponse;
import com.talentai.assistant.service.AssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final AssistantService assistantService;

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(assistantService.chat(request));
    }
}
