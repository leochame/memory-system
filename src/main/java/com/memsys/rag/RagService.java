package com.memsys.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.memsys.llm.LlmDtos.ExampleItem;
import com.memsys.memory.MemoryScopeContext;
import com.memsys.memory.model.Memory;
import com.memsys.memory.storage.MemoryStorage;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单文件 RAG 服务：包含索引、检索、持久化与对外业务接口。
 */
@Slf4j
@Service
public class RagService {

    private static final double DEFAULT_CONTEXT_MIN_SCORE = 0.30;
    private static final String METADATA_SCOPE = "scope";

    private final MemoryStorage memoryStorage;
    private final VectorIndex vectorIndex;

    public RagService(
            MemoryStorage memoryStorage,
            @Value("${memory.base-path:.memory}") String basePath
    ) {
        this.memoryStorage = memoryStorage;
        this.vectorIndex = new VectorIndex(Paths.get(basePath));
    }

    public void cleanupLegacyDocuments() {
        List<VectorDocument> docs = vectorIndex.listDocuments();
        int removed = 0;
        for (VectorDocument doc : docs) {
            String memoryType = String.valueOf(doc.getMetadata().get("memory_type"));
            if ("MODEL_SET_CONTEXT".equals(memoryType) || "NOTABLE_HIGHLIGHTS".equals(memoryType)) {
                vectorIndex.delete(doc.getId());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} legacy vector documents", removed);
        }
    }

    public void indexMemory(String slotName, Memory memory) {
        if (slotName == null || slotName.isBlank() || memory == null) {
            return;
        }
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("slot_name", slotName);
        metadata.put("memory_type", memory.getMemoryType() == null ? "UNKNOWN" : memory.getMemoryType().toString());
        metadata.put("source", memory.getSource() == null ? "UNKNOWN" : memory.getSource().toString());
        metadata.put("created_at", String.valueOf(memory.getCreatedAt()));
        metadata.put(METADATA_SCOPE, currentScope());

        vectorIndex.upsert(scopedDocId("memory:" + slotName), memory.getContent(), metadata);
    }

    public void deleteMemory(String slotName) {
        if (slotName == null || slotName.isBlank()) {
            return;
        }
        vectorIndex.delete(scopedDocId("memory:" + slotName));
        vectorIndex.delete("memory:" + slotName);
    }

    public void indexAllMemories() {
        Map<String, Memory> all = memoryStorage.readUserInsights();
        for (Map.Entry<String, Memory> entry : all.entrySet()) {
            String slotName = entry.getKey();
            Memory memory = entry.getValue();
            if (slotName == null || slotName.isBlank() || memory == null) {
                log.warn("Skip invalid memory record while indexing. slotName={}, memoryNull={}",
                        slotName, memory == null);
                continue;
            }
            indexMemory(slotName, memory);
        }
        log.info("Indexed {} memories to vector store", all.size());
    }

    public List<RelevantMemory> searchMemories(String query, int topK, double minScore) {
        int candidateSize = Math.max(topK * 4, topK);
        List<SearchHit> hits = vectorIndex.search(query, candidateSize, minScore);
        List<RelevantMemory> results = new ArrayList<>(hits.size());
        String scope = currentScope();
        for (SearchHit hit : hits) {
            VectorDocument doc = hit.document();
            if (!docInCurrentScope(doc, scope)) {
                continue;
            }
            String slotName = (String) doc.getMetadata().getOrDefault("slot_name", doc.getId());
            results.add(new RelevantMemory(slotName, doc.getContent(), hit.score(), doc.getMetadata()));
            if (results.size() >= topK) {
                break;
            }
        }
        return results;
    }

    public List<RelevantMemory> buildSmartContext(String currentMessage, int maxMemories) {
        return searchMemories(currentMessage, maxMemories, DEFAULT_CONTEXT_MIN_SCORE);
    }

    public void indexExample(ExampleItem example) {
        String id = "example:" + System.currentTimeMillis() + "_" + example.problem().hashCode();
        String content = "Problem: " + example.problem() + "\nSolution: " + example.solution();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "example");
        metadata.put("problem", example.problem());
        metadata.put("solution", example.solution());
        metadata.put(METADATA_SCOPE, currentScope());
        if (example.tags() != null && !example.tags().isEmpty()) {
            metadata.put("tags", String.join(",", example.tags()));
        }

