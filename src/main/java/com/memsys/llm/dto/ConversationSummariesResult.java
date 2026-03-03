package com.memsys.llm.dto;

import java.util.List;

/**
 * OpenAI structured output requires the JSON Schema root to be an object.
 * We wrap list outputs in an object with an "items" array.
 */
public record ConversationSummariesResult(
        List<ConversationSummaryItem> items
) {
}


