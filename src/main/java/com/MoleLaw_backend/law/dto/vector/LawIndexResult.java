package com.MoleLaw_backend.law.dto.vector;

/** Completed or reusable source indexing result. */
public record LawIndexResult(String referenceId, String collection, int articleCount, int chunkCount) { }
