package com.talentai.screening;

import com.talentai.common.entity.Candidate;
import com.talentai.common.entity.JobRequisition;
import com.talentai.common.repository.CandidateRepository;
import com.talentai.common.repository.JobRequisitionRepository;
import com.talentai.screening.entity.ScreeningResult;
import com.talentai.screening.repository.ScreeningResultRepository;
import com.talentai.screening.service.ScreeningAgentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI Evaluation tests for the Screening Agent.
 *
 * Three layers:
 *  1. Structural  — response is well-formed, scores in [0,100], rec is a valid enum
 *  2. Golden set  — 5 known CV+job pairs; overall accuracy must be >= 70%
 *  3. Consistency — same CV run twice must produce the same recommendation
 *
 * Requires CLAUDE_API_KEY in the environment and consumes API credits.
 * Run with: mvn test -Dspring.profiles.active=test
 */
@SpringBootTest
@ActiveProfiles("test")
class ScreeningAgentEvalTest {

    @Autowired ScreeningAgentService screeningAgentService;
    @Autowired ScreeningResultRepository screeningResultRepository;
    @Autowired CandidateRepository candidateRepository;
    @Autowired JobRequisitionRepository jobRequisitionRepository;

    private static final Set<String> VALID_RECS = Set.of("ADVANCE", "REJECT", "REVIEW");

    // IDs of entities created per test — cleaned up in @AfterEach
    private final List<Long> candidateIds = new ArrayList<>();
    private Long reqId;

    @BeforeEach
    void setUp() {
        JobRequisition req = jobRequisitionRepository.save(JobRequisition.builder()
                .title("Senior Java Engineer")
                .department("Engineering")
                .experienceLevel("SENIOR")
                .requiredSkills("Java, Spring Boot, REST APIs, Microservices, SQL")
                .description("Looking for a Senior Java Engineer with 5+ years Spring Boot " +
                        "experience to design and build microservices.")
                .status("OPEN")
                .build());
        reqId = req.getId();
    }

    @AfterEach
    void tearDown() {
        // Clean up screening results first (FK), then candidates and the req
        candidateIds.forEach(cid ->
                screeningResultRepository.findByCandidateIdAndRequisitionId(cid, reqId)
                        .ifPresent(screeningResultRepository::delete));
        candidateIds.forEach(candidateRepository::deleteById);
        candidateIds.clear();
        jobRequisitionRepository.deleteById(reqId);
    }

    // ── 1. Structural tests ──────────────────────────────────────────────────

    @Test
    @DisplayName("Structural: scores are in [0,100], recommendation is valid, rationale is not blank")
    void structural_responseIsWellFormed() {
        Candidate c = save(candidate("Test Eval",
                "5 years Java Spring Boot developer. Built REST APIs and microservices."));

        screeningAgentService.runScreeningInternal(c, req());

        ScreeningResult r = result(c);

        assertThat(r.getRecommendation()).as("recommendation").isIn(VALID_RECS);
        assertInRange(r.getOverallScore(),    "overallScore");
        assertInRange(r.getSkillsScore(),     "skillsScore");
        assertInRange(r.getExperienceScore(), "experienceScore");
        assertInRange(r.getCultureFitScore(), "cultureFitScore");
        assertThat(r.getRationale()).as("rationale").isNotBlank();
        assertThat(r.getIsEdgeCase()).as("isEdgeCase").isNotNull();
    }

    @Test
    @DisplayName("Structural: REVIEW recommendation always sets isEdgeCase=true")
    void structural_reviewSetsEdgeCase() {
        Candidate c = save(candidate("Borderline Dev",
                "2 years Java. Some Spring MVC work. Mixed tech background."));

        screeningAgentService.runScreeningInternal(c, req());

        ScreeningResult r = result(c);
        if ("REVIEW".equals(r.getRecommendation())) {
            assertThat(r.getIsEdgeCase()).as("REVIEW must set isEdgeCase=true").isTrue();
        }
    }

    @Test
    @DisplayName("Structural: strong candidate scores higher than clearly unqualified candidate")
    void structural_strongScoresHigherThanWeak() {
        Candidate strong = save(candidate("Strong Dev",
                "8 years Java Spring Boot. Built microservices. AWS certified. Team lead."));
        Candidate weak = save(candidate("Unqualified",
                "No programming experience. Worked in retail for 3 years."));

        screeningAgentService.runScreeningInternal(strong, req());
        screeningAgentService.runScreeningInternal(weak, req());

        assertThat(result(strong).getOverallScore())
                .as("strong candidate should score higher than unqualified candidate")
                .isGreaterThan(result(weak).getOverallScore());
    }

    // ── 2. Golden dataset eval ───────────────────────────────────────────────

    record EvalCase(String label, String resumeText, String expectedRec) {}

