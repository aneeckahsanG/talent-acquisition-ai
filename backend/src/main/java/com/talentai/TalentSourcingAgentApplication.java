package com.talentai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TalentSourcingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(TalentSourcingAgentApplication.class, args);
    }
}
