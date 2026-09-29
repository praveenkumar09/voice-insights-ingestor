package com.aia.voiceinsights.ingest.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;

public class PdfTextExtractor {

    public record Extracted(String text, int pageCount) {}

    public Extracted extract(Path pdfFile) throws IOException {
        try (PDDocument doc = PDDocument.load(pdfFile.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            return new Extracted(text, doc.getNumberOfPages());
        }
    }
}
