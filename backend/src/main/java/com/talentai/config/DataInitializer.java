package com.talentai.config;

import com.talentai.common.entity.AppUser;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.AppUserRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds demo users and job requisitions on first startup if they don't already exist.
 * Uses BCryptPasswordEncoder so password hashes are always correct regardless of platform.
 * Default password for all demo users: password123
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final AppUserRepository appUserRepository;
    private final JobRequisitionRepository jobRequisitionRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedUsers();
        seedRequisitions();
    }

    private void seedUsers() {
        record UserSeed(String username, String email, String fullName, String role) {}

        List<UserSeed> users = List.of(
                new UserSeed("admin",      "admin@talentai.demo",      "System Admin",  "ADMIN"),
                new UserSeed("recruiter1", "recruiter1@talentai.demo", "Aisha Rahman",  "RECRUITER"),
                new UserSeed("hiringmgr1", "hiringmgr1@talentai.demo", "Daniel Lim",   "HIRING_MANAGER")
        );

        String encodedPassword = passwordEncoder.encode("password123");

        for (UserSeed seed : users) {
            if (!appUserRepository.existsByUsername(seed.username())) {
                AppUser user = AppUser.builder()
                        .username(seed.username())
                        .email(seed.email())
                        .fullName(seed.fullName())
                        .role(seed.role())
                        .passwordHash(encodedPassword)
                        .build();
                appUserRepository.save(user);
                log.info("Seeded demo user: {}", seed.username());
            }
        }
    }

    private void seedRequisitions() {
        if (jobRequisitionRepository.count() > 0) {
            return; // already seeded
        }

        Long createdBy = appUserRepository.findByUsername("recruiter1")
                .map(AppUser::getId)
                .orElse(null);

        List<JobRequisition> requisitions = List.of(
                JobRequisition.builder()
                        .title("Senior Backend Engineer (Java)")
                        .department("Engineering")
                        .location("Kuala Lumpur")
                        .description("We are looking for a Senior Backend Engineer with strong Java and Spring Boot experience to lead development of our core platform services. Responsibilities include designing scalable microservices, mentoring junior engineers, and collaborating with product teams.")
                        .requiredSkills("Java, Spring Boot, Microservices, PostgreSQL, AWS, REST APIs, System Design")
                        .experienceLevel("SENIOR")
                        .status("OPEN")
                        .createdBy(createdBy)
                        .build(),
                JobRequisition.builder()
                        .title("Data Analyst")
                        .department("Analytics")
                        .location("Kuala Lumpur")
                        .description("Seeking a Data Analyst to support business teams with reporting, dashboards, and ad-hoc analysis. Strong SQL skills and experience with BI tools required.")
                        .requiredSkills("SQL, Excel, Power BI, Tableau, Python, Data Visualization")
                        .experienceLevel("MID")
                        .status("OPEN")
                        .createdBy(createdBy)
                        .build(),
                JobRequisition.builder()
                        .title("AI/ML Engineer")
                        .department("Engineering")
                        .location("Kuala Lumpur (Hybrid)")
                        .description("Join our AI team to build and deploy machine learning models and LLM-powered features into production. Experience with Python, model deployment, and prompt engineering preferred.")
                        .requiredSkills("Python, Machine Learning, LLM, PyTorch, Cloud Deployment, APIs")
                        .experienceLevel("MID")
                        .status("OPEN")
                        .createdBy(createdBy)
                        .build()
        );

        jobRequisitionRepository.saveAll(requisitions);
        log.info("Seeded {} demo job requisitions", requisitions.size());
    }
}
