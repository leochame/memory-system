package com.memsys.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Slf4j
@Service
public class SkillService {

    private final Path skillsDir;

    public SkillService(@Value("${memory.base-path:.memory}") String basePath) {
        this.skillsDir = Paths.get(basePath).resolve("skills");
        try {
            Files.createDirectories(skillsDir);
        } catch (IOException e) {
            log.error("Failed to create skills directory: {}", skillsDir, e);
        }
    }

    public List<SkillFile> listSkills() {
        List<SkillFile> skills = new ArrayList<>();
        try (Stream<Path> files = Files.list(skillsDir)) {
            files.filter(p -> p.toString().endsWith(".md"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         String name = p.getFileName().toString().replace(".md", "");
                         String content = Files.readString(p);
                         skills.add(new SkillFile(name, p, content));
                     } catch (IOException e) {
                         log.warn("Failed to read skill file: {}", p, e);
                     }
                 });
        } catch (IOException e) {
            log.warn("Failed to list skills directory", e);
        }
        return skills;
    }

    public List<String> listSkillNames() {
        try (Stream<Path> files = Files.list(skillsDir)) {
            return files.filter(path -> path.toString().endsWith(".md"))
                    .sorted()
                    .map(path -> path.getFileName().toString().replace(".md", ""))
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list skill names", e);
            return List.of();
        }
    }

    public Optional<SkillFile> readSkill(String name) {
        Path path = resolveSkillPath(name);
        if (path == null) {
            return Optional.empty();
        }
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(path);
            return Optional.of(new SkillFile(name, path, content));
        } catch (IOException e) {
            log.warn("Failed to read skill: {}", name, e);
            return Optional.empty();
        }
    }

    public void saveSkill(String name, String markdownContent) {
        Path path = resolveSkillPath(name);
        if (path == null) {
            log.warn("Failed to save skill: invalid name '{}'", name);
            return;
        }
        try {
            Path temp = skillsDir.resolve(path.getFileName().toString() + ".tmp");
            Files.writeString(temp, markdownContent);
            moveWithAtomicFallback(temp, path);
            log.info("Saved skill: {}", name);
        } catch (IOException e) {
            log.error("Failed to save skill: {}", name, e);
        }
    }

    public boolean deleteSkill(String name) {
        Path path = resolveSkillPath(name);
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete skill: {}", name, e);
            return false;
        }
    }

    private Path resolveSkillPath(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim();
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")) {
            return null;
        }
        Path path = skillsDir.resolve(normalized + ".md").normalize();
        if (!path.startsWith(skillsDir)) {
            return null;
        }
        return path;
    }

    private void moveWithAtomicFallback(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public record SkillFile(String name, Path path, String content) {
    }
}
