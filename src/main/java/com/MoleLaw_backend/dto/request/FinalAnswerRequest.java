package com.MoleLaw_backend.dto.request;


import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class FinalAnswerRequest {
    private String query;
    private KeywordAndTitleResponse keywordInfo;
}
