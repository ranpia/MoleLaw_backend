package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates snapshot membership, not whether a cited source entails a legal claim. */
@Component
public class CitationValidator {
    private static final Pattern CITATION = Pattern.compile(
            "\\[\\[([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})]]");
    private static final Pattern URL = Pattern.compile("https?\\s*:", Pattern.CASE_INSENSITIVE);

    public String validate(String answer, List<EvidenceItem> evidence) {
        if (answer == null || answer.isBlank()) {
            throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE);
        }
        if (evidence == null || URL.matcher(answer).find()) {
            throw invalidCitation();
        }
        Set<String> allowed = new HashSet<>();
        for (EvidenceItem item : evidence) {
            if (item == null || item.id() == null || !allowed.add(item.id())) {
                throw invalidCitation();
            }
        }
        Matcher matcher = CITATION.matcher(answer);
        int citations = 0;
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw invalidCitation();
            }
            citations++;
        }
        String remaining = CITATION.matcher(answer).replaceAll("");
        if (remaining.contains("[[") || remaining.contains("]]")) {
            throw invalidCitation();
        }
        if (!evidence.isEmpty() && citations == 0) {
            throw invalidCitation();
        }
        return answer;
    }

    private GptApiException invalidCitation() {
        return new GptApiException(ErrorCode.GPT_API_FAILURE);
    }
}
