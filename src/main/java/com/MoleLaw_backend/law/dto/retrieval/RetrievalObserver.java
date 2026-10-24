package com.MoleLaw_backend.law.dto.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import java.util.List;

/** Request-local observable work, never model reasoning or candidate bodies. */
public interface RetrievalObserver {
    RetrievalObserver NONE = new RetrievalObserver() {};
    default String traceId() { return null; }
    default void progress(String stage) {}
    default void evidence(List<EvidenceItem> confirmed) {}
}
