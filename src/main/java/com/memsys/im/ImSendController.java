package com.memsys.im;

import com.memsys.im.model.ActiveSendRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主动发消息接口。
 */
@Slf4j
@RestController
@RequestMapping("/im")
@ConditionalOnProperty(name = "im.api.enabled", havingValue = "true", matchIfMissing = true)
public class ImSendController {

    private final ImRuntimeService imRuntimeService;

    public ImSendController(ImRuntimeService imRuntimeService) {
        this.imRuntimeService = imRuntimeService;
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody ActiveSendRequest request) {
        String platform = request == null ? "" : trim(request.platform());
        String conversationId = request == null ? "" : trim(request.conversationId());
        String text = request == null ? "" : trim(request.text());

        if (platform.isBlank() || conversationId.isBlank() || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "ok", false,
                    "error", "platform/conversationId/text 不能为空"
            ));
        }

        try {
            imRuntimeService.sendText(platform, conversationId, text);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("platform", platform);
            result.put("conversation_id", conversationId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("主动发送失败. platform={}, conversationId={}", platform, conversationId, e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "ok", false,
                    "error", trim(e.getMessage()).isBlank() ? "主动发送失败" : trim(e.getMessage())
            ));
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
