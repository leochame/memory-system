package com.memsys.task;

import com.memsys.task.model.TaskDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * 管理 .memory/tasks/ 目录下的 .yaml 任务定义文件。
 * <p>
 * 设计风格仿照 {@link com.memsys.skill.SkillService}：
 * 每个任务定义是一个独立的 .yaml 文件，文件名即任务名。
 */
@Slf4j
@Service
public class TaskDefinitionService {

    private final Path tasksDir;

    public TaskDefinitionService(@Value("${memory.base-path:.memory}") String basePath) {
        this.tasksDir = Paths.get(basePath).resolve("tasks");
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException e) {
            log.error("Failed to create tasks directory: {}", tasksDir, e);
        }
    }

    // ── 仅供测试使用 ──

    TaskDefinitionService(Path tasksDir) {
        this.tasksDir = tasksDir;
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException e) {
            log.error("Failed to create tasks directory: {}", tasksDir, e);
        }
    }

    /**
     * 列出所有任务定义（完整加载）。
     */
    public List<TaskDefinition> listTaskDefinitions() {
        List<TaskDefinition> definitions = new ArrayList<>();
        try (Stream<Path> files = Files.list(tasksDir)) {
            files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                 .sorted()
                 .forEach(p -> {
                     try {
                         TaskDefinition def = readYaml(p);
                         if (def != null) {
                             String name = stripExtension(p.getFileName().toString());
                             def.setName(name);
                             definitions.add(def);
                         }
                     } catch (Exception e) {
                         log.warn("Failed to read task definition file: {}", p, e);
                     }
                 });
        } catch (IOException e) {
            log.warn("Failed to list tasks directory", e);
        }
        return definitions;
    }

    /**
     * 列出所有任务名称。
     */
    public List<String> listTaskDefinitionNames() {
        try (Stream<Path> files = Files.list(tasksDir)) {
            return files.filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                        .sorted()
                        .map(p -> stripExtension(p.getFileName().toString()))
                        .toList();
        } catch (IOException e) {
            log.warn("Failed to list task definition names", e);
            return List.of();
        }
    }

    /**
     * 读取指定名称的任务定义。
     */
    public Optional<TaskDefinition> readTaskDefinition(String name) {
        Path path = resolveTaskFile(name);
        if (path == null || !Files.exists(path)) {
            return Optional.empty();
        }
        try {
            TaskDefinition def = readYaml(path);
            if (def != null) {
                def.setName(name);
            }
            return Optional.ofNullable(def);
        } catch (Exception e) {
            log.warn("Failed to read task definition: {}", name, e);
            return Optional.empty();
        }
    }

    /**
     * 保存任务定义到文件。
     */
    public void saveTaskDefinition(TaskDefinition definition) {
        if (definition == null || definition.getName() == null || definition.getName().isBlank()) {
            log.warn("Cannot save task definition with empty name");
            return;
        }
        String name = sanitizeName(definition.getName());
        Path path = tasksDir.resolve(name + ".yaml");
        try {
            if (definition.getCreatedAt() == null) {
                definition.setCreatedAt(LocalDateTime.now());
            }
            String yamlContent = toYaml(definition);
            Path temp = tasksDir.resolve(name + ".yaml.tmp");
            Files.writeString(temp, yamlContent);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Saved task definition: {}", name);
        } catch (IOException e) {
            log.error("Failed to save task definition: {}", name, e);
        }
    }

    /**
     * 删除指定名称的任务定义。
     */
    public boolean deleteTaskDefinition(String name) {
        Path path = resolveTaskFile(name);
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete task definition: {}", name, e);
            return false;
        }
    }

    /**
     * 更新任务的 lastTriggeredAt 字段并持久化。
     */
    public void updateLastTriggeredAt(String name, LocalDateTime triggeredAt) {
        readTaskDefinition(name).ifPresent(def -> {
            def.setLastTriggeredAt(triggeredAt);
            saveTaskDefinition(def);
        });
    }

    /**
     * 禁用任务（用于一次性任务触发后自动关闭）。
     */
    public void disableTaskDefinition(String name) {
        readTaskDefinition(name).ifPresent(def -> {
            def.setEnabled(false);
            saveTaskDefinition(def);
        });
    }

    // ── 内部方法 ──

    private Path resolveTaskFile(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String sanitized = sanitizeName(name);
        Path yamlPath = tasksDir.resolve(sanitized + ".yaml");
        if (Files.exists(yamlPath)) {
            return yamlPath;
        }
        Path ymlPath = tasksDir.resolve(sanitized + ".yml");
        if (Files.exists(ymlPath)) {
            return ymlPath;
        }
        return yamlPath; // 默认返回 .yaml 路径
    }

    private TaskDefinition readYaml(Path path) throws IOException {
        String content = Files.readString(path);
        if (content == null || content.isBlank()) {
            return null;
        }
        Yaml yaml = createYaml();
        return yaml.loadAs(content, TaskDefinition.class);
    }

    private String toYaml(TaskDefinition definition) {
        // 转为 LinkedHashMap 以控制输出顺序
        Map<String, Object> map = new LinkedHashMap<>();
        putIfNotNull(map, "type", definition.getType());
        putIfNotNull(map, "title", definition.getTitle());
        putIfNotNull(map, "detail", definition.getDetail());
        putIfNotNull(map, "cron", definition.getCron());
        putIfNotNull(map, "once", definition.getOnce());
        putIfNotNull(map, "executeCommand", definition.getExecuteCommand());
        putIfNotNull(map, "executeTimeoutSeconds", definition.getExecuteTimeoutSeconds());
        map.put("enabled", definition.isEnabled());
        putIfNotNull(map, "notifyPlatform", definition.getNotifyPlatform());
        putIfNotNull(map, "notifyConversationId", definition.getNotifyConversationId());
        putIfNotNull(map, "notifySenderId", definition.getNotifySenderId());
        putIfNotNull(map, "createdAt", definition.getCreatedAt() != null ? definition.getCreatedAt().toString() : null);
        putIfNotNull(map, "lastTriggeredAt", definition.getLastTriggeredAt() != null ? definition.getLastTriggeredAt().toString() : null);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        Yaml yaml = new Yaml(options);
        return yaml.dump(map);
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            String strValue = String.valueOf(value);
            if (!strValue.isBlank()) {
                map.put(key, value);
            }
        }
    }

    private Yaml createYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        Representer representer = new Representer(new DumperOptions());
        representer.getPropertyUtils().setSkipMissingProperties(true);
        return new Yaml(new Constructor(TaskDefinition.class, loaderOptions), representer);
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String sanitizeName(String name) {
        // 只保留字母、数字、中文、连字符、下划线
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff\\-_]", "_").trim();
    }
}
