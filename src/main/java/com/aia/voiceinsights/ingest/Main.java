package com.aia.voiceinsights.ingest;

import com.aia.voiceinsights.ingest.chunk.DocumentChunker;
import com.aia.voiceinsights.ingest.config.AppConfig;
import com.aia.voiceinsights.ingest.extract.PdfTextExtractor;
import com.aia.voiceinsights.ingest.llm.EmbeddingClient;
import com.aia.voiceinsights.ingest.model.DocCategory;
import com.aia.voiceinsights.ingest.model.ProductChunk;
import com.aia.voiceinsights.ingest.store.ProductVectorStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Walks src/main/resources/input/<Product>/<Category>/*.pdf, extracts +
 * chunks + embeds every document, and upserts the result into
 * voice_insights.product_chunks. One end-to-end pass per run — same shape as
 * cobol-ingestor-api's Main, not a long-running daemon.
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("============================================");
        System.out.println("  AIA Voice Insights — Product Document Ingestion");
        System.out.println("  Insurance PDFs -> pgvector (voice_insights.product_chunks)");
        System.out.println("============================================");

        Path resourcesRoot = findResourcesDir();
        Path inputRoot = resourcesRoot.resolve("input");

        if (!Files.isDirectory(inputRoot)) {
            System.out.println("ERROR: input directory not found: " + inputRoot.toAbsolutePath());
            return;
        }

        int maxChars = AppConfig.getInt("INGEST_CHUNK_MAX_CHARS", "ingest.chunk.max-chars", 1500);
        DocumentChunker chunker = new DocumentChunker(maxChars);
        PdfTextExtractor extractor = new PdfTextExtractor();

        EmbeddingClient embedder = EmbeddingClient.create();
        System.out.println("  Embeddings : " + (embedder != null
            ? "ENABLED (model: " + embedder.getModel() + ")"
            : "DISABLED (set OPENAI_API_KEY) — chunks will be stored without vectors"));

        List<ProductChunk> allChunks = new ArrayList<>();
        int filesProcessed = 0;

        List<Path> productDirs;
        try (Stream<Path> stream = Files.list(inputRoot)) {
            productDirs = stream.filter(Files::isDirectory)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            System.out.println("ERROR listing " + inputRoot + ": " + e.getMessage());
            return;
        }

        System.out.println("\n--- Products found: " + productDirs.size() + " ---");

        for (Path productDir : productDirs) {
            String productName = productDir.getFileName().toString();
            System.out.println("\n--- " + productName + " ---");

            List<Path> categoryDirs;
            try (Stream<Path> stream = Files.list(productDir)) {
                categoryDirs = stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            } catch (IOException e) {
                System.out.println("  ERROR listing product folder: " + e.getMessage());
                continue;
            }

            for (Path categoryDir : categoryDirs) {
                DocCategory category = DocCategory.fromFolderName(categoryDir.getFileName().toString());
                if (category == null) {
                    System.out.println("  Skipping unrecognized folder: " + categoryDir.getFileName());
                    continue;
                }

                List<Path> pdfFiles;
                try (Stream<Path> stream = Files.list(categoryDir)) {
                    pdfFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                } catch (IOException e) {
                    System.out.println("  ERROR listing " + categoryDir + ": " + e.getMessage());
                    continue;
                }

                for (Path pdfFile : pdfFiles) {
                    String fileName = pdfFile.getFileName().toString();
                    try {
                        PdfTextExtractor.Extracted extracted = extractor.extract(pdfFile);
                        List<DocumentChunker.Section> sections = chunker.chunk(extracted.text(), category);

                        if (sections.isEmpty()) {
                            System.out.println("  [" + category.folderName() + "] " + fileName + " ... 0 chunks");
                            continue;
                        }

                        List<ProductChunk> fileChunks = new ArrayList<>();
                        for (int i = 0; i < sections.size(); i++) {
                            DocumentChunker.Section section = sections.get(i);
                            ProductChunk chunk = new ProductChunk();
                            chunk.setChunkId(chunkId(productName, category, fileName, i));
                            chunk.setProductName(productName);
                            chunk.setDocCategory(category.folderName());
                            chunk.setSourceFile(fileName);
                            chunk.setChunkIndex(i);
                            chunk.setTotalChunks(sections.size());
                            chunk.setSectionTitle(section.title());
                            chunk.setPageStart(1);
                            chunk.setPageEnd(extracted.pageCount());
                            chunk.setContent(section.content());
                            chunk.setEmbeddingText(
                                "Product: " + productName
                                    + " | Category: " + category.folderName()
                                    + " | Section: " + section.title()
                                    + "\n\n" + section.content());
                            fileChunks.add(chunk);
                        }

                        allChunks.addAll(fileChunks);
                        filesProcessed++;
                        System.out.println("  [" + category.folderName() + "] " + fileName
                            + " ... " + fileChunks.size() + " chunks");
                    } catch (Exception e) {
                        System.out.println("  [" + category.folderName() + "] " + fileName
                            + " ... ERROR: " + e.getMessage());
                    }
                }
            }
        }

        Map<String, float[]> embeddingMap = embedAll(allChunks, embedder);
        storeToVectorDB(allChunks, embeddingMap);

        System.out.println("\n============================================");
        System.out.println("  INGESTION COMPLETE");
        System.out.printf("  Products processed : %d%n", productDirs.size());
        System.out.printf("  Files processed     : %d%n", filesProcessed);
        System.out.printf("  Total chunks        : %d%n", allChunks.size());
        System.out.printf("  Embeddings stored   : %d%n", embeddingMap.size());
        System.out.println("============================================");
    }

    private static Map<String, float[]> embedAll(List<ProductChunk> allChunks, EmbeddingClient embedder) {
        System.out.println("\n--- Generating Embeddings ---");
        Map<String, float[]> result = new HashMap<>();
        if (embedder == null) {
            System.out.println("  Skipped (OPENAI_API_KEY not set)");
            return result;
        }
        if (allChunks.isEmpty()) {
            System.out.println("  Skipped (no chunks)");
            return result;
        }

        System.out.printf("  Embedding %d chunks (batch size %d)...%n", allChunks.size(), EmbeddingClient.BATCH_SIZE);
        for (int i = 0; i < allChunks.size(); i += EmbeddingClient.BATCH_SIZE) {
            List<ProductChunk> batch = allChunks.subList(i, Math.min(i + EmbeddingClient.BATCH_SIZE, allChunks.size()));
            List<String> texts = batch.stream().map(ProductChunk::getEmbeddingText).toList();
            try {
                List<float[]> vecs = embedder.embedBatch(texts);
                for (int j = 0; j < batch.size(); j++) {
                    result.put(batch.get(j).getChunkId(), vecs.get(j));
                }
                System.out.printf("  ... %d/%d embedded%n",
                    Math.min(i + EmbeddingClient.BATCH_SIZE, allChunks.size()), allChunks.size());
                if (i + EmbeddingClient.BATCH_SIZE < allChunks.size()) Thread.sleep(150);
            } catch (Exception e) {
                System.out.println("  WARNING: batch " + (i / EmbeddingClient.BATCH_SIZE + 1) + " failed: " + e.getMessage());
            }
        }
        System.out.println("  ✓ " + result.size() + " embeddings generated");
        return result;
    }

    private static void storeToVectorDB(List<ProductChunk> allChunks, Map<String, float[]> embeddingMap) {
        System.out.println("\n--- Storing to PostgreSQL (voice_insights.product_chunks) ---");
        if (allChunks.isEmpty()) {
            System.out.println("  Nothing to store");
            return;
        }
        try (ProductVectorStore store = ProductVectorStore.create()) {
            store.upsertChunks(allChunks, embeddingMap);
            System.out.println("  ✓ " + allChunks.size() + " chunks stored");
        } catch (Exception e) {
            System.out.println("  ERROR: " + e.getMessage());
            System.out.println("  Is PostgreSQL running? -> (from cobol-ingestor-api) docker compose up -d postgres");
        }
    }

    private static String chunkId(String productName, DocCategory category, String fileName, int index) {
        return slug(productName) + "::" + category.name().toLowerCase() + "::" + slug(fileName) + "::" + index;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    private static Path findResourcesDir() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path candidate = cwd.resolve("src/main/resources");
        if (Files.isDirectory(candidate)) return candidate;
        candidate = cwd.resolve("../src/main/resources").normalize();
        if (Files.isDirectory(candidate)) return candidate;
        return cwd;
    }
}
