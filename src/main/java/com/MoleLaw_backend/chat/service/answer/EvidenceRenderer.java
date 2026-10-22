package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;

/** Renders source links from server evidence rather than model-generated URLs. */
@Component
public class EvidenceRenderer {
    public String render(List<EvidenceItem> evidence) {
        StringBuilder info = new StringBuilder("## 고정 근거\n\n");
        for (EvidenceItem item : evidence) {
            info.append("- [[").append(item.id()).append("]] [").append(escapeLabel(item.title()))
                    .append("](").append(sourceUrl(item.sourceUrl())).append(")\n  - ")
                    .append(item.content()).append('\n');
        }
        return info.toString();
    }

    private String escapeLabel(String title) {
        return title.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                .replace("(", "\\(").replace(")", "\\)").replace("\r", " ").replace("\n", " ");
    }

    private String sourceUrl(String sourceUrl) {
        URI uri = URI.create(sourceUrl);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Evidence source must be an HTTP(S) URL");
        }
        return uri.toASCIIString().replace("(", "%28").replace(")", "%29");
    }
}
