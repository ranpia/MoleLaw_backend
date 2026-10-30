package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Downstream roles already receive the original question; source quotations stay on the validated plan. */
public final class QuestionPlanInput {
    private QuestionPlanInput() { }

    public static ObjectNode view(QuestionRetrievalPlan plan, ObjectMapper mapper) {
        ObjectNode view = mapper.valueToTree(plan);
        if (plan.analysis() == null) return view;
        ObjectNode analysis = (ObjectNode) view.path("analysis");
        analysis.remove("title");
        analysis.path("facts").forEach(fact -> ((ObjectNode) fact).remove("sourceQuote"));
        analysis.path("issues").forEach(issue -> ((ObjectNode) issue).remove("sourceQuote"));
        return view;
    }
}
