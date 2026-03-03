package com.memsys.llm.dto;

public record UserInsightItem(
        String slot_name,
        String content,
        String confidence
) {
}


