package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.QueryRequest;
import com.MoleLaw_backend.service.FinalAnswer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/answer")
@RequiredArgsConstructor
public class AnswerController {

    private final FinalAnswer finalAnswer;

    @PostMapping
    public ResponseEntity<String> getAnswer(@RequestBody QueryRequest request) {
        String gptResponse = finalAnswer.getFinalAnswer(request.getQuery());
        return ResponseEntity.ok(gptResponse);
    }
}
