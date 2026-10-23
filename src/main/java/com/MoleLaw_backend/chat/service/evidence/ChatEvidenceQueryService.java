package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.chat.dto.response.CaseSourceResponse;
import com.MoleLaw_backend.chat.dto.response.EvidenceDetailResponse;
import com.MoleLaw_backend.chat.dto.response.EvidenceSummaryResponse;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatEvidenceQueryService {
    private final ChatEvidenceService evidence;
    private final ObjectMapper mapper;

    public List<EvidenceSummaryResponse> list(Long userId, Long roomId) {
        return evidence.load(userId, roomId).stream().map(EvidenceSummaryResponse::from).toList();
    }

    public EvidenceDetailResponse detail(Long userId, Long roomId, String id) {
        var item = evidence.load(userId, roomId).stream().filter(value -> value.id().equals(id))
                .findFirst().orElseThrow(() -> new MolelawException(ErrorCode.NOT_FOUND));
        if (!"CASE_BODY".equals(item.kind())) return new EvidenceDetailResponse(EvidenceSummaryResponse.from(item), item.content(), null);
        try {
            var body = mapper.readTree(item.content());
            return new EvidenceDetailResponse(EvidenceSummaryResponse.from(item), body.path("판례내용").asText(),
                    new CaseSourceResponse(text(body, "사건번호"), text(body, "법원명"), text(body, "선고일자"),
                            text(body, "판시사항"), text(body, "판결요지"), text(body, "참조조문"), text(body, "참조판례")));
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE);
        }
    }

    private String text(JsonNode source, String field) {
        String value = source.path(field).asText();
        return value.isBlank() ? null : value;
    }
}
