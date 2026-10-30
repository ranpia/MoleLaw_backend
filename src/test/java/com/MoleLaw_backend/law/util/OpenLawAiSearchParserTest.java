package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OpenLawAiSearchParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiLawSearchRequest request = new AiLawSearchRequest("공항", 1, 15);
    private final String article = """
            {"법령조문ID":"9001","법령ID":"001234","법령일련번호":"123456","법령명":"검증법",
             "시행일자":"20260101","조문번호":"0034","조문가지번호":"2","조문제목":"제한","조문내용":"실제 법령이 아닌 검증 자료"}
            """;

    @Test void parsesDocumentedFieldsAndPreservesBranchIdentity() throws Exception {
        var page = OpenLawAiSearchParser.parse(mapper.readTree("{\"AiSearch\":{\"검색결과개수\":20,\"법령조문\":[" + article + "]}}"), request);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.articles().get(0).articleNumber()).isEqualTo("34");
        assertThat(page.articles().get(0).articleBranchNumber()).isEqualTo("2");
        assertThat(page.articles().get(0).content()).isEqualTo("실제 법령이 아닌 검증 자료");
    }

    @Test void supportsSingleObjectAndNormalEmptyResultButRejectsProviderErrors() throws Exception {
        assertThat(OpenLawAiSearchParser.parse(mapper.readTree("{\"AiSearch\":{\"totalCnt\":1,\"law\":" + article + "}}"), request).articles()).hasSize(1);
        assertThat(OpenLawAiSearchParser.parse(mapper.readTree("{\"AiSearch\":{\"totalCnt\":0}}"), request).articles()).isEmpty();
        for (String body : new String[]{"{}", "{\"error\":\"denied\"}", "{\"AiSearch\":{\"totalCnt\":1}}"})
            assertThatThrownBy(() -> OpenLawAiSearchParser.parse(mapper.readTree(body), request)).isInstanceOf(LegalToolException.class);
    }

    @Test void rejectsMissingTextInvalidIdentityDuplicateArticlesAndInvalidDates() {
        for (String row : new String[]{article.replace("9001", "unknown"), article.replace("20260101", "20260230"),
                article.replace("실제 법령이 아닌 검증 자료", ""), article + "," + article}) {
            assertThatThrownBy(() -> OpenLawAiSearchParser.parse(mapper.readTree("{\"AiSearch\":{\"totalCnt\":2,\"law\":[" + row + "]}}"), request))
                    .isInstanceOf(LegalToolException.class);
        }
    }
}
