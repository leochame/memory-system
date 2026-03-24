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

    private String content;
    private MemoryType memoryType;
    private SourceType source;
    private int hitCount;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastAccessed;

    private String confidence;
}
