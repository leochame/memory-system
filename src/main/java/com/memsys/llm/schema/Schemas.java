package com.memsys.llm.schema;

import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centralized JSON Schemas for OpenAI Structured Outputs (json_schema).
 * <p>
 * Important: For OpenAI, the root element must be a {@link JsonObjectSchema}.
 * For list outputs, we wrap arrays into an object: { "items": [...] }.
 */
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
                .enumValues(List.of("model_set_context", "user_insight"))
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

    public static JsonSchema conversationSummariesResult() {
        Map<String, JsonSchemaElement> itemProps = new LinkedHashMap<>();
        itemProps.put("slot_name", JsonStringSchema.builder()
                .description("Slot name for conversation summary, e.g. conversation_summary_YYYY_MM")
                .build());
        itemProps.put("content", JsonStringSchema.builder()
                .description("Summary content")
                .build());

        return JsonSchema.builder()
                .name("ConversationSummariesResult")
                .rootElement(itemsWrapper(itemProps, "List of conversation summaries"))
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

    public static JsonSchema topicsResult() {
        Map<String, JsonSchemaElement> itemProps = new LinkedHashMap<>();
        itemProps.put("slot_name", JsonStringSchema.builder()
                .description("Topic slot name, should be stable and concise")
                .build());
        itemProps.put("content", JsonStringSchema.builder()
                .description("Topic description")
                .build());

        return JsonSchema.builder()
                .name("TopicsResult")
                .rootElement(itemsWrapper(itemProps, "List of notable topics/highlights"))
                .build();
    }

    private static JsonObjectSchema itemsWrapper(Map<String, JsonSchemaElement> itemProps, String description) {
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

    /**
     * Builds a strict object schema: all fields required + additionalProperties=false.
     */
    private static JsonObjectSchema strictObject(Map<String, JsonSchemaElement> props) {
        return JsonObjectSchema.builder()
                .properties(props)
                .required(List.copyOf(props.keySet()))
                .additionalProperties(false)
                .build();
    }
}


