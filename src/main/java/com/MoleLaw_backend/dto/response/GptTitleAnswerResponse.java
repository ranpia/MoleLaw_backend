package com.MoleLaw_backend.dto.response;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GptTitleAnswerResponse {
    private String title;
    private String answer;
}
