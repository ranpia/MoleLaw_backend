package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ChatEvidenceQueryServiceTest {
    @Test void detailAndMetadataArePlainTextWhileStoredSourceRemainsUnchanged() throws Exception {
        var store = mock(ChatEvidenceService.class);
        var mapper = new ObjectMapper();
        String raw = mapper.writeValueAsString(java.util.Map.of("판례내용", "<p>주문<br>파기</p><p>판결 이유</p>",
                "판결요지", "<b>책임</b>&nbsp;범위", "참조조문", "민법<br>제750조"));
        var item = new EvidenceItem("id", "CASE_BODY", "101", "<b>손해배상</b>", "https://www.law.go.kr", raw);
        when(store.load(1L, 2L)).thenReturn(List.of(item));
        var service = new ChatEvidenceQueryService(store, mapper);
        var response = service.detail(1L, 2L, "id");
        assertThat(response.content()).isEqualTo("주문\n파기\n판결 이유");
        assertThat(response.caseInfo().summary()).isEqualTo("책임 범위");
        assertThat(response.caseInfo().referenceLaws()).isEqualTo("민법\n제750조");
        assertThat(service.list(1L, 2L).get(0).title()).isEqualTo("손해배상");
        assertThat(item.content()).isEqualTo(raw);
    }
}
