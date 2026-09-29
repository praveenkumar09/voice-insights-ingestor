package com.aia.voiceinsights.ingest.model;

public class ProductChunk {

    private String chunkId;
    private String productName;
    private String docCategory;
    private String sourceFile;
    private int chunkIndex;
    private int totalChunks;
    private String sectionTitle;
    private int pageStart;
    private int pageEnd;
    private String content;
    private String embeddingText;

    public String getChunkId() { return chunkId; }
    public void setChunkId(String chunkId) { this.chunkId = chunkId; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public String getDocCategory() { return docCategory; }
    public void setDocCategory(String docCategory) { this.docCategory = docCategory; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public int getChunkIndex() { return chunkIndex; }
    public void setChunkIndex(int chunkIndex) { this.chunkIndex = chunkIndex; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }

    public String getSectionTitle() { return sectionTitle; }
    public void setSectionTitle(String sectionTitle) { this.sectionTitle = sectionTitle; }

    public int getPageStart() { return pageStart; }
    public void setPageStart(int pageStart) { this.pageStart = pageStart; }

    public int getPageEnd() { return pageEnd; }
    public void setPageEnd(int pageEnd) { this.pageEnd = pageEnd; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getEmbeddingText() { return embeddingText; }
    public void setEmbeddingText(String embeddingText) { this.embeddingText = embeddingText; }
}
