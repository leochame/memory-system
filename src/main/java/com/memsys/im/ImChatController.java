package com.memsys.im;

import com.memsys.cli.ConversationProgressEvent;
import com.memsys.im.model.ImChatRequest;
import com.memsys.im.model.ImConversationResult;
import com.memsys.im.model.IncomingImMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * IM 对话 API：
 * 1) 普通 JSON 模式
 * 2) SSE 流式过程输出
 */
@Slf4j
@RestController
@RequestMapping("/im/chat")
@ConditionalOnProperty(name = "im.api.enabled", havingValue = "true")
public class ImChatController {

    private final ImRuntimeService imRuntimeService;

    public ImChatController(ImRuntimeService imRuntimeService) {
        this.imRuntimeService = imRuntimeService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ImChatRequest request) {
        ValidationResult validation = validate(request);
        if (!validation.ok()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", validation.message()
            ));
        }

        List<ConversationProgressEvent> processSteps = new ArrayList<>();
        ImConversationResult result = imRuntimeService.processIncoming(
                toIncomingMessage(request),
                processSteps::add,
                request.temporary()
        );

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("reply", result.reply());
        body.put("duration_ms", result.durationMs());
        body.put("process_collapsed", true);
        body.put("process_steps", toStepPayloads(processSteps));
        body.put("process_preview", processPreview(processSteps));
        return ResponseEntity.ok(body);
    }

    @PostMapping(path = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ImChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        ValidationResult validation = validate(request);
        if (!validation.ok()) {
            safeSend(emitter, "error", Map.of(
                    "ok", false,
                    "error", validation.message()
            ));
            emitter.complete();
            return emitter;
        }

        CompletableFuture.runAsync(() -> {
            List<ConversationProgressEvent> processSteps = new ArrayList<>();
            try {
                ImConversationResult result = imRuntimeService.processIncoming(
                        toIncomingMessage(request),
                        event -> {
                            processSteps.add(event);
                            safeSend(emitter, "process", toStepPayload(event));
                        },
                        request.temporary()
                );

                Map<String, Object> finalPayload = new LinkedHashMap<>();
                finalPayload.put("ok", true);
                finalPayload.put("reply", result.reply());
                finalPayload.put("duration_ms", result.durationMs());
                finalPayload.put("process_collapsed", true);
                finalPayload.put("process_steps", toStepPayloads(processSteps));
                finalPayload.put("process_preview", processPreview(processSteps));
                safeSend(emitter, "final", finalPayload);
                safeSend(emitter, "done", Map.of("ok", true));
                emitter.complete();
            } catch (Throwable e) {
                log.warn("SSE chat processing failed", e);
                safeSend(emitter, "error", Map.of(
                        "ok", false,
                        "error", trim(e.getMessage()).isBlank() ? "chat stream failed" : trim(e.getMessage())
                ));
                emitter.complete();
            }
        });

        return emitter;
    }

    private ValidationResult validate(ImChatRequest request) {
        if (request == null) {
            return new ValidationResult(false, "request 不能为空");
        }
        if (request.platform().isBlank() || request.conversationId().isBlank()
                || request.senderId().isBlank() || request.text().isBlank()) {
            return new ValidationResult(false, "platform/conversationId/senderId/text 不能为空");
        }
        return new ValidationResult(true, "");
    }

    private IncomingImMessage toIncomingMessage(ImChatRequest request) {
        return new IncomingImMessage(
                request.platform(),
                request.conversationId(),
                request.senderId(),
                request.text(),
                "",
                System.currentTimeMillis()
        );
    }

    private List<Map<String, Object>> toStepPayloads(List<ConversationProgressEvent> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream().map(this::toStepPayload).toList();
    }

    private Map<String, Object> toStepPayload(ConversationProgressEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", event == null ? "" : event.stage());
        payload.put("message", event == null ? "" : event.message());
        payload.put("timestamp_ms", event == null ? 0L : event.timestampMs());
        payload.put("details", event == null ? Map.of() : event.details());
        return payload;
    }

    private List<String> processPreview(List<ConversationProgressEvent> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }
        return steps.stream()
                .map(step -> step == null ? "" : trim(step.message()))
                .filter(s -> !s.isBlank())
                .limit(3)
                .toList();
    }

    private void safeSend(SseEmitter emitter, String event, Object body) {
        try {
            emitter.send(SseEmitter.event().name(event).data(body));
        } catch (IOException e) {
            log.debug("Failed to send SSE event: {}", event, e);
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private record ValidationResult(boolean ok, String message) {
    }
}
