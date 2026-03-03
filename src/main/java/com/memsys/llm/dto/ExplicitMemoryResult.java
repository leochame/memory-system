package com.memsys.llm.dto;

/**
 * Structured output for explicit memory extraction.
 * <p>
 * Note: We keep field names snake_case to match the JSON schema and avoid extra Jackson annotations.
 */
public record ExplicitMemoryResult(
        boolean has_memory,
        String slot_name,
        String content,
        String memory_type,
        String source
) {
}


