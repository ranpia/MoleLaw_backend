package com.MoleLaw_backend.chat.dto.response;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvidenceSummaryResponseTest {
    @Test void repairsStoredEncodedLawLinksForBothSummaryAndNestedDetailWithoutMutatingSnapshot() {
        String legacy = "https://www.law.go.kr/%EB%B2%95%EB%A0%B9?lsId=001872&lsiSeq=285279";
        var item = new EvidenceItem("id", "LAW_QDRANT", "285279:1", "법령", legacy, "본문");
        var summary = EvidenceSummaryResponse.from(item);
        assertThat(summary.sourceUrl()).isEqualTo("https://www.law.go.kr/lsInfoP.do?lsiSeq=285279");
        assertThat(new EvidenceDetailResponse(summary, item.content(), null).evidence().sourceUrl()).isEqualTo(summary.sourceUrl());
        assertThat(item.sourceUrl()).isEqualTo(legacy);
    }
}
