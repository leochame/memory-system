package com.memsys.llm.schema;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Schemas {

    private Schemas() {
    }

    public static JsonSchema explicitMemoryResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("has_memory", JsonBooleanSchema.builder()
                .description("Whether the user message contains explicit memory worth saving")
                .build());
        props.put("slot_name", JsonStringSchema.builder()
                .description("Stable slot identifier, e.g. diet_preference")
                .build());
        props.put("content", JsonStringSchema.builder()
                .description("Memory content to store")
                .build());
        props.put("memory_type", JsonEnumSchema.builder()
                .description("Memory type")
                .enumValues(List.of("user_insight"))
                .build());
        props.put("source", JsonEnumSchema.builder()
                .description("Memory source")
                .enumValues(List.of("explicit"))
                .build());

        return JsonSchema.builder()
                .name("ExplicitMemoryResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema userInsightsResult() {
        Map<String, JsonSchemaElement> itemProps = new LinkedHashMap<>();
        itemProps.put("slot_name", JsonStringSchema.builder()
                .description("Slot name for user profile attribute, e.g. home_city")
                .build());
        itemProps.put("content", JsonStringSchema.builder()
                .description("User insight content")
                .build());
        itemProps.put("confidence", JsonEnumSchema.builder()
                .description("Confidence level")
                .enumValues(List.of("low", "medium", "high"))
                .build());

        return JsonSchema.builder()
                .name("UserInsightsResult")
                .rootElement(itemsWrapper(itemProps, "List of extracted user insights"))
                .build();
    }

    public static JsonSchema skillGenerationResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("should_generate", JsonBooleanSchema.builder()
                .description("Whether a reusable skill/methodology was identified in the conversation")
                .build());
        props.put("skill_name", JsonStringSchema.builder()
                .description("Short snake_case name for the skill, e.g. code_review_checklist")
                .build());
        props.put("skill_content", JsonStringSchema.builder()
                .description("Markdown content describing the skill/methodology")
                .build());

        return JsonSchema.builder()
                .name("SkillGenerationResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema examplesResult() {
        Map<String, JsonSchemaElement> tagSchema = new LinkedHashMap<>();
        // tags is an array of strings inside each item

        Map<String, JsonSchemaElement> itemProps = new LinkedHashMap<>();
        itemProps.put("problem", JsonStringSchema.builder()
                .description("The problem or question that was addressed")
                .build());
        itemProps.put("solution", JsonStringSchema.builder()
                .description("The solution or approach that was used")
                .build());
        itemProps.put("tags", JsonArraySchema.builder()
                .description("Tags categorizing this example")
                .items(JsonStringSchema.builder().description("tag").build())
                .build());

        return JsonSchema.builder()
                .name("ExamplesResult")
                .rootElement(itemsWrapper(itemProps, "List of problem/solution examples"))
                .build();
    }

    public static JsonSchema scheduledTaskResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("has_task", JsonBooleanSchema.builder()
                .description("Whether user message should create a scheduled task")
                .build());
        props.put("task_title", JsonStringSchema.builder()
                .description("Short task title")
                .build());
        props.put("task_detail", JsonStringSchema.builder()
                .description("Task detail, can be empty")
                .build());
        props.put("due_at_iso", JsonStringSchema.builder()
                .description("Absolute due datetime in local timezone, ISO-8601 format, e.g. 2026-03-21T09:00:00")
                .build());
        props.put("recurrence_type", JsonEnumSchema.builder()
                .description("Task recurrence type")
                .enumValues(List.of("none", "daily", "weekly"))
                .build());
        props.put("recurrence_interval", JsonIntegerSchema.builder()
                .description("Recurrence interval. Use 0 when recurrence_type is none; otherwise >=1")
                .build());

        return JsonSchema.builder()
                .name("ScheduledTaskResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema memoryReflectionResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("needs_memory", JsonBooleanSchema.builder()
                .description("Whether this user message requires long-term memory to answer well")
                .build());
        props.put("reason", JsonStringSchema.builder()
                .description("Brief explanation of why memory is or is not needed for this message")
                .build());
        props.put("evidence_purposes", JsonArraySchema.builder()
                .description("List of evidence purposes if memory is needed: personalization, continuity, constraint, experience, followup")
                .items(JsonStringSchema.builder().description("evidence purpose").build())
                .build());

        return JsonSchema.builder()
                .name("MemoryReflectionResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema conversationSummaryResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("summary", JsonStringSchema.builder()
                .description("A concise summary of the conversation, covering main topics, decisions, and outcomes")
                .build());
        props.put("key_topics", JsonArraySchema.builder()
                .description("List of key topics discussed in this conversation segment")
                .items(JsonStringSchema.builder().description("topic keyword or phrase").build())
                .build());
        props.put("turn_count", JsonIntegerSchema.builder()
                .description("Number of conversation turns summarized")
                .build());
        props.put("time_range", JsonStringSchema.builder()
                .description("Time range of the conversation, e.g. '2026-03-24 20:00 ~ 21:30'")
                .build());

        return JsonSchema.builder()
                .name("ConversationSummaryResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema topicShiftDetectionResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("topic_shifted", JsonBooleanSchema.builder()
                .description("Whether the current message indicates a significant topic shift from the recent conversation context")
                .build());
        props.put("previous_topic", JsonStringSchema.builder()
                .description("A short phrase describing the previous conversation topic (empty if no shift)")
                .build());
        props.put("current_topic", JsonStringSchema.builder()
                .description("A short phrase describing the new topic the user is shifting to (empty if no shift)")
                .build());

        return JsonSchema.builder()
                .name("TopicShiftDetectionResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema proactiveReminderResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("should_remind", JsonBooleanSchema.builder()
                .description("Whether there is something meaningful to proactively remind the user about based on their profile and recent history")
                .build());
        props.put("reminder_text", JsonStringSchema.builder()
                .description("The reminder message text in Chinese, friendly and concise (2-4 sentences). Empty if should_remind=false")
                .build());
        props.put("reminder_type", JsonEnumSchema.builder()
                .description("Type of reminder")
                .enumValues(List.of("review", "suggestion", "follow_up", "insight", "none"))
                .build());
        props.put("based_on_memories", JsonArraySchema.builder()
                .description("List of memory slot names or topics this reminder is based on")
                .items(JsonStringSchema.builder().description("memory reference").build())
                .build());
        props.put("suggested_action", JsonStringSchema.builder()
                .description("A suggested next action for the user (empty if not applicable)")
                .build());

        return JsonSchema.builder()
                .name("ProactiveReminderResult")
                .rootElement(strictObject(props))
                .build();
    }

    public static JsonSchema evalScoreResult() {
        Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
        props.put("relevance", JsonIntegerSchema.builder()
                .description("Relevance score from 1 to 10")
                .build());
        props.put("personalization", JsonIntegerSchema.builder()
                .description("Personalization score from 1 to 10")
                .build());
        props.put("accuracy", JsonIntegerSchema.builder()
                .description("Accuracy score from 1 to 10")
                .build());
        props.put("helpfulness", JsonIntegerSchema.builder()
                .description("Helpfulness score from 1 to 10")
                .build());
        props.put("justification", JsonStringSchema.builder()
                .description("Short reason explaining the score")
                .build());

        return JsonSchema.builder()
                .name("EvalScoreResult")
                .rootElement(strictObject(props))
                .build();
    }

    // ========== 工具方法 ==========

    static JsonObjectSchema itemsWrapper(Map<String, JsonSchemaElement> itemProps, String description) {
        JsonObjectSchema itemSchema = strictObject(itemProps);
        JsonArraySchema arraySchema = JsonArraySchema.builder()
                .description(description)
                .items(itemSchema)
                .build();

        Map<String, JsonSchemaElement> wrapperProps = new LinkedHashMap<>();
        wrapperProps.put("items", arraySchema);

        return JsonObjectSchema.builder()
                .description("Wrapper object")
                .properties(wrapperProps)
                .required(List.of("items"))
                .additionalProperties(false)
                .build();
    }

    static JsonObjectSchema strictObject(Map<String, JsonSchemaElement> props) {
        return JsonObjectSchema.builder()
                .properties(props)
                .required(List.copyOf(props.keySet()))
                .additionalProperties(false)
                .build();
    }
}
