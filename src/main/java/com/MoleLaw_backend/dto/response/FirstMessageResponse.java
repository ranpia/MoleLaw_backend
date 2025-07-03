package com.MoleLaw_backend.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class FirstMessageResponse {
    private Long id;
    private List<MessageResponse> messages;
}
