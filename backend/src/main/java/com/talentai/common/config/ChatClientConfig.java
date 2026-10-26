package com.talentai.common.config;

import com.talentai.aiusage.AiUsageAdvisor;
import com.talentai.aiusage.AiUsageRecorder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatClientConfig {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, AiUsageRecorder usageRecorder) {
        // every AI call made through this client is recorded (tokens and estimated cost)
        return builder.defaultAdvisors(new AiUsageAdvisor(usageRecorder)).build();
    }
}
