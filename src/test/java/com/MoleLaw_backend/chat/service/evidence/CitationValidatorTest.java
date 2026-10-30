package com.MoleLaw_backend.chat.service.evidence;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CitationValidatorTest {
    @Test void exposesSafeDiagnosticReasonsWithoutChangingThePublicFailureCode() {
        var missing = assertThrows(com.MoleLaw_backend.chat.exception.CitationValidationException.class,
                () -> validator.validate("인용 없는 설명", List.of(item)));
        assertEquals(com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.MISSING_CITATION, missing.reason());
        assertEquals(ErrorCode.GPT_API_FAILURE, missing.getErrorCode());
        var unknown = assertThrows(com.MoleLaw_backend.chat.exception.CitationValidationException.class,
                () -> validator.validate("설명 [[764a37ed-3ffd-4ae4-a1c4-0170544a987a]]", List.of(item)));
        assertEquals(com.MoleLaw_backend.chat.exception.CitationValidationException.Reason.UNKNOWN_ID, unknown.reason());
    }

    private static final String ID = "65ead687-bda9-4fce-aa72-056efec095a6";
    private final CitationValidator validator = new CitationValidator();
    private final EvidenceItem item = new EvidenceItem(ID, "LAW", "source", "민법",
            "https://www.law.go.kr/법령/민법", "원문");

    @Test
    void retainsKnownCitationIdsForStorageAndAllowsRepeatedReferences() {
        String answer = "설명 [[" + ID + "]] 추가 설명 [[" + ID + "]]";
        assertEquals(answer, validator.validate(answer, List.of(item)));
    }

    @Test
    void rejectsCitationBelongingToAnotherSnapshot() {
        rejects("설명 [[764a37ed-3ffd-4ae4-a1c4-0170544a987a]]", List.of(item));
    }

    @Test
    void requiresCitationWhenSnapshotContainsEvidence() {
        rejects("출처 없는 답변", List.of(item));
    }

    @Test
    void rejectsMalformedMarkersEvenAlongsideAValidCitation() {
        for (String suffix : List.of(" [[missing]]", " [[", " ]]", " [[" + ID.toUpperCase() + "]]")) {
            rejects("설명 [[" + ID + "]]" + suffix, List.of(item));
        }
    }

    @Test
    void rejectsModelSuppliedUrlsEvenIfTheyMatchAnApprovedSource() {
        rejects("설명 [[" + ID + "]] https://www.law.go.kr/법령/민법", List.of(item));
        rejects("설명 [[" + ID + "]] [출처](HTTPS://attacker.example)", List.of(item));
    }

    @Test
    void emptySnapshotAllowsPlainAnswerButCannotInventCitations() {
        assertEquals("근거가 부족합니다.", validator.validate("근거가 부족합니다.", List.of()));
        rejects("설명 [[" + ID + "]]", List.of());
    }

    @Test
    void rejectsDuplicateEvidenceIdsAndMissingSnapshot() {
        rejects("설명 [[" + ID + "]]", List.of(item, item));
        rejects("설명", null);
    }

    @Test
    void mapsBlankResponseToEmptyResponseError() {
        for (String answer : new String[]{null, "", " \n"}) {
            GptApiException error = assertThrows(GptApiException.class,
                    () -> validator.validate(answer, List.of(item)));
            assertEquals(ErrorCode.GPT_EMPTY_RESPONSE, error.getErrorCode());
        }
    }

    private void rejects(String answer, List<EvidenceItem> evidence) {
        GptApiException error = assertThrows(GptApiException.class,
                () -> validator.validate(answer, evidence));
        assertEquals(ErrorCode.GPT_API_FAILURE, error.getErrorCode());
    }
}
