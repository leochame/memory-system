package com.memsys.task;

import com.memsys.task.model.TaskDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TaskDefinitionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void readTaskDefinitionShouldReturnCanonicalSanitizedName() {
        TaskDefinitionService service = new TaskDefinitionService(tempDir.resolve("tasks"));

        TaskDefinition definition = new TaskDefinition();
        definition.setName("daily/plan");
        definition.setTitle("每日计划");
        service.saveTaskDefinition(definition);

        Optional<TaskDefinition> loaded = service.readTaskDefinition("daily/plan");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getName()).isEqualTo("daily_plan");
        assertThat(service.listTaskDefinitionNames()).contains("daily_plan");
    }

    @Test
    void saveTaskDefinitionShouldNormalizeNameOnInputObject() {
        TaskDefinitionService service = new TaskDefinitionService(tempDir.resolve("tasks"));

        TaskDefinition definition = new TaskDefinition();
        definition.setName("weekly:review");
        definition.setTitle("周复盘");
        service.saveTaskDefinition(definition);

        assertThat(definition.getName()).isEqualTo("weekly_review");
    }

    @Test
    void readTaskDefinitionShouldKeepDefaultTypeWhenYamlOmitsType() throws Exception {
        Path tasksDir = tempDir.resolve("tasks");
        TaskDefinitionService service = new TaskDefinitionService(tasksDir);
        Files.writeString(tasksDir.resolve("no_type.yaml"), """
                title: 无类型任务
                enabled: true
                """);

        Optional<TaskDefinition> loaded = service.readTaskDefinition("no_type");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getType()).isEqualTo(TaskDefinition.TYPE_REMINDER);
    }

    @Test
    void readTaskDefinitionShouldRejectDuplicateYamlKeys() throws Exception {
        Path tasksDir = tempDir.resolve("tasks");
        TaskDefinitionService service = new TaskDefinitionService(tasksDir);
        Files.writeString(tasksDir.resolve("dup.yaml"), """
                title: first
                title: second
                enabled: true
                """);

        Optional<TaskDefinition> loaded = service.readTaskDefinition("dup");
        assertThat(loaded).isEmpty();
    }

    @Test
    void saveTaskDefinitionShouldRejectBlankName() {
        Path tasksDir = tempDir.resolve("tasks");
        TaskDefinitionService service = new TaskDefinitionService(tasksDir);

        TaskDefinition definition = new TaskDefinition();
        definition.setName("   ");
        definition.setTitle("invalid");
        service.saveTaskDefinition(definition);

        assertThat(service.listTaskDefinitionNames()).isEmpty();
        assertThat(service.readTaskDefinition("   ")).isEmpty();
        assertThat(service.deleteTaskDefinition("   ")).isFalse();
    }
}
