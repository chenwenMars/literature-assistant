package com.literature.service;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.literature.common.exception.BusinessException;
import com.literature.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.*;
import com.literature.dto.SourceDocument;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 文件处理服务
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
public class FileProcessingService {

    @Value("${literature.file.upload-path:./uploads/documents}")
    private String uploadPath;

    @Value("${literature.file.allowed-extensions:pdf,doc,docx,md,markdown}")
    private String allowedExtensions;

    @Value("${literature.file.max-file-size:10MB}")
    private String maxFileSize;

    /**
     * 保存上传的文件并返回文件路径
     *
     * @param file 上传的文件
     * @return 保存后的文件路径
     */
    public String saveFile(MultipartFile file) {
        try {
            // 验证文件
            validateFile(file);

            // 确保上传目录存在
            Path uploadDir = Paths.get(uploadPath);
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            // 生成唯一文件名
            String originalFilename = file.getOriginalFilename();
            String extension = FilenameUtils.getExtension(originalFilename);
            String uniqueFilename = UUID.randomUUID().toString() + "." + extension;
            
            // 保存文件
            Path filePath = uploadDir.resolve(uniqueFilename);
            Files.copy(file.getInputStream(), filePath);
            
            log.info("文件保存成功: {}", filePath.toString());
            return filePath.toString();
            
        } catch (IOException e) {
            log.error("文件保存失败", e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR);
        }
    }

    /**
     * 解析文件内容
     *
     * @param filePath 文件路径
     * @return 文件内容
     */
    public String extractFileContent(String filePath) {
        return extractDocument(filePath).toPromptText();
    }

    public SourceDocument extractDocument(String filePath) {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new BusinessException(ResultCode.FILE_NOT_EXIST);
        }
        List<SourceDocument.Block> blocks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        String extension = FilenameUtils.getExtension(filePath).toLowerCase();
        try {
            switch (extension) {
                case "pdf" -> {
                    warnings.add("PDF按文件页序提取文字；未进行OCR，图片、复杂表格和公式可能未完整提取，不能据此断言原文没有相关证据。");
                    try (PDDocument document = PDDocument.load(file)) {
                        PDFTextStripper stripper = new PDFTextStripper();
                        for (int page = 1; page <= document.getNumberOfPages(); page++) {
                            stripper.setStartPage(page);
                            stripper.setEndPage(page);
                            String text = stripper.getText(document);
                            if (StrUtil.isBlank(text)) {
                                warnings.add("PDF文件第" + page + "页未提取到文字，需要核对原文件或提供OCR文本。");
                            }
                            addBlock(blocks, "PDF文件第" + page + "页（非论文印刷页码）", text);
                        }
                    }
                }
                case "docx" -> {
                    warnings.add("Word按正文顺序提取段落和表格；图片、公式、页眉页脚、脚注和批注未作为完整证据提取，无可靠页码。");
                    try (FileInputStream input = new FileInputStream(file);
                         XWPFDocument document = new XWPFDocument(input)) {
                        int index = 0;
                        for (IBodyElement element : document.getBodyElements()) {
                            index++;
                            if (element instanceof XWPFParagraph paragraph) {
                                addBlock(blocks, "Word正文段落位置" + index, paragraph.getText());
                            } else if (element instanceof XWPFTable table) {
                                addBlock(blocks, "Word正文表格位置" + index, tableText(table));
                            } else {
                                warnings.add("Word正文位置" + index + "为未支持的内容类型：" + element.getElementType());
                            }
                        }
                    }
                }
                case "doc" -> {
                    warnings.add("旧版Word提取文字；表格结构、图片和公式可能缺失，无可靠页码。");
                    addTextBlocks(blocks, "Word文字片段", extractDocContent(file));
                }
                case "md", "markdown" -> {
                    warnings.add("Markdown保留标题及文本表格；外部图片与链接内容未读取，无页码。");
                    addTextBlocks(blocks, "Markdown文本片段", FileUtil.readString(file, StandardCharsets.UTF_8));
                }
                default -> throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED);
            }
        } catch (IOException e) {
            log.error("文件内容解析失败: {}", filePath, e);
            throw new BusinessException("文件内容解析失败: " + e.getMessage());
        }
        if (blocks.isEmpty()) {
            throw new BusinessException("未提取到可阅读文字，请提供含文本的文献或完成OCR后重新上传");
        }
        return new SourceDocument(List.copyOf(blocks), List.copyOf(warnings));
    }

    private void addTextBlocks(List<SourceDocument.Block> blocks, String label, String text) {
        for (String part : text.split("\\n\\s*\\n")) {
            addBlock(blocks, label + (blocks.size() + 1), part);
        }
    }

    private void addBlock(List<SourceDocument.Block> blocks, String label, String text) {
        if (StrUtil.isNotBlank(text)) {
            blocks.add(new SourceDocument.Block("S" + (blocks.size() + 1), label, text.strip()));
        }
    }

    private String tableText(XWPFTable table) {
        StringBuilder text = new StringBuilder();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                StringBuilder cellText = new StringBuilder();
                for (IBodyElement element : cell.getBodyElements()) {
                    if (element instanceof XWPFParagraph paragraph) {
                        cellText.append(paragraph.getText()).append("\n");
                    } else if (element instanceof XWPFTable nested) {
                        cellText.append(tableText(nested)).append("\n");
                    }
                }
                cells.add(cellText.toString().strip());
            }
            text.append(String.join("\t", cells)).append("\n");
        }
        return text.toString();
    }

    /**
     * 验证上传的文件
     */
    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传的文件为空");
        }

        String originalFilename = file.getOriginalFilename();
        if (StrUtil.isBlank(originalFilename)) {
            throw new BusinessException("文件名不能为空");
        }

        // 验证文件扩展名
        String extension = FilenameUtils.getExtension(originalFilename).toLowerCase();
        List<String> allowedExtList = Arrays.asList(allowedExtensions.split(","));
        if (!allowedExtList.contains(extension)) {
            throw new BusinessException(ResultCode.FILE_TYPE_NOT_SUPPORTED, 
                "只支持以下文件类型: " + allowedExtensions);
        }

        // 验证文件大小
        long maxSize = parseFileSize(maxFileSize);
        if (file.getSize() > maxSize) {
            throw new BusinessException(ResultCode.FILE_SIZE_EXCEEDED, 
                "文件大小超过限制: " + maxFileSize);
        }
    }

    /**
     * 解析 DOC 文件内容
     */
    private String extractDocContent(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             HWPFDocument document = new HWPFDocument(fis);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * 解析文件大小字符串
     */
    private long parseFileSize(String sizeStr) {
        if (StrUtil.isBlank(sizeStr)) {
            return 10 * 1024 * 1024; // 默认 10MB
        }
        
        sizeStr = sizeStr.trim().toLowerCase();
        long multiplier = 1;
        
        if (sizeStr.endsWith("kb")) {
            multiplier = 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("mb")) {
            multiplier = 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        } else if (sizeStr.endsWith("gb")) {
            multiplier = 1024 * 1024 * 1024;
            sizeStr = sizeStr.substring(0, sizeStr.length() - 2);
        }
        
        try {
            return Long.parseLong(sizeStr.trim()) * multiplier;
        } catch (NumberFormatException e) {
            return 10 * 1024 * 1024; // 默认 10MB
        }
    }

    /**
     * 删除文件
     */
    public void deleteFile(String filePath) {
        try {
            Files.deleteIfExists(Paths.get(filePath));
            log.info("文件删除成功: {}", filePath);
        } catch (IOException e) {
            log.warn("文件删除失败: {}", filePath, e);
        }
    }
}
