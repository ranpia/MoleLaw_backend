package com.MoleLaw_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class KeywordAndTitleResponse {
    private List<String> keywords;
    private String summary;
}
