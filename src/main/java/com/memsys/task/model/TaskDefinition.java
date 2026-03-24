package com.memsys.task.model;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件形式的定时任务定义。
 * <p>
 * 存储在 .memory/tasks/ 目录下，每个 .yaml 文件对应一个任务定义。
 * 支持两种调度模式：
 * <ul>
 *   <li>cron — 周期执行（如 "0 9 * * 1-5" 表示工作日每天 9:00）</li>
 *   <li>once — 一次性执行（ISO-8601 时间，触发后自动标记 enabled=false）</li>
 * </ul>
 * 触发后的行为由 type 决定：
 * <ul>
 *   <li>reminder — 仅通过 IM 推送提醒消息</li>
 *   <li>script — 执行 executeCommand 并将结果通过 IM 推送</li>
 * </ul>
 */
@Data
public class TaskDefinition {

    /** 任务名称，即文件名（不含扩展名） */
    private String name;

    /** 任务类型：reminder（纯提醒）| script（执行脚本） */
    private String type = "reminder";

    /** 任务标题，用于提醒消息 */
    private String title;

    /** 任务详情描述 */
    private String detail;

    /** Cron 表达式（5 位，标准 Unix cron），用于周期任务 */
    private String cron;

    /** 一次性执行时间（ISO-8601 LocalDateTime），触发后自动 disable */
    private String once;

    /** 到期后执行的 shell 命令（仅 type=script 时生效） */
    private String executeCommand;

    /** 命令执行超时秒数（默认使用系统配置） */
    private Integer executeTimeoutSeconds;

    /** 是否启用 */
    private boolean enabled = true;

    /** 通知目标平台（telegram/feishu），为空则使用 CLI 输出 */
    private String notifyPlatform;

    /** 通知目标会话 ID */
    private String notifyConversationId;

    /** 通知目标发送者 ID */
    private String notifySenderId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 上次触发时间 */
    private LocalDateTime lastTriggeredAt;

    // ── 常量 ──

    public static final String TYPE_REMINDER = "reminder";
    public static final String TYPE_SCRIPT = "script";
}
