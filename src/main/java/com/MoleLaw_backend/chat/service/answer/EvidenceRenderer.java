package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.util.LawSourceUrl;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/** Renders source links from server evidence rather than model-generated URLs. */
@Component
public class EvidenceRenderer {
    public String render(List<EvidenceItem> evidence) {
        return render(evidence, id -> "[[" + id + "]]");
    }

    public String renderForStream(List<EvidenceItem> evidence, java.util.function.Function<String, String> label) {
        return render(evidence, label);
    }

    private String render(List<EvidenceItem> evidence, java.util.function.Function<String, String> label) {
        StringBuilder info = new StringBuilder("## 고정 근거\n\n");
        for (EvidenceItem item : evidence) {
            info.append("- ").append(label.apply(item.id())).append(" [").append(escapeLabel(item.title()))
                    .append("](").append(sourceUrl(item.sourceUrl())).append(")\n  - ")
                    .append(displayBody(item)).append('\n');
        }
        return info.toString();
    }

    private String displayBody(EvidenceItem item) {
        if (!"CASE_BODY".equals(item.kind()) || !item.content().stripLeading().startsWith("{"))
            return com.MoleLaw_backend.chat.util.EvidenceDisplayText.plain(item.content());
        try {
            var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree(item.content());
            String summary = raw.path("판결요지").asText();
            if (summary.isBlank()) summary = raw.path("판시사항").asText();
            summary = com.MoleLaw_backend.chat.util.EvidenceDisplayText.plain(summary);
            return summary.isBlank() ? "판결 본문 확인 완료. 전체 내용은 공식 출처에서 확인할 수 있습니다."
                    : "판결 요지 발췌: " + summary.substring(0, Math.min(summary.length(), 2000));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid case source JSON");
        }
    }

    private String escapeLabel(String title) {
        title = com.MoleLaw_backend.chat.util.EvidenceDisplayText.plain(title);
        return title.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)").replace("\r", " ").replace("\n", " ");
    }

    private String sourceUrl(String sourceUrl) {
        URI uri = URI.create(LawSourceUrl.publicLink(sourceUrl));
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Evidence source must be an HTTP(S) URL");
        }
        return uri.toASCIIString().replace("(", "%28").replace(")", "%29");
    }
}
