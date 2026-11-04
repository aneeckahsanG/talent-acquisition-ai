package com.talentai.agent;

import com.talentai.orchestrator.agent.AgentTrace;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** An {@link AgentTrace} for tests of {@code RecruitingTools}: every tool call gets a span that records nothing. */
final class NoOpAgentTrace {

    private NoOpAgentTrace() {}

    static AgentTrace create() {
        AgentTrace trace = mock(AgentTrace.class);
        when(trace.tool(any(), any())).thenReturn(mock(AgentTrace.Span.class));
        return trace;
    }
}
