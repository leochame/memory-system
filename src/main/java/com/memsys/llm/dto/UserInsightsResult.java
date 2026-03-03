package com.memsys.llm.dto;

import java.util.List;

public record UserInsightsResult(
        List<UserInsightItem> items
) {
}


