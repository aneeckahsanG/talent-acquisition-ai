package com.talentai.common.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads agent system prompts from src/main/resources/prompts/{name}.md.
 *
 * Each file is plain markdown with an optional YAML frontmatter block
 * (between --- lines) carrying metadata for humans — agent name,
 * description, whether the output is parsed as strict JSON, last-updated
 * date. The frontmatter is stripped before the text reaches Claude; only
 * the body below it is ever sent as the system prompt.
 *
 * Prompts are read once and cached — restart the app to pick up an edited
 * file. See docs/agentic-orchestrator-async-loop.md-style notes for why
 * this is intentionally NOT hot-reloaded: several of these prompts dictate
 * a strict JSON shape that downstream Java code parses, so a prompt edit
 * is a code-adjacent change that should go through the same build/deploy
 * path as everything else, not be editable on a live server.
 */
@Component
@Slf4j
public class PromptLoader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String name) {
        return cache.computeIfAbsent(name, this::readAndStripFrontmatter);
    }

    private String readAndStripFrontmatter(String name) {
        String path = "prompts/" + name + ".md";
        // Explicitly use this class's own classloader rather than the ambient
        // thread-context classloader: prompts are loaded from background
        // threads (CompletableFuture.runAsync for the orchestrator and async
        // screening) whose context classloader isn't reliably the Spring Boot
        // fat-jar launcher's classloader, which is where BOOT-INF/classes
        // resources actually live.
        try (InputStream in = new ClassPathResource(path, getClass().getClassLoader()).getInputStream()) {
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return stripFrontmatter(raw).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Could not load prompt file: " + path, e);
        }
    }

    private String stripFrontmatter(String raw) {
        String trimmed = raw.stripLeading();
        if (!trimmed.startsWith("---")) return raw;
        int end = trimmed.indexOf("\n---", 3);
        if (end == -1) return raw;
        int bodyStart = trimmed.indexOf('\n', end + 4);
        return bodyStart == -1 ? "" : trimmed.substring(bodyStart + 1);
    }
}
