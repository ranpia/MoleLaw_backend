package com.MoleLaw_backend.dto.request;

/** Optional official law-list filters. null status preserves the API's all-version default. */
public record LawSearchFilters(String lawId, String kind, Integer status, boolean exactName,
                               String sort, String effectiveDateRange, String promulgationDateRange) {
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
