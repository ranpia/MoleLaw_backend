package com.MoleLaw_backend.law.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Getter;
import lombok.Setter;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;

@Getter
@Setter
public class PrecedentSearchRequest {
    @Schema(description = "판례명 또는 본문 검색어; 필수 1~200자. 참조법령 필터와 별개입니다.", example = "손해배상", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 200)
    private String query; // 예: "근로기준법", "제34조", "퇴직금", "해고"

    @Schema(description = "검색 범위: 1 판례명, 2 본문", example = "1", defaultValue = "1", allowableValues = {"1", "2"})
    private int search = 1;
    @Schema(description = "검색 페이지", example = "1", defaultValue = "1", minimum = "1", maximum = "10000")
    private int page = 1;
    @Schema(description = "페이지당 결과 수", example = "5", defaultValue = "5", minimum = "1", maximum = "100")
    private int display = 5;
    @Schema(description = "참조법령명 필터(JO); 선택, 1~200자", example = "민법", maxLength = 200)
    private String referenceLaw;
    @Schema(description = "법원 종류(org); 생략하면 전체", example = "400201", allowableValues = {"400201", "400202"})
    private String courtType;
    @Schema(description = "법원명 필터(curt); 선택, 1~100자", example = "대법원", maxLength = 100)
    private String courtName;

    public void validate() {
        if (query == null || query.isBlank() || query.length() > 200 || (search != 1 && search != 2)
                || page < 1 || page > 10000 || display < 1 || display > 100
                || (referenceLaw != null && (referenceLaw.isBlank() || referenceLaw.length() > 200))
                || (courtType != null && !courtType.equals("400201") && !courtType.equals("400202"))
                || (courtName != null && (courtName.isBlank() || courtName.length() > 100))) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST);
        }
    }
}
