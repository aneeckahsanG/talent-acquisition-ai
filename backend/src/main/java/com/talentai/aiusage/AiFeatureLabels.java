package com.talentai.aiusage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns the technical origin of an AI call into something a reader can act on.
 *
 * The usage log stores {@code Class.method}, which is right for tracing but unreadable on a
 * dashboard. Here that becomes an agent name and a plain-English purpose, while the raw value is
 * still carried alongside so an engineer can find the code.
 */
final class AiFeatureLabels {

    private AiFeatureLabels() {
    }

    /** Which agent the calling class belongs to. Order matters: first prefix match wins. */
    private static final Map<String, String> AGENT_BY_CLASS = new LinkedHashMap<>() {{
        put("SourcingAgentService",          "Sourcing Agent");
        put("ScreeningAgentService",         "Screening Agent");
        put("ReferralAgentService",          "Referral Agent");
        put("AdminAgentService",             "Coordination Agent");
        put("InterviewReminderService",      "Coordination Agent");
        put("RecruitingOrchestratorService", "Orchestrator Agent");
        put("AgentLoopService",              "Orchestrator Agent");
        put("RecruitingTools",               "Orchestrator Agent");
        put("AssistantService",              "Assistant");
        put("InterviewDebriefService",       "Interview Debrief");
        put("LinkedInParseService",          "LinkedIn Parsing");
        put("RequisitionService",            "Job Requisitions");
        put("CvInboxProcessingService",      "Resume Drop Zone");
        put("PublicJobController",           "Careers Page");
    }};

    /** What the call is actually for, keyed by method name. */
    private static final Map<String, String> PURPOSE_BY_METHOD = new LinkedHashMap<>() {{
        put("scoreCandidateAgainstRequisition", "Score a candidate against a role");
        put("matchCandidateToRequisition",      "Score a candidate and save the match");
        put("matchExistingCandidateToOpenRoles","Match a candidate to every open role");
        put("scanTalentPoolForNewRequisition",  "Scan the talent pool for a new role");
        put("draftOutreach",                    "Draft an outreach email");
        put("draftOutreachForCandidate",        "Draft an outreach email");
        put("runScreening",                     "Screen a candidate against the rubric");
        put("runScreeningInternal",             "Screen a candidate against the rubric");
        put("screenCandidate",                  "Screen a candidate");
        put("screenExistingCandidate",          "Screen a candidate");
        put("screenCandidateFromFile",          "Screen an uploaded CV");
        put("screenOrReuse",                    "Screen a candidate");
        put("extractNameAndEmail",              "Read the name and email from a CV");
        put("parseResume",                      "Read the name and email from a CV");
        put("generateJd",                       "Write a job description from a title");
        put("parseJd",                          "Parse a pasted job description");
        put("bulkParse",                        "Parse several roles at once");
        put("chat",                             "Answer a question in the app");
        put("runLoop",                          "Decide what to do with an application");
        put("proposeInterview",                 "Propose interview times");
        put("sendStatusUpdate",                 "Write a candidate status update");
        put("generateOfferLetter",              "Write an offer letter");
        put("debrief",                          "Combine the interview scorecards");
        put("generate",                         "Combine the interview scorecards");
        put("matchReferral",                    "Match a referral to a role");
        put("parse",                            "Read a LinkedIn profile");
    }};

    /**
     * javac names a lambda body {@code lambda$enclosingMethod$N}, where N counts lambdas across the
     * whole class and shifts whenever one is added or moved. Collapsing it back to the real method
     * keeps one feature on one row and stops older rows being mislabelled.
     */
    static String normalise(String feature) {
        if (feature == null || feature.isBlank()) return "Unknown";
        int dot = feature.lastIndexOf('.');
        if (dot < 0) return feature;
        String method = feature.substring(dot + 1).replaceAll("^lambda\\$(.+)\\$\\d+$", "$1");
        return feature.substring(0, dot) + "." + method;
    }

    static String agentOf(String feature) {
        String cls = className(feature);
        for (var e : AGENT_BY_CLASS.entrySet()) {
            if (cls.startsWith(e.getKey())) return e.getValue();
        }
        return cls.replaceAll("(Service|Controller)$", "");
    }

    static String purposeOf(String feature) {
        String method = methodName(feature);
        String purpose = PURPOSE_BY_METHOD.get(method);
        if (purpose != null) return purpose;
        // Fall back to the method name split into words: "sendOutreachEmail" -> "Send outreach email"
        String words = method.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase();
        return words.isBlank() ? "AI call" : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String className(String feature) {
        int dot = feature.lastIndexOf('.');
        return dot < 0 ? feature : feature.substring(0, dot);
    }

    private static String methodName(String feature) {
        int dot = feature.lastIndexOf('.');
        return dot < 0 ? feature : feature.substring(dot + 1);
    }
}
