package com.aia.voiceinsights.ingest.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.aia.voiceinsights.ingest.config.AppConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Calls OpenAI Embeddings API (text-embedding-3-large, 3072 dims) — mirrors
 * cobol-ingestor-api's EmbeddingClient so both ingestors stay on the same
 * model/dimension convention. Set OPENAI_API_KEY to enable.
 */
public class EmbeddingClient {

    public static final int DIMS       = AppConfig.getInt("OPENAI_EMBED_DIMS", "openai.embed.dims", 3072);
    public static final int BATCH_SIZE = AppConfig.getInt("OPENAI_EMBED_BATCH_SIZE", "openai.embed.batch-size", 50);

    private final String apiKey;
    private final String model;
    private final String embedUrl;
    private final HttpClient http;
    private final ObjectMapper mapper;

    private EmbeddingClient(String apiKey, String model, String embedUrl) {
        this.apiKey   = apiKey;
        this.model    = model;
        this.embedUrl = embedUrl;
        this.http     = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        this.mapper   = new ObjectMapper();
    }

    /** Returns null when OPENAI_API_KEY is absent — callers skip embedding. */
    public static EmbeddingClient create() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) return null;
        String model = AppConfig.get("OPENAI_EMBED_MODEL", "openai.embed.model", "text-embedding-3-large");
        String url   = AppConfig.get("openai.embed.url", "https://api.openai.com/v1/embeddings");
        return new EmbeddingClient(key, model, url);
    }

    public String getModel() { return model; }

    /** Embeds a batch of texts (max BATCH_SIZE per call). */
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts.isEmpty()) return List.of();

        Map<String, Object> body = Map.of(
            "model", model,
            "input", texts,
            "dimensions", DIMS
        );

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(embedUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("OpenAI Embeddings HTTP " + response.statusCode()
                + ": " + response.body());
        }

        JsonNode root = mapper.readTree(response.body());
        JsonNode dataArr = root.path("data");

        List<float[]> result = new ArrayList<>(texts.size());
        for (JsonNode item : dataArr) {
            JsonNode embArr = item.path("embedding");
            float[] vec = new float[embArr.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) embArr.get(i).asDouble();
            }
            result.add(vec);
        }
        return result;
    }

    /** Converts a float[] vector to the pgvector string format: [v1,v2,...] */
    public static String toVectorString(float[] vec) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        return sb.append(']').toString();
    }
}
