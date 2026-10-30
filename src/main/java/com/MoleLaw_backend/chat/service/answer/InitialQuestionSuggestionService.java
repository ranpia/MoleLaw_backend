package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.answer.InitialQuestionSuggestionDraft;
import com.MoleLaw_backend.chat.dto.response.InitialQuestionSuggestionsResponse;
import com.MoleLaw_backend.chat.dto.response.InitialQuestionSuggestionsResponse.SuggestedQuestion;
import com.MoleLaw_backend.chat.service.ChatContentLimits;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class InitialQuestionSuggestionService {
    private final ChatClient client;
    private final ObjectMapper mapper;

    public InitialQuestionSuggestionService(@Qualifier("initialQuestionSuggestionChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public InitialQuestionSuggestionsResponse suggest(String query) {
        ChatContentLimits.question(query);
        int remaining = ChatContentLimits.MAX_QUESTION_CODE_POINTS - query.codePointCount(0, query.length()) - 2;
        if (remaining < 32) return new InitialQuestionSuggestionsResponse(List.of(
                new SuggestedQuestion("suggestion-1", "작성한 질문으로 상담", query)));
        try {
            int limit = Math.min(300, remaining);
            String output = client.prompt().user(mapper.writeValueAsString(Map.of("query", query, "focusMaxCharacters", limit)))
                    .call().content();
            if (output == null || output.length() > 8192) throw new IllegalArgumentException();
            var draft = mapper.readerFor(InitialQuestionSuggestionDraft.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).<InitialQuestionSuggestionDraft>readValue(output);
            if (draft == null || draft.suggestions() == null || draft.suggestions().isEmpty() || draft.suggestions().size() > 3)
                throw new IllegalArgumentException();
            var result = new ArrayList<SuggestedQuestion>();
            var labels = new HashSet<String>();
            var focuses = new HashSet<String>();
            for (var proposal : draft.suggestions()) {
                if (proposal == null) throw new IllegalArgumentException();
                String label = bounded(proposal.label(), 40), focus = bounded(proposal.focus(), limit);
                if (!labels.add(key(label)) || !focuses.add(key(focus))) throw new IllegalArgumentException();
                String question = query + "\n\n" + focus;
                ChatContentLimits.question(question);
                result.add(new SuggestedQuestion("suggestion-" + (result.size() + 1), label, question));
            }
            return new InitialQuestionSuggestionsResponse(result);
        } catch (Exception invalid) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }

    private String bounded(String value, int limit) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit) throw new IllegalArgumentException();
        return value.strip();
    }

    private String key(String value) { return value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
}
