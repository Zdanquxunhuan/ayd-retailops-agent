package com.yu.retailops.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Durable execution cursor. User answers are untrusted context, never tool evidence. */
public class AgentState {
    public String schemaVersion = "agent-state-v1";
    public String nextStep = "COLLECT_FACTS";
    public Investigation.Query pendingQuery;
    public Investigation.ValidationFeedback feedback;
    public boolean correctionUsed;
    public String question;
    public Instant waitingUntil;
    public List<UserAnswer> answers = new ArrayList<>();
    public record UserAnswer(String question, String answer, Instant at) {}
}
