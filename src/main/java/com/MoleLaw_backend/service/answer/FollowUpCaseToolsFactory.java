package com.MoleLaw_backend.service.answer;

import com.MoleLaw_backend.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.service.tool.CaseEvidenceReader;
import com.MoleLaw_backend.service.tool.LegalApiToolService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.UUID;

/** Creates isolated, server-bound tools for one authorized room turn. */
@Service
@RequiredArgsConstructor
public class FollowUpCaseToolsFactory {
    private final LegalApiToolService api;
    private final CaseEvidenceReader reader;
    private final com.MoleLaw_backend.service.retrieval.CaseSearchPipeline pipeline;
    @org.springframework.beans.factory.annotation.Value("${app.case-search.body-max-input-tokens:32000}")
    private int bodyMaxInputTokens = 32000;

    public FollowUpCaseSession create(List<EvidenceItem> roomEvidence) { return create(roomEvidence, false); }
    public FollowUpCaseSession create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch) {
        return create(roomEvidence, allowCaseSearch, UUID.randomUUID().toString(), stage -> {});
    }
    public FollowUpCaseSession create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch, String traceId, java.util.function.Consumer<String> progress) {
        return create(roomEvidence, allowCaseSearch, traceId, null, progress);
    }
    public FollowUpCaseSession create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch, String traceId, Long roomId, java.util.function.Consumer<String> progress) {
        return new FollowUpCaseSession(api, reader, pipeline, roomEvidence, allowCaseSearch, traceId, roomId, progress, bodyMaxInputTokens);
    }

    public FollowUpCaseSession create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch, String traceId, Long roomId,
            java.util.function.Consumer<String> progress, String userQuestion) {
        var session = create(roomEvidence, allowCaseSearch, traceId, roomId, progress);
        session.userQuestion(userQuestion);
        return session;
    }

}
