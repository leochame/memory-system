package com.memsys.task.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ScheduledTask {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_TRIGGERED = "triggered";
    public static final String STATUS_CANCELLED = "cancelled";

    private String id;
    private String title;
    private String detail;
    private LocalDateTime dueAt;
    private LocalDateTime createdAt;
    private LocalDateTime triggeredAt;
    private LocalDateTime executedAt;
    private String status;
    private String executeCommand;
    private Integer executeTimeoutSeconds;
    private String executionStatus;
    private Integer executionExitCode;
    private String executionOutput;
    private String sourceMessage;
    private String sourcePlatform;
    private String sourceConversationId;
    private String sourceSenderId;
}
