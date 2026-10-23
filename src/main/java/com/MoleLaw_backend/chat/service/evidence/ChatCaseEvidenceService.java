package com.MoleLaw_backend.chat.service.evidence;

import com.MoleLaw_backend.chat.entity.ChatCaseEvidence;
import com.MoleLaw_backend.chat.entity.ChatRoom;
import com.MoleLaw_backend.chat.repository.ChatCaseEvidenceRepository;
import com.MoleLaw_backend.chat.repository.ChatRoomRepository;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.MoleLaw_backend.law.service.tool.CaseEvidenceReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatCaseEvidenceService {
    private final ChatCaseEvidenceRepository repository;
    private final ChatRoomRepository rooms;
    private final LegalSourceService sources;
    private final CaseEvidenceReader reader;

    /** Caller has checked room ownership through ChatEvidenceService. */
    @Transactional(readOnly = true)
    public List<EvidenceItem> load(Long roomId) {
        return repository.findByRoomIdOrderByCapturedAtAscIdAsc(roomId).stream().map(record -> {
            var item = reader.read(record.getSource().getId(), record.getId());
            if (!record.getExternalId().equals(item.sourceId())) throw new IllegalArgumentException("Case identity mismatch");
            return item;
        }).toList();
    }

    /** Joins the successful turn transaction. Duplicate/conflicting IDs cannot silently replace citations. */
    @Transactional
    public void append(ChatRoom room, List<EvidenceItem> additions) {
        if (additions.isEmpty()) return;
        var owned = rooms.lockById(room.getId()).orElseThrow();
        if (!owned.getUser().getId().equals(room.getUser().getId())) throw new IllegalArgumentException("Room owner changed");
        var existing = load(room.getId());
        if (existing.size() + additions.size() > 20) throw new IllegalArgumentException("Room precedent limit exceeded");
        for (var item : additions) {
            if (!"CASE_BODY".equals(item.kind()) || item.provenance() == null
                    || !java.util.UUID.fromString(item.id()).toString().equals(item.id())
                    || !reader.read(item.provenance().sourceUuid(), item.id()).equals(item))
                throw new IllegalArgumentException("Unverified precedent addition");
            var duplicate = existing.stream().filter(value -> value.sourceId().equals(item.sourceId())).findFirst();
            if (duplicate.isPresent()) {
                if (!duplicate.get().equals(item)) throw new IllegalArgumentException("Conflicting precedent citation");
                continue;
            }
            repository.saveAndFlush(new ChatCaseEvidence(item.id(), owned, sources.restore(item.provenance().sourceUuid())));
        }
    }
}