        vectorIndex.upsert(scopedDocId(id), content, metadata);
    }

    public List<RelevantMemory> searchExamples(String query, int topK, double minScore) {
        List<SearchHit> hits = vectorIndex.search(query, topK * 6, minScore);
        List<RelevantMemory> examples = new ArrayList<>();
        String scope = currentScope();
        for (SearchHit hit : hits) {
            VectorDocument doc = hit.document();
            if (!docInCurrentScope(doc, scope)) {
                continue;
            }
            if (!"example".equals(doc.getMetadata().get("type"))) {
                continue;
            }
            examples.add(new RelevantMemory(doc.getId(), doc.getContent(), hit.score(), doc.getMetadata()));
            if (examples.size() >= topK) {
                break;
            }
        }
        return examples;
    }

    public Map<String, Object> getStatistics() {
        String scope = currentScope();
        List<VectorDocument> docs = vectorIndex.listDocuments().stream()
                .filter(doc -> docInCurrentScope(doc, scope))
                .toList();
        long memoryCount = docs.stream().filter(doc -> rawDocId(doc).startsWith("memory:")).count();
        long conversationCount = docs.stream().filter(doc -> rawDocId(doc).startsWith("conversation:")).count();
        long exampleCount = docs.stream().filter(doc -> rawDocId(doc).startsWith("example:")).count();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_documents", docs.size());
        stats.put("memory_documents", memoryCount);
        stats.put("conversation_documents", conversationCount);
        stats.put("example_documents", exampleCount);
        return stats;
    }

    private boolean docInCurrentScope(VectorDocument doc, String scope) {
        if (doc == null) {
            return false;
        }
        String docScope = String.valueOf(doc.getMetadata().getOrDefault(METADATA_SCOPE, ""));
        if (docScope == null || docScope.isBlank()) {
            return MemoryScopeContext.DEFAULT_SCOPE.equals(scope);
        }
        return scope.equals(MemoryScopeContext.normalize(docScope));
    }

    private String currentScope() {
        return MemoryScopeContext.currentScope();
    }

    private String scopedDocId(String rawId) {
        return currentScope() + "|" + rawId;
    }

    private String rawDocId(VectorDocument doc) {
        if (doc == null || doc.getId() == null) {
            return "";
        }
        String id = doc.getId();
        int split = id.indexOf('|');
        if (split >= 0 && split < id.length() - 1) {
            return id.substring(split + 1);
        }
        return id;
    }

    public static class RelevantMemory {
        private final String slotName;
        private final String content;
        private final double score;
        private final Map<String, Object> metadata;

        public RelevantMemory(String slotName, String content, double score, Map<String, Object> metadata) {
            this.slotName = slotName;
            this.content = content;
            this.score = score;
            this.metadata = metadata;
        }

        public String getSlotName() {
            return slotName;
        }

        public String getContent() {
            return content;
        }

        public double getScore() {
            return score;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }

    private record SearchHit(VectorDocument document, double score) {
    }

    private static final class VectorIndex {

        private static final int EMBEDDING_DIM = 384;

        private final Path filePath;
        private final ObjectMapper objectMapper;
        private final EmbeddingModel embeddingModel;
        private final Map<String, VectorDocument> documents = new HashMap<>();

        private VectorIndex(Path basePath) {
            this.filePath = basePath.resolve("vector_store.json");
            this.objectMapper = new ObjectMapper();
            this.objectMapper.registerModule(new JavaTimeModule());
            this.embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            initialize(basePath);
        }

        private synchronized void upsert(String id, String content, Map<String, Object> metadata) {
            VectorDocument doc = documents.getOrDefault(id, new VectorDocument());
            doc.setId(id);
            doc.setContent(content == null ? "" : content);
            doc.setEmbedding(embed(doc.getContent()));
            doc.setMetadata(metadata == null ? new HashMap<>() : new HashMap<>(metadata));
            if (doc.getCreatedAt() == null) {
                doc.setCreatedAt(LocalDateTime.now());
            }
            doc.setLastAccessed(LocalDateTime.now());
            documents.put(id, doc);
            save();
        }

        private synchronized List<SearchHit> search(String query, int topK, double minScore) {
            if (documents.isEmpty() || query == null || query.isBlank() || topK <= 0) {
                return List.of();
            }

            float[] queryEmbedding = embed(query);
            List<SearchHit> hits = documents.values().stream()
                    .map(doc -> new SearchHit(doc, cosineSimilarity(queryEmbedding, doc.getEmbedding())))
                    .filter(hit -> hit.score() >= minScore)
                    .sorted((a, b) -> Double.compare(b.score(), a.score()))
                    .limit(topK)
                    .toList();

            if (!hits.isEmpty()) {
                for (SearchHit hit : hits) {
                    VectorDocument doc = hit.document();
                    doc.setLastAccessed(LocalDateTime.now());
                    doc.setHitCount(doc.getHitCount() + 1);
                }
                save();
            }
            return hits;
        }

        private synchronized void delete(String id) {
            if (documents.remove(id) != null) {
                save();
            }
        }

        private synchronized List<VectorDocument> listDocuments() {
            return new ArrayList<>(documents.values());
        }

        private synchronized int size() {
            return documents.size();
        }

        private void initialize(Path basePath) {
            try {
                Files.createDirectories(basePath);
                if (Files.exists(filePath)) {
                    load();
                } else {
                    save();
                }
                log.info("Vector store initialized with {} documents", documents.size());
            } catch (IOException e) {
                log.error("Failed to initialize vector store", e);
            }
        }

        private synchronized void save() {
            try {
                Path tempFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), documents);
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                log.error("Failed to save vector store", e);
            }
        }

        private synchronized void load() {
            try {
                Map<String, VectorDocument> loaded = objectMapper.readValue(
                        Files.readString(filePath),
                        objectMapper.getTypeFactory().constructMapType(
                                HashMap.class, String.class, VectorDocument.class)
                );
                documents.clear();
                documents.putAll(loaded);
            } catch (IOException e) {
                log.error("Failed to load vector store", e);
            }
        }

        private float[] embed(String text) {
            if (text == null || text.isBlank()) {
                return new float[EMBEDDING_DIM];
            }
            Embedding embedding = embeddingModel.embed(text).content();
            return embedding.vector();
        }

        private double cosineSimilarity(float[] a, float[] b) {
            if (a == null || b == null || a.length != b.length || a.length == 0) {
                return 0.0;
            }
            double dot = 0.0;
            double normA = 0.0;
            double normB = 0.0;
            for (int i = 0; i < a.length; i++) {
                dot += a[i] * b[i];
                normA += a[i] * a[i];
                normB += b[i] * b[i];
            }
            if (normA == 0.0 || normB == 0.0) {
                return 0.0;
            }
            return dot / (Math.sqrt(normA) * Math.sqrt(normB));
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class VectorDocument {
        private String id;
        private String content;
        private float[] embedding;
        private Map<String, Object> metadata;
        private LocalDateTime createdAt;
        private LocalDateTime lastAccessed;
        private int hitCount;
    }
}
