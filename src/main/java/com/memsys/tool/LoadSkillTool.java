package com.memsys.tool;

import com.memsys.llm.LlmClient;
import com.memsys.skill.SkillService;
import dev.langchain4j.agent.tool.JsonSchemaProperty;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 按需加载单个 skill 正文。
 */
@Component
public class LoadSkillTool extends BaseTool {

    private final SkillService skillService;

    public LoadSkillTool(SkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public Optional<LlmClient.ToolDefinition> build(List<String> availableSkillNames) {
        List<String> names = normalizeSkillNames(availableSkillNames);
        if (names.isEmpty()) {
            return Optional.empty();
        }

        ToolSpecification loadSkillTool = ToolSpecification.builder()
                .name("load_skill")
                .description("按名称读取单个 skill 文件正文，仅在确实需要某个 skill 详情时调用。")
                .addParameter(
                        "name",
                        JsonSchemaProperty.STRING,
                        JsonSchemaProperty.description("要加载的 skill 名称，必须来自当前会话提供的可用 skill 列表。")
                )
                .build();

        return Optional.of(new LlmClient.ToolDefinition(
                loadSkillTool,
                request -> execute(request, names)
        ));
    }

    private String execute(ToolExecutionRequest request, List<String> availableSkillNames) {
        String skillName = stringArg(request, "name");
        if (skillName.isBlank()) {
            return "load_skill 调用失败：缺少参数 name。";
        }

        if (!availableSkillNames.contains(skillName)) {
            return "未找到 skill: " + skillName + "。可用 skill: " + String.join(", ", availableSkillNames);
        }

        return skillService.readSkill(skillName)
                .map(skill -> {
                    log.info("Executed tool load_skill({})", skill.name());
                    return "# skill: " + skill.name() + "\n\n" + skill.content();
                })
                .orElse("未找到 skill 文件: " + skillName);
    }

    private List<String> normalizeSkillNames(List<String> availableSkillNames) {
        if (availableSkillNames == null || availableSkillNames.isEmpty()) {
            return List.of();
        }
        return List.copyOf(availableSkillNames);
    }
}
