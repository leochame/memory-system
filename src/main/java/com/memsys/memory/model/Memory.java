package com.memsys.memory.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Memory {

    public enum MemoryType {
        USER_INSIGHT
    }

    public enum SourceType {
        EXPLICIT,
        IMPLICIT
    }

    /**
     * 记忆治理状态 — Phase 9 核心字段。
     * <ul>
     *   <li>ACTIVE — 已确认生效的记忆</li>
     *   <li>PENDING — 待审核（如隐式提取的低置信度记忆）</li>
     *   <li>CONFLICT — 与已有记忆冲突，等待用户裁决</li>
     *   <li>ARCHIVED — 已归档，不再参与 prompt 注入但保留历史</li>
     * </ul>
     */
    public enum MemoryStatus {
        ACTIVE,
        PENDING,
        CONFLICT,
        ARCHIVED
    }

    private String content;
    private MemoryType memoryType;
    private SourceType source;
    private int hitCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastAccessed;

    private String confidence;

    /** 治理状态：ACTIVE / PENDING / CONFLICT / ARCHIVED（默认 ACTIVE） */
    private MemoryStatus status;

    /** 验证时间：记忆被用户确认或系统自动合并的时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime verifiedAt;

    /** 验证来源：如 user_confirmed / auto_merged / implicit_inferred */
    private String verifiedSource;
}
