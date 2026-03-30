package com.memsys.task;

import com.memsys.task.model.TaskDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

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

    @Autowired
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
                def.setName(stripExtension(path.getFileName().toString()));
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
        if (name.isBlank()) {
            log.warn("Cannot save task definition: sanitized name is empty. original={}", definition.getName());
            return;
        }
        Path resolved = resolveTaskFile(name);
        Path path = (resolved != null && Files.exists(resolved))
                ? resolved
                : tasksDir.resolve(name + ".yaml");
        try {
            if (definition.getCreatedAt() == null) {
                definition.setCreatedAt(LocalDateTime.now());
            }
            definition.setName(name);
            String yamlContent = toYaml(definition);
            Path temp = newTempFile(name + ".yaml");
            Files.writeString(temp, yamlContent);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
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
        if (sanitized.isBlank()) {
            return null;
        }
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
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(loaderOptions));
        Object loaded = yaml.load(content);
        if (!(loaded instanceof Map<?, ?> map)) {
            return null;
        }

        TaskDefinition definition = new TaskDefinition();
        String type = asString(map.get("type"));
        if (type != null) {
            definition.setType(type);
        }
        definition.setTitle(asString(map.get("title")));
        definition.setDetail(asString(map.get("detail")));
        definition.setCron(asString(map.get("cron")));
        definition.setOnce(asString(map.get("once")));
        definition.setExecuteCommand(asString(map.get("executeCommand")));
        definition.setExecuteTimeoutSeconds(asInteger(map.get("executeTimeoutSeconds")));
        Boolean enabled = asBoolean(map.get("enabled"));
        if (enabled != null) {
            definition.setEnabled(enabled);
        }
        definition.setNotifyPlatform(asString(map.get("notifyPlatform")));
        definition.setNotifyConversationId(asString(map.get("notifyConversationId")));
        definition.setNotifySenderId(asString(map.get("notifySenderId")));
        definition.setCreatedAt(asLocalDateTime(map.get("createdAt")));
        definition.setLastTriggeredAt(asLocalDateTime(map.get("lastTriggeredAt")));
        return definition;
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

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String sanitizeName(String name) {
        // 只保留字母、数字、中文、连字符、下划线
        return name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fff\\-_]", "_").trim();
    }

    private Path newTempFile(String prefix) {
        return tasksDir.resolve(prefix + "." + UUID.randomUUID() + ".tmp");
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        return null;
    }

    private LocalDateTime asLocalDateTime(Object value) {
        String text = asString(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text);
        } catch (Exception ignored) {
            return null;
        }
    }
}
