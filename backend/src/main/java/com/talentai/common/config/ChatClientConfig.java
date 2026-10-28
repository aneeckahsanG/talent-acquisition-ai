package com.talentai.common.config;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.aiusage.AiUsageAdvisor;
import com.talentai.aiusage.AiUsageRecorder;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatProperties;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    /**
     * Anthropic now rejects the `temperature` field outright on every model ("`temperature`
     * is deprecated for this model") — but Spring AI's autoconfiguration hard-codes
     * AnthropicChatProperties' default AnthropicChatOptions to temperature=0.8, and that's
     * already a constructed object by the time application.yml properties bind to it, so a
     * blank/null yaml value is simply ignored rather than clearing it. Replacing the
     * AnthropicChatModel bean ourselves — same model/max-tokens from properties, temperature
     * just never set — keeps it out of every outgoing request.
     */
    @Bean
    AnthropicChatModel anthropicChatModel(AnthropicApi anthropicApi, AnthropicChatProperties properties,
                                           ObjectProvider<ObservationRegistry> observationRegistry) {
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(properties.getOptions().getModel())
                .maxTokens(properties.getOptions().getMaxTokens())
                .build();
        return AnthropicChatModel.builder()
                .anthropicApi(anthropicApi)
                .defaultOptions(options)
                .retryTemplate(RetryUtils.DEFAULT_RETRY_TEMPLATE)
                .observationRegistry(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP))
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, AiUsageRecorder usageRecorder, AiBudgetGuard budgetGuard) {
        // every AI call made through this client is checked against the budget, then recorded
        return builder.defaultAdvisors(new AiUsageAdvisor(usageRecorder, budgetGuard)).build();
    }
}
