package com.MoleLaw_backend.chat.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CreateMessageRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void validatesOptionalSelectedLawAndExplicitConsent() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String valid : java.util.List.of(
                    "{\"content\":\"질문\"}",
                    "{\"content\":\"질문\",\"caseSearchLawEvidenceIds\":null}",
                    "{\"content\":\"질문\",\"caseSearchLawEvidenceIds\":[]}",
                    "{\"content\":\"네\",\"allowCaseSearch\":true,\"caseSearchLawEvidenceIds\":[\"00000000-0000-0000-0000-000000000002\"]}")) {
                assertThat(validator.validate(mapper.readValue(valid, CreateMessageRequest.class))).isEmpty();
            }
            var many = mapper.createObjectNode().put("content", "검색").put("allowCaseSearch", true);
            var ids = many.putArray("caseSearchLawEvidenceIds");
            for (int index = 1; index <= 100; index++) ids.add("00000000-0000-0000-0000-" + String.format("%012d", index));
            assertThat(validator.validate(mapper.treeToValue(many, CreateMessageRequest.class))).isEmpty();
            for (String invalid : java.util.List.of(
                    "{\"content\":\"네\",\"caseSearchLawEvidenceIds\":[\"00000000-0000-0000-0000-000000000002\"]}",
                    "{\"content\":\"네\",\"allowCaseSearch\":true,\"caseSearchLawEvidenceIds\":[\"\"]}",
                    "{\"content\":\"네\",\"allowCaseSearch\":true,\"caseSearchLawEvidenceIds\":[\"invalid\"]}")) {
                assertThat(validator.validate(mapper.readValue(invalid, CreateMessageRequest.class))).isNotEmpty();
            }
        }
    }
}
