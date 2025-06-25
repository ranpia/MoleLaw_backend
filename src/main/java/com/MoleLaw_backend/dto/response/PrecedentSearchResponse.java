package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PrecedentSearchResponse {

    @JsonProperty("PrecSearch")
    private PrecSearch precSearch;

    @Getter
    @Setter
    public static class PrecSearch {

        @JsonProperty("prec") // ❗ 필수: 실제 JSON 키가 "prec"
        private List<PrecedentInfo> prec;

        @JsonProperty("키워드")
        private String keyword;

        @JsonProperty("page")
        private int page;

        @JsonProperty("section")
        private String section;

        @JsonProperty("totalCnt")
        private int totalCnt;
    }
}




