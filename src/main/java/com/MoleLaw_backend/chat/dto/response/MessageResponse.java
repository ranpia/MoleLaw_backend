package com.MoleLaw_backend.chat.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.MoleLaw_backend.chat.entity.Message;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class MessageResponse {
    @Schema(description = "발신 구분: USER(사용자), BOT(AI), INFO(참고 정보)", example = "USER")
    private String sender;
    @Schema(description = "메시지 본문", example = "퇴직금을 받지 못했습니다.")
    private String content;
    @Schema(description = "UTC 메시지 생성 시각. 가상 INFO는 최초 BOT의 시각을 사용합니다.", example = "2026-01-15T10:30:00")
    private LocalDateTime timestamp;

    @Schema(description = "봇이 새 판례 검색 동의를 요청했는지 여부. true이면 승인·거절 선택을 제시하고 승인할 때만 다음 요청의 allowCaseSearch=true를 전송하세요. 법령 맥락은 자동 선택하지 않습니다. 사용자가 선택하면 근거 조회 API의 LAW_ 항목 id들을 개수 상한 없이 caseSearchLawEvidenceIds 배열로 보내고, 선택하지 않으면 해당 필드를 생략하거나 []로 보내 검색어만으로 검색합니다.", example = "false")
    private boolean caseSearchRequested;

    public static MessageResponse from(Message message, String content) {
        return MessageResponse.builder()
                .sender(message.getSender().name())
                .content(content)
                .timestamp(message.getTimestamp())
                .caseSearchRequested(message.isCaseSearchRequested())
                .build();
    }
}
