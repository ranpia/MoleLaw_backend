package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.request.QueryRequest;
import com.MoleLaw_backend.dto.response.AnswerResponse;
import com.MoleLaw_backend.service.law.FinalAnswer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/answer")
@RequiredArgsConstructor
public class AnswerController {

    private final FinalAnswer finalAnswer;

    @PostMapping
    public ResponseEntity<AnswerResponse> getAnswer(@RequestBody QueryRequest request) {
        return ResponseEntity.ok(finalAnswer.getAnswer(request.getQuery()));
    }

}
