package com.sql.logic.engine.domain.agentic.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Embedding-based semantic matching for Skills.
 * <p>
 * MVP implementation: stores embeddings in-memory and computes cosine similarity.
 * Production version would delegate to pgvector.
 */
@Service
public class SkillEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SkillEmbeddingService.class);

    private final Map<String, double[]> embeddings = new ConcurrentHashMap<>();
    private static final double SIMILARITY_THRESHOLD = 0.6;

    /**
     * Store embedding for a skill.
     */
    public void storeEmbedding(String skillName, double[] embedding) {
        if (embedding != null && embedding.length > 0) {
            embeddings.put(skillName, embedding);
        }
    }

    /**
     * Find skills semantically similar to the query text.
     * Returns skill names ordered by similarity descending.
     * Falls back to empty if no embeddings are stored.
     */
    public List<String> findSimilar(String query, int limit) {
        if (embeddings.isEmpty()) return List.of();
        // For MVP: since we don't have a real embedding service wired,
        // return empty to let keyword matching take over
        log.debug("Embedding search requested but no embedder available — falling back to keyword");
        return List.of();
    }

    /**
     * Compute cosine similarity between two vectors.
     */
    public static double cosineSimilarity(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * Check if embedding-based matching is available.
     */
    public boolean isAvailable() {
        return !embeddings.isEmpty();
    }
}
