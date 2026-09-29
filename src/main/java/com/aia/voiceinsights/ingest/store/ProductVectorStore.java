package com.aia.voiceinsights.ingest.store;

import com.aia.voiceinsights.ingest.config.AppConfig;
import com.aia.voiceinsights.ingest.llm.EmbeddingClient;
import com.aia.voiceinsights.ingest.model.ProductChunk;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.List;
import java.util.Map;

/**
 * Stores product document chunks + embeddings into the SAME Postgres/pgvector
 * server that cobol-ingestor-api and cobalt-rag-api use, but under a
 * dedicated {@code voice_insights} schema and {@code product_chunks} table —
 * self-provisioned here (mirrors cobalt-rag-api's AuthStore self-provisioning
 * pattern) rather than requiring an init.sql mounted into a Postgres
 * container this project doesn't own.
 *
 * Connection is read from env vars:
 *   PG_HOST (default: localhost)   PG_PORT (default: 5432)
 *   PG_DB   (default: ingestor)    PG_USER (default: admin)   PG_PASS (default: admin)
 */
public class ProductVectorStore implements AutoCloseable {

    private static final String SCHEMA = AppConfig.get("db.pg.schema", "voice_insights");

    private static final String INSERT_SQL = """
        INSERT INTO %s.product_chunks (
            chunk_id, product_name, doc_category, source_file,
            chunk_index, total_chunks, section_title, page_start, page_end,
            content, embedding_text, payload, embedding
        ) VALUES (
            ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?::jsonb, ?::vector
        )
        ON CONFLICT (chunk_id) DO UPDATE SET
            section_title  = EXCLUDED.section_title,
            content        = EXCLUDED.content,
            embedding_text = EXCLUDED.embedding_text,
            payload        = EXCLUDED.payload,
            embedding      = EXCLUDED.embedding
        """.formatted(SCHEMA);

    private final Connection conn;
    private final ObjectMapper mapper;

    private ProductVectorStore(Connection conn) {
        this.conn = conn;
        this.mapper = new ObjectMapper();
    }

    public static ProductVectorStore create() throws SQLException {
        String host = AppConfig.get("PG_HOST", "db.pg.host", "localhost");
        String port = AppConfig.get("PG_PORT", "db.pg.port", "5432");
        String db   = AppConfig.get("PG_DB",   "db.pg.name", "ingestor");
        String user = AppConfig.get("PG_USER", "db.pg.user", "admin");
        String pass = AppConfig.get("PG_PASS", "db.pg.pass", "admin");
        String url  = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        Connection conn = DriverManager.getConnection(url, user, pass);
        conn.setAutoCommit(false);
        ProductVectorStore store = new ProductVectorStore(conn);
        store.ensureSchema();
        return store;
    }

    private void ensureSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS vector");
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
            st.execute("""
                CREATE TABLE IF NOT EXISTS %s.product_chunks (
                    chunk_id       TEXT PRIMARY KEY,
                    product_name   TEXT NOT NULL,
                    doc_category   TEXT NOT NULL,
                    source_file    TEXT NOT NULL,
                    chunk_index    INTEGER,
                    total_chunks   INTEGER,
                    section_title  TEXT,
                    page_start     INTEGER,
                    page_end       INTEGER,
                    content        TEXT,
                    embedding_text TEXT,
                    payload        JSONB,
                    embedding      vector(%d),
                    created_at     TIMESTAMPTZ DEFAULT NOW()
                )
                """.formatted(SCHEMA, EmbeddingClient.DIMS));
            st.execute("CREATE INDEX IF NOT EXISTS idx_vi_chunks_product  ON %s.product_chunks (product_name)".formatted(SCHEMA));
            st.execute("CREATE INDEX IF NOT EXISTS idx_vi_chunks_category ON %s.product_chunks (doc_category)".formatted(SCHEMA));
            st.execute("CREATE INDEX IF NOT EXISTS idx_vi_chunks_payload  ON %s.product_chunks USING gin (payload)".formatted(SCHEMA));
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
    }

    /** Inserts/updates all chunks in a single transaction. chunkId -> embedding vector (null if not embedded). */
    public void upsertChunks(List<ProductChunk> chunks, Map<String, float[]> embeddingMap) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {
            for (ProductChunk c : chunks) {
                float[] vec = embeddingMap.get(c.getChunkId());

                ps.setString(1, c.getChunkId());
                ps.setString(2, c.getProductName());
                ps.setString(3, c.getDocCategory());
                ps.setString(4, c.getSourceFile());
                ps.setInt(5, c.getChunkIndex());
                ps.setInt(6, c.getTotalChunks());
                ps.setString(7, c.getSectionTitle());
                ps.setInt(8, c.getPageStart());
                ps.setInt(9, c.getPageEnd());
                ps.setString(10, c.getContent());
                ps.setString(11, c.getEmbeddingText());
                ps.setString(12, mapper.writeValueAsString(c));
                ps.setString(13, vec != null ? EmbeddingClient.toVectorString(vec) : null);

                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}
