package com.aia.voiceinsights.ingest.model;

/**
 * The six document categories every product folder under {@code input/} is
 * expected to have a matching subfolder for. The folder name (not the enum
 * name) is what's scanned on disk — see {@link #fromFolderName}.
 */
public enum DocCategory {
    RIDER("Rider"),
    OTHERS("Others"),
    PRODUCT_SUMMARY("Product Summary"),
    CONTRACT("Contract"),
    FACT_SHEET("Fact Sheet"),
    FAQ("FAQ");

    private final String folderName;

    DocCategory(String folderName) {
        this.folderName = folderName;
    }

    public String folderName() {
        return folderName;
    }

    /** Matches a folder name to a category, case- and whitespace-insensitive. Null if no match. */
    public static DocCategory fromFolderName(String folderName) {
        String normalized = folderName.trim().toLowerCase();
        for (DocCategory c : values()) {
            if (c.folderName.toLowerCase().equals(normalized)) return c;
        }
        return null;
    }
}
