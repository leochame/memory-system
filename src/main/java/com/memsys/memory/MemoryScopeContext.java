package com.memsys.memory;

import java.util.Locale;

/**
 * 记忆作用域上下文。
 * <p>
 * 支持将读写隔离到不同命名空间：
 * - personal:{unifiedId}
 * - team:{teamId}
 */
public final class MemoryScopeContext {

    public static final String DEFAULT_SCOPE = "personal:user_default";
    private static final ThreadLocal<String> CURRENT_SCOPE =
            ThreadLocal.withInitial(() -> DEFAULT_SCOPE);

    private MemoryScopeContext() {
    }

    public static String currentScope() {
        return normalize(CURRENT_SCOPE.get());
    }

    public static Scope useScope(String scope) {
        String previous = currentScope();
        CURRENT_SCOPE.set(normalize(scope));
        return () -> CURRENT_SCOPE.set(previous);
    }

    public static String personalScope(String unifiedId) {
        String id = normalizeId(unifiedId, "user_default");
        return "personal:" + id;
    }

    public static String teamScope(String teamId) {
        String id = normalizeId(teamId, "default");
        return "team:" + id;
    }

    public static String normalize(String scope) {
        String raw = scope == null ? "" : scope.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            return DEFAULT_SCOPE;
        }
        if (raw.startsWith("personal:") || raw.startsWith("team:")) {
            return raw;
        }
        return "personal:" + normalizeId(raw, "user_default");
    }

    private static String normalizeId(String id, String fallback) {
        String raw = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (raw.isBlank()) {
            return fallback;
        }
        return raw.replaceAll("[^a-z0-9._-]", "_");
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
