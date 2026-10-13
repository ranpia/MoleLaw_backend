package com.MoleLaw_backend.law.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** Optional official law-list filters. null status preserves the API's all-version default. */
public record LawSearchFilters(
        @Schema(description = "법령 ID. 1~20자리 숫자", example = "001823", pattern = "[0-9]{1,20}") String lawId,
        @Schema(description = "법령 종류 코드. A와 숫자 4자리", example = "A0002", pattern = "A[0-9]{4}") String kind,
        @Schema(description = "외부 API 법령 상태 구분. 생략 시 전체 버전 검색", example = "2", minimum = "1", maximum = "3") Integer status,
        @Schema(description = "법령명 완전 일치 검색 여부", example = "false") boolean exactName,
        @Schema(description = "정렬 코드. 생략 시 lasc", example = "lasc", defaultValue = "lasc",
                allowableValues = {"lasc", "ldes", "dasc", "ddes", "nasc", "ndes", "efasc", "efdes"}) String sort,
        @Schema(description = "시행일 범위. 시작일은 종료일 이하여야 합니다.", example = "20240101~20241231", pattern = "[0-9]{8}~[0-9]{8}") String effectiveDateRange,
        @Schema(description = "공포일 범위. 시작일은 종료일 이하여야 합니다.", example = "20240101~20241231", pattern = "[0-9]{8}~[0-9]{8}") String promulgationDateRange) {
    public static final LawSearchFilters NONE = new LawSearchFilters(null, null, null, false);

    public LawSearchFilters(String lawId, String kind, Integer status, boolean exactName) {
        this(lawId, kind, status, exactName, "lasc", null, null);
    }

    public LawSearchFilters {
        sort = sort == null ? "lasc" : sort;
        if ((lawId != null && !lawId.matches("[0-9]{1,20}"))
                || (kind != null && !kind.matches("A[0-9]{4}"))
                || (status != null && (status < 1 || status > 3))
                || !java.util.Set.of("lasc", "ldes", "dasc", "ddes", "nasc", "ndes", "efasc", "efdes").contains(sort)) {
            throw new IllegalArgumentException("Invalid law filters");
        }
        validateDateRange(effectiveDateRange);
        validateDateRange(promulgationDateRange);
    }

    private static void validateDateRange(String range) {
        if (range == null) return;
        if (!range.matches("[0-9]{8}~[0-9]{8}")) throw new IllegalArgumentException("Invalid date range");
        try {
            var start = java.time.LocalDate.parse(range.substring(0, 8), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            var end = java.time.LocalDate.parse(range.substring(9), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            if (start.isAfter(end)) throw new IllegalArgumentException("Reversed date range");
        } catch (java.time.format.DateTimeParseException failure) {
            throw new IllegalArgumentException("Invalid date range", failure);
        }
    }
}
