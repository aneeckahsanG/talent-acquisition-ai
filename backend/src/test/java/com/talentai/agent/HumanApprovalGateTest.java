package com.talentai.agent;

import com.talentai.agent.service.HumanApprovalGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HumanApprovalGateTest {

    private HumanApprovalGate gate;

    @BeforeEach
    void setUp() {
        gate = new HumanApprovalGate();
    }

    @Test
    @DisplayName("a request keeps the tool, the exact content and the run it belongs to")
    void requestCarriesWhatIsBeingApproved() {
        gate.requestApproval("sendOutreach:7", "Send an outreach email", "sendOutreach", "Hi Priya", 5L);

        var pending = gate.pendingForRun(5L);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).toolName()).isEqualTo("sendOutreach");
        assertThat(pending.get(0).payload()).isEqualTo("Hi Priya");
        assertThat(gate.isApproved("sendOutreach:7")).isFalse();
    }

    @Test
    @DisplayName("approving a run approves what that run is waiting on and nothing else")
    void approveRunOnlyTouchesThatRun() {
        gate.requestApproval("a", "A", "sendOutreach", "x", 5L);
        gate.requestApproval("b", "B", "moveCandidateStage", null, 5L);
        gate.requestApproval("c", "C", "sendOutreach", "y", 6L);

        int approved = gate.approveRun(5L);

        assertThat(approved).isEqualTo(2);
        assertThat(gate.isApproved("a")).isTrue();
        assertThat(gate.isApproved("b")).isTrue();
        assertThat(gate.isApproved("c")).isFalse();
        assertThat(gate.pendingForRun(5L)).isEmpty();
        assertThat(gate.pendingForRun(6L)).hasSize(1);
    }

    @Test
    @DisplayName("the approved content is what gets sent")
    void approvedPayloadIsAvailableOnceApproved() {
        gate.requestApproval("sendOutreach:7", "Send", "sendOutreach", "Hi Priya", 5L);
        assertThat(gate.approvedPayload("sendOutreach:7")).isEmpty();

        gate.approveRun(5L);

        assertThat(gate.approvedPayload("sendOutreach:7")).contains("Hi Priya");
    }

    @Test
    @DisplayName("a grant is used up once the action has run")
    void consumeEndsTheGrant() {
        gate.requestApproval("a", "A", "sendOutreach", "x", 5L);
        gate.approveRun(5L);

        gate.consume("a");

        assertThat(gate.isApproved("a")).isFalse();
    }

    @Test
    @DisplayName("denying a run leaves nothing pending and the same action has to be asked for again")
    void denyRunClearsIt() {
        gate.requestApproval("a", "A", "sendOutreach", "x", 5L);

        gate.denyRun(5L);

        assertThat(gate.pendingForRun(5L)).isEmpty();
        assertThat(gate.isApproved("a")).isFalse();
        assertThat(gate.requestApproval("a", "A", "sendOutreach", "x2", 5L)).isNotNull();
        assertThat(gate.pendingForRun(5L)).hasSize(1);
    }

    @Test
    @DisplayName("asking for the same action again reuses the request and hands it to the newer run")
    void sameActionFromANewerRunIsTheSameRequest() {
        String first = gate.requestApproval("a", "A", "sendOutreach", "x", 5L);
        String second = gate.requestApproval("a", "A", "sendOutreach", "other text", 6L);

        assertThat(second).isEqualTo(first);
        assertThat(gate.pendingForRun(5L)).isEmpty();
        assertThat(gate.pendingForRun(6L)).hasSize(1);
        assertThat(gate.pendingForRun(6L).get(0).payload()).isEqualTo("x");
    }

    @Test
    @DisplayName("a grant restored from a saved run works like a normal approval")
    void grantRestoresAnApproval() {
        gate.grant("sendOutreach:7", "Send", "sendOutreach", "Hi Priya", 5L);

        assertThat(gate.isApproved("sendOutreach:7")).isTrue();
        assertThat(gate.approvedPayload("sendOutreach:7")).contains("Hi Priya");
    }

    @Test
    @DisplayName("the plain two-argument request still works for callers that have no run")
    void plainRequestStillWorks() {
        String id = gate.requestApproval("a", "A");

        assertThat(gate.listPending()).extracting(HumanApprovalGate.PendingApproval::approvalId).containsExactly(id);
        assertThat(gate.approve(id)).isTrue();
        assertThat(gate.isApproved("a")).isTrue();
    }
}
