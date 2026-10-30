package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.chat.exception.CitationValidationException;
import static com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.MISSING_EVIDENCE;
import static com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.DUPLICATE_EVIDENCE;
import static com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.MODEL_URL;
import static com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.UNKNOWN_ID;
import static com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.MALFORMED_MARKER;
import static com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.MISSING_CITATION;
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
        if (evidence == null) throw new CitationValidationException(MISSING_EVIDENCE);
        if (URL.matcher(answer).find()) throw new CitationValidationException(MODEL_URL);
        Set<String> allowed = new HashSet<>();
        for (EvidenceItem item : evidence) {
            if (item == null || item.id() == null || !allowed.add(item.id())) {
                throw new CitationValidationException(DUPLICATE_EVIDENCE);
            }
        }
        Matcher matcher = CITATION.matcher(answer);
        int citations = 0;
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1))) {
                throw new CitationValidationException(UNKNOWN_ID);
            }
            citations++;
        }
        String remaining = CITATION.matcher(answer).replaceAll("");
        if (remaining.contains("[[") || remaining.contains("]]")) {
            throw new CitationValidationException(MALFORMED_MARKER);
        }
        if (!evidence.isEmpty() && citations == 0) {
            throw new CitationValidationException(MISSING_CITATION);
        }
        return answer;
    }

}
