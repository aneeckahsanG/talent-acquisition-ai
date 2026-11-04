package com.talentai.common.config;

import com.talentai.aiusage.AiBudgetGuard;
import com.talentai.aiusage.AiUsageAdvisor;
import com.talentai.aiusage.AiUsageRecorder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, AiUsageRecorder usageRecorder, AiBudgetGuard budgetGuard,
                          com.talentai.orchestrator.agent.AgentTrace agentTrace) {
        // every AI call made through this client is checked against the budget, recorded for cost,
        // and attached to the agent run that triggered it when one is in progress
        return builder.defaultAdvisors(new AiUsageAdvisor(usageRecorder, budgetGuard, agentTrace)).build();
    }
}
