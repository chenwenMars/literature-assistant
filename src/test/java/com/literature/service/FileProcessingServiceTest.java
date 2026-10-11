package com.literature.service;

import com.literature.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileProcessingServiceTest {
    @TempDir Path directory;
    private final FileProcessingService service = new FileProcessingService();

    @Test
    void docxPreservesParagraphAndTableOrder() throws Exception {
        Path file = directory.resolve("case.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("问题分析");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("指标");
            table.getRow(0).getCell(1).setText("基线");
            table.getRow(1).getCell(0).setText("交付周期");
            table.getRow(1).getCell(1).setText("12天");
            document.createParagraph().createRun().setText("提出方案");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }
        var source = service.extractDocument(file.toString());
        assertEquals(3, source.blocks().size());
        assertEquals("问题分析", source.blocks().get(0).text());
        assertTrue(source.blocks().get(1).text().contains("交付周期\t12天"));
        assertEquals("提出方案", source.blocks().get(2).text());
        assertEquals("S2", source.blocks().get(1).id());
    }

    @Test
    void docxNestedTableEvidenceIsNotDropped() throws Exception {
        Path file = directory.resolve("nested.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            var cell = document.createTable(1, 1).getRow(0).getCell(0);
            cell.setText("外层资料");
            var nested = cell.getCTTc().addNewTbl();
            nested.addNewTr().addNewTc().addNewP().addNewR().addNewT().setStringValue("访谈编码证据");
            try (var output = Files.newOutputStream(file)) { document.write(output); }
        }
        assertTrue(service.extractDocument(file.toString()).toPromptText().contains("访谈编码证据"));
    }

    @Test
    void pdfLabelsPhysicalPagesAndReportsEmptyPages() throws Exception {
        Path file = directory.resolve("case.pdf");
        try (PDDocument document = new PDDocument()) {
            for (String text : new String[] {"first page", "second page"}) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(PDType1Font.HELVETICA, 12);
                    stream.newLineAtOffset(50, 700);
                    stream.showText(text);
                    stream.endText();
                }
            }
            document.addPage(new PDPage());
            document.save(file.toFile());
        }
        var source = service.extractDocument(file.toString());
        assertEquals(2, source.blocks().size());
        assertEquals("PDF文件第2页（非论文印刷页码）", source.blocks().get(1).label());
        assertTrue(source.warnings().stream().anyMatch(w -> w.contains("第3页")));
    }

    @Test
    void markdownKeepsHeadingsTablesAndRejectsEmptyInput() throws Exception {
        Path file = directory.resolve("case.md");
        Files.writeString(file, "# 研究方法\n\n|指标|数值|\n|---|---|\n|周期|12|\n");
        var source = service.extractDocument(file.toString());
        assertTrue(source.toPromptText().contains("# 研究方法"));
        assertTrue(source.toPromptText().contains("|周期|12|"));
        Files.writeString(file, " \n\n");
        assertThrows(BusinessException.class, () -> service.extractDocument(file.toString()));
    }
}