    List<EvalCase> goldenCases() {
        return List.of(
                new EvalCase(
                        "Strong match — 8yr senior Java engineer",
                        """
                        John Tan — Senior Software Engineer, 8 years
                        Skills: Java 17, Spring Boot 3, Microservices, REST APIs, PostgreSQL, AWS, Docker
                        - Led backend team, built 12 Spring Boot microservices at FinTech startup
                        - Improved API latency 40% via caching and query optimisation
                        - Mentored 3 junior engineers
                        Education: BSc Computer Science
                        """,
                        "ADVANCE"
                ),
                new EvalCase(
                        "Weak match — fresh grad, no tech skills",
                        """
                        Priya Nair — Fresh Graduate
                        Skills: HTML, CSS, Photoshop, Microsoft Word
                        Education: Diploma in Graphic Design, 2024
                        Part-time work: Barista, 1 year
                        """,
                        "REJECT"
                ),
                new EvalCase(
                        "Borderline — 2yr junior Java, some Spring",
                        """
                        Ahmad Farid — Junior Java Developer, 2 years
                        Skills: Java, Spring MVC, MySQL, basic REST APIs
                        - Maintained legacy Java EE application at a bank
                        - Fixed bugs and wrote unit tests
                        Education: BSc Information Technology
                        """,
                        "REVIEW"
                ),
                new EvalCase(
                        "Strong match — 6yr full-stack, deep Java backend",
                        """
                        Sarah Lim — Full-Stack Engineer, 6 years
                        Skills: Java 17, Spring Boot 3, Hibernate, Kafka, Redis, React, Kubernetes
                        - Built e-commerce platform using Spring Boot microservices (500k MAU)
                        - Designed REST APIs, implemented CI/CD with GitHub Actions
                        """,
                        "ADVANCE"
                ),
                new EvalCase(
                        "Weak match — PHP developer, no Java",
                        """
                        Wei Chen — PHP Developer, 4 years
                        Skills: PHP, Laravel, MySQL, JavaScript, Vue.js
                        - Built CMS portals using Laravel
                        - Integrated Stripe/PayPal payment gateways
                        No Java or Spring experience.
                        """,
                        "REJECT"
                )
        );
    }

    @Test
    @DisplayName("Golden set: overall accuracy >= 70% across 5 representative cases")
    void golden_overallAccuracyAtLeast70Percent() {
        List<EvalCase> cases = goldenCases();
        List<Candidate> candidates = cases.stream()
                .map(tc -> save(candidate("GoldenCandidate " + cases.indexOf(tc), tc.resumeText())))
                .toList();

        // Run screening for all candidates
        for (Candidate c : candidates) {
            screeningAgentService.runScreeningInternal(c, req());
        }

        long correct = 0;
        for (int i = 0; i < cases.size(); i++) {
            EvalCase tc = cases.get(i);
            ScreeningResult r = result(candidates.get(i));
            boolean match = tc.expectedRec().equals(r.getRecommendation());
            System.out.printf("[GOLDEN] %-50s | expected=%-6s | got=%-6s | score=%-4s | %s%n",
                    tc.label(), tc.expectedRec(), r.getRecommendation(),
                    r.getOverallScore(), match ? "✓" : "✗");
            if (match) correct++;
        }

        double accuracy = (double) correct / cases.size();
        System.out.printf("%n[GOLDEN] Accuracy: %d/%d = %.0f%%%n", correct, cases.size(), accuracy * 100);

        assertTrue(accuracy >= 0.70,
                String.format("Expected accuracy >= 70%%, got %.0f%% (%d/%d correct)",
                        accuracy * 100, correct, cases.size()));
    }

    // ── 3. Consistency test ──────────────────────────────────────────────────

    @Test
    @DisplayName("Consistency: same CV submitted twice produces the same recommendation (temperature=0)")
    void consistency_sameInputProducesSameRecommendation() {
        String resumeText = "David Ng. 7 years Java Spring Boot. Built payment microservices. AWS, Docker, Kubernetes.";

        Candidate run1 = save(candidate("David Ng Run1", resumeText));
        Candidate run2 = save(candidate("David Ng Run2", resumeText));

        screeningAgentService.runScreeningInternal(run1, req());
        screeningAgentService.runScreeningInternal(run2, req());

        ScreeningResult r1 = result(run1);
        ScreeningResult r2 = result(run2);

        System.out.printf("[CONSISTENCY] Run1=%s (score=%s) | Run2=%s (score=%s)%n",
                r1.getRecommendation(), r1.getOverallScore(),
                r2.getRecommendation(), r2.getOverallScore());

        assertThat(r1.getRecommendation())
                .as("Same CV should produce the same recommendation on repeated calls")
                .isEqualTo(r2.getRecommendation());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Candidate candidate(String name, String resumeText) {
        return Candidate.builder()
                .fullName(name)
                .email(name.toLowerCase().replace(" ", ".") + "@eval.test")
                .resumeText(resumeText)
                .sourceChannel("EVAL_TEST")
                .build();
    }

    private Candidate save(Candidate c) {
        Candidate saved = candidateRepository.save(c);
        candidateIds.add(saved.getId());
        return saved;
    }

    private JobRequisition req() {
        return jobRequisitionRepository.findById(reqId).orElseThrow();
    }

    private ScreeningResult result(Candidate c) {
        return screeningResultRepository
                .findByCandidateIdAndRequisitionId(c.getId(), reqId)
                .orElseThrow(() -> new AssertionError(
                        "No screening result found for candidate " + c.getFullName()));
    }

    private void assertInRange(BigDecimal score, String field) {
        assertThat(score).as(field + " not null").isNotNull();
        assertThat(score.compareTo(BigDecimal.ZERO)).as(field + " >= 0").isGreaterThanOrEqualTo(0);
        assertThat(score.compareTo(BigDecimal.valueOf(100))).as(field + " <= 100").isLessThanOrEqualTo(0);
    }
}
