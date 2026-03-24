package com.memsys.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
@Service
public class AgentGuideService {

    private final Path agentGuidePath;
    private final Path localGuidePath;
    private final String cachedGuide;

    public AgentGuideService(
            @Value("${agent.guide-path:Agent.md}") String agentGuidePath,
            @Value("${memory.base-path:.memory}") String basePath
    ) {
        this.agentGuidePath = Paths.get(agentGuidePath);
        this.localGuidePath = Paths.get(basePath).resolve("Agent.local.md");
        this.cachedGuide = loadGuide();
    }

    public String getCachedGuide() {
        return cachedGuide;
    }

    public boolean isLoaded() {
        return !cachedGuide.isBlank();
    }

    private String loadGuide() {
        String globalGuide = readFile(agentGuidePath);
        String localGuide = readFile(localGuidePath);

        StringBuilder guide = new StringBuilder();
        if (!globalGuide.isBlank()) {
            guide.append(globalGuide.trim());
        }
        if (!localGuide.isBlank()) {
            if (guide.length() > 0) {
                guide.append("\n\n");
            }
            guide.append("## Local Overrides\n");
            guide.append(localGuide.trim());
        }

        if (guide.length() == 0) {
            log.warn("Agent guide not found: {} (optional local override: {})", agentGuidePath, localGuidePath);
            return "";
        }

        log.debug("Startup map loaded.");
        return guide.toString();
    }

    private String readFile(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path);
        } catch (IOException e) {
            log.warn("Failed to read agent guide file: {}", path, e);
            return "";
        }
    }
}
