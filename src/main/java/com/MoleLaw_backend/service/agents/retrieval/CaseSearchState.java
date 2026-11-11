package com.MoleLaw_backend.service.agents.retrieval;

import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.dto.retrieval.CaseQueryPlan;
import com.MoleLaw_backend.dto.retrieval.CaseSearchScope;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mutable state owned by one authorized case-search turn; never a singleton bean. */
public final class CaseSearchState {
    final Long roomId;
    public CaseSearchState() { this(null); }
    public CaseSearchState(Long roomId) { this.roomId = roomId; }
    public Long roomId() { return roomId; }
    String userQuestion;
    List<EvidenceItem> lawContexts;
    public void lawContexts(List<EvidenceItem> value) { lawContexts = List.copyOf(value); }
    CaseQueryPlan searchPlan;
    public void userQuestion(String value) { userQuestion = value; }
    public CaseQueryPlan searchPlan() { return searchPlan; }
    CaseSearchScope scope = CaseSearchScope.BODY;
    public void scope(CaseSearchScope value) { scope = value == null ? CaseSearchScope.BODY : value; }
    boolean currentSearchComplete = true;
    boolean currentRouteFailed;
    final Map<String, EvidenceItem> bodies = new LinkedHashMap<>();
    final Set<String> attempted = new HashSet<>();
    final Set<String> searches = new HashSet<>();
    final Set<String> plannedInputs = new HashSet<>();
    int rerankCalls;

}
