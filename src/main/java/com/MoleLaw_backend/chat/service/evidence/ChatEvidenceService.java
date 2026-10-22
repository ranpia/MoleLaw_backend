package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;

import com.MoleLaw_backend.chat.entity.ChatEvidenceSnapshot;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.repository.ChatEvidenceSnapshotRepository;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChatEvidenceService {
    private static final int MAX_ITEMS = 100;
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final Set<String> KINDS = Set.of("LAW_CHUNK", "CASE_METADATA", "LAW_QDRANT");
    private final ChatRoomRepository rooms;
    private final ChatEvidenceSnapshotRepository snapshots;
    private final ObjectMapper mapper;

    /** Called inside the initial successful-turn transaction; an existing snapshot is never replaced. */
    @Transactional
    public void freeze(ChatRoom room, List<EvidenceItem> evidence) {
        if (room == null || room.getId() == null) {
            throw failure();
        }
        validate(evidence);
        if (snapshots.existsById(room.getId())) {
            throw failure();
        }
        try {
            String json = mapper.writeValueAsString(List.copyOf(evidence));
            checkSize(json);
            snapshots.saveAndFlush(new ChatEvidenceSnapshot(room, json, hash(json)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "근거 스냅샷 저장 실패", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<EvidenceItem> load(Long userId, Long roomId) {
        if (userId == null) {
            throw new MolelawException(ErrorCode.USER_NOT_AUTHENTICATED);
        }
        if (roomId == null) {
            throw new MolelawException(ErrorCode.CHATROOM_NOT_FOUND);
        }
        ChatRoom room = rooms.findById(roomId)
                .orElseThrow(() -> new MolelawException(ErrorCode.CHATROOM_NOT_FOUND));
        if (room.getUser() == null || !userId.equals(room.getUser().getId())) {
            throw new MolelawException(ErrorCode.UNAUTHORIZED_CHATROOM_ACCESS);
        }
        ChatEvidenceSnapshot snapshot = snapshots.findById(roomId).orElseThrow(ChatEvidenceService::failure);
        if (snapshot.getSchemaVersion() < 1
                || snapshot.getSchemaVersion() > ChatEvidenceSnapshot.CURRENT_SCHEMA_VERSION) {
            throw failure();
        }
        String json = snapshot.getEvidenceJson();
        checkSize(json);
        if (!hash(json).equals(snapshot.getContentHash())) {
            throw failure();
        }
        try {
            List<EvidenceItem> evidence = mapper.readValue(json, new TypeReference<List<EvidenceItem>>() {});
            validate(evidence);
            return List.copyOf(evidence);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "근거 스냅샷 복원 실패", ex);
        }
    }

    private static void validate(List<EvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty() || evidence.size() > MAX_ITEMS) {
            throw failure();
        }
        Set<String> ids = new HashSet<>();
        for (EvidenceItem item : evidence) {
            if (item == null || blank(item.id()) || !KINDS.contains(item.kind() == null ? "" : item.kind())
                    || blank(item.sourceId()) || blank(item.title()) || blank(item.sourceUrl())
                    || blank(item.content()) || !ids.add(item.id())) {
                throw failure();
            }
            try {
                if (!UUID.fromString(item.id()).toString().equals(item.id())) {
                    throw failure();
                }
            } catch (IllegalArgumentException ex) {
                throw failure();
            }
            validateProvenance(item);
        }
    }

    private static void validateProvenance(EvidenceItem item) {
        EvidenceProvenance p = item.provenance();
        if (p == null) {
            if ("LAW_QDRANT".equals(item.kind())) throw failure();
            return;
        }
        if (p.sourceUuid() != null) {
            try {
                if (!UUID.fromString(p.sourceUuid()).toString().equals(p.sourceUuid())) throw failure();
            } catch (IllegalArgumentException ex) {
                throw failure();
            }
        }
        if ((p.mst() != null && !p.mst().matches("[0-9]{1,20}"))
                || (p.lawId() != null && !p.lawId().matches("[0-9]{1,20}"))) throw failure();
        if (p.effectiveDate() != null) {
            try {
                java.time.LocalDate.parse(p.effectiveDate(), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            } catch (java.time.format.DateTimeParseException ex) {
                throw failure();
            }
        }
        for (String value : new String[]{p.articlePath(), p.modelRevision(), p.configFingerprint(), p.generation()}) {
            if (value != null && (value.isBlank() || value.length() > 2048)) throw failure();
        }
        if ("LAW_QDRANT".equals(item.kind()) && (blank(p.sourceUuid()) || blank(p.mst()) || blank(p.articlePath()))) {
            throw failure();
        }
        if ("LAW_QDRANT".equals(item.kind()) && !item.sourceId().equals(p.mst() + ":" + p.articlePath())) {
            throw failure();
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void checkSize(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw failure();
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static MolelawException failure() {
        return new MolelawException(ErrorCode.GPT_API_FAILURE, "검증된 최초 근거 스냅샷을 사용할 수 없습니다");
    }
}
