package com.literature.service.impl;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.literature.common.exception.BusinessException;
import com.literature.common.request.PageResult;
import com.literature.common.result.ResultCode;
import com.literature.dto.BatchLiteratureImportRequest;
import com.literature.dto.LiteratureQueryRequest;
import com.literature.dto.LiteratureVO;
import com.literature.dto.SourceDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.literature.dto.ClassificationResponse;
import cn.hutool.json.JSONUtil;
import com.literature.entity.Literature;
import com.literature.mapper.LiteratureMapper;
import com.literature.service.FileProcessingService;
import com.literature.service.LiteratureAiService;
import com.literature.service.LiteratureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 文献服务实现类
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiteratureServiceImpl extends ServiceImpl<LiteratureMapper, Literature> implements LiteratureService {

    private final ObjectMapper objectMapper;
    private final FileProcessingService fileProcessingService;
    private final LiteratureAiService literatureAiService;
    
    // 虚拟线程池，用于并发处理文件
    private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public Long createLiterature(MultipartFile file, String filePath, Integer contentLength) {
        Literature literature = new Literature();
        literature.setOriginalName(file.getOriginalFilename());
        literature.setFilePath(filePath);
        literature.setFileSize(file.getSize());
        literature.setFileType(FileUtil.extName(file.getOriginalFilename()).toLowerCase());
        literature.setContentLength(contentLength);
        literature.setStatus(Literature.Status.PROCESSING.getCode());

        this.save(literature);
        log.info("创建文献记录成功，ID: {}, 文件名: {}", literature.getId(), literature.getOriginalName());

        return literature.getId();
    }

    @Override
    public void updateReadingGuide(Long id, String readingGuide) {
        Literature literature = new Literature();
        literature.setId(id);
        literature.setReadingGuide(readingGuide);
        literature.setReportVersion("mba-reading-v2");

        this.updateById(literature);
        log.info("更新文献阅读指南成功，ID: {}", id);
    }

    @Override
    public void saveSourceDocument(Long id, SourceDocument document) {
        try {
            this.lambdaUpdate().eq(Literature::getId, id)
                    .set(Literature::getSourceDocument, objectMapper.writeValueAsString(document)).update();
        } catch (JsonProcessingException e) {
            throw new BusinessException("原文快照保存失败：" + e.getMessage());
        }
    }

    @Override
    public SourceDocument getSourceDocument(Long id) {
        Literature literature = requireLiterature(id);
        if (literature.getSourceDocument() == null) {
            throw new BusinessException("历史文献未保存原文快照，请先重新解析原文");
        }
        try {
            return objectMapper.readValue(literature.getSourceDocument(), SourceDocument.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException("原文快照读取失败：" + e.getMessage());
        }
    }

    @Override
    public SourceDocument reextractSourceDocument(Long id) {
        Literature literature = requireLiterature(id);
        if (literature.getSourceDocument() != null) return getSourceDocument(id);
        SourceDocument source = fileProcessingService.extractDocument(literature.getFilePath());
        saveSourceDocument(id, source);
        return source;
    }

    private Literature requireLiterature(Long id) {
        Literature literature = this.getById(id);
        if (literature == null) throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在或已删除");
        return literature;
    }

    @Override
    public void updateFailure(Long id, Integer status, String message) {
        this.lambdaUpdate().eq(Literature::getId, id).set(Literature::getStatus, status)
                .set(Literature::getErrorMessage, message.length() > 2000 ? message.substring(0, 2000) : message).update();
    }

    @Override
    public void deleteLiterature(Long id) {
        if (baseMapper.deleteById(id) == 0) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在或已删除");
        }
        log.info("逻辑删除文献成功，ID: {}", id);
    }

    @Override
    @Transactional
    public void deleteLiteratures(List<Long> ids) {
        Set<Long> uniqueIds = Set.copyOf(ids);
        int deletedCount = baseMapper.deleteBatchIds(uniqueIds);
        if (deletedCount != uniqueIds.size()) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "部分文献不存在或已删除，本次批量删除已取消，请刷新列表后重试");
        }
        log.info("批量逻辑删除文献成功，数量: {}, IDs: {}", deletedCount, uniqueIds);
    }

    @Override
    public void updateClassification(Long id, List<String> tags, String description) {
        this.lambdaUpdate().eq(Literature::getId, id).set(Literature::getTags, JSONUtil.toJsonStr(tags))
                .set(Literature::getDescription, description).set(Literature::getStatus, 1)
                .set(Literature::getErrorMessage, null).update();
        log.info("更新文献分类成功，ID: {}, 标签数量: {}", id, tags.size());
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        Literature literature = new Literature();
        literature.setId(id);
        literature.setStatus(status);

        this.updateById(literature);
        log.info("更新文献状态成功，ID: {}, 状态: {}", id, status);
    }

    @Override
    public PageResult<LiteratureVO> pageLiteratures(LiteratureQueryRequest request) {
        // 创建分页对象
        Page<Literature> page = new Page<>(request.getPageNum(), request.getPageSize());

        // 执行分页查询
        IPage<Literature> pageResult = baseMapper.selectLiteraturePage(page, request);

        // 转换为 VO 对象
        List<LiteratureVO> voList = pageResult.getRecords().stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());

        return PageResult.of(voList, pageResult.getTotal(), request.getPageNum(), request.getPageSize());
    }

    @Override
    public LiteratureVO getLiteratureDetail(Long id) {
        Literature literature = this.getById(id);
        if (literature == null) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在");
        }

        return convertToDetailVO(literature);
    }

    /**
     * 转换为列表 VO（不包含完整阅读指南）
     */
    private LiteratureVO convertToVO(Literature literature) {
        LiteratureVO vo = new LiteratureVO();
        vo.setId(literature.getId());
        vo.setOriginalName(literature.getOriginalName());
        vo.setFileSize(literature.getFileSize());
        vo.setFileType(literature.getFileType());
        vo.setContentLength(literature.getContentLength());
        vo.setTags(literature.getTags());
        vo.setDescription(literature.getDescription());
        vo.setStatus(literature.getStatus());
        vo.setSourceAvailable(literature.getSourceDocument() != null);
        vo.setReportVersion(literature.getReportVersion());
        vo.setErrorMessage(literature.getErrorMessage());
        vo.setStatusDesc(getStatusDesc(literature.getStatus()));
        vo.setCreateTime(literature.getCreateTime());
        vo.setUpdateTime(literature.getUpdateTime());

        // 阅读指南摘要（截取前200个字符）
        if (StrUtil.isNotBlank(literature.getReadingGuide())) {
            String summary = literature.getReadingGuide().length() > 200
                    ? literature.getReadingGuide().substring(0, 200) + "..."
                    : literature.getReadingGuide();
            vo.setReadingGuideSummary(summary);
        }

        return vo;
    }

    /**
     * 转换为详情 VO（包含完整信息）
     */
    private LiteratureVO convertToDetailVO(Literature literature) {
        LiteratureVO vo = convertToVO(literature);
        // 详情页面可以返回完整的阅读指南
        vo.setReadingGuideSummary(literature.getReadingGuide());
        return vo;
    }

    @Override
    public void downloadLiteratureFile(Long id, HttpServletResponse response) {
        log.info("开始下载文献文件，ID: {}", id);

        // 查询文献信息
        Literature literature = this.getById(id);
        if (literature == null) {
            throw new BusinessException(ResultCode.DATA_NOT_EXIST, "文献不存在");
        }

        // 检查文件是否存在
        if (StrUtil.isBlank(literature.getFilePath())) {
            throw new BusinessException(ResultCode.FILE_NOT_EXIST, "文件路径为空");
        }

        // 处理文件路径，支持相对路径和绝对路径
        String filePath = literature.getFilePath();
        java.io.File file;

        // 如果是相对路径，则相对于项目根目录
        if (filePath.startsWith("./") || !filePath.startsWith("/")) {
            // 获取项目根目录
            String projectRoot = System.getProperty("user.dir");
            file = FileUtil.file(projectRoot, filePath);
        } else {
            // 绝对路径直接使用
            file = FileUtil.file(filePath);
        }

        if (!file.exists()) {
            throw new BusinessException(ResultCode.FILE_NOT_EXIST, "文件不存在: " + file.getAbsolutePath());
        }

        try {
            String fileName = literature.getOriginalName();
            String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                    .replaceAll("\\+", "%20");

            // 设置响应头
            response.setContentType("application/octet-stream");
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Content-Disposition",
                    "attachment; filename*=UTF-8''" + encodedFileName);
            response.setHeader("Content-Length", String.valueOf(file.length()));

            // 下载文件
            try (OutputStream os = response.getOutputStream()) {
                FileUtil.writeToStream(file, os);
                os.flush();
            }

            log.info("文献文件下载成功，ID: {}, 文件名: {}, 文件大小: {} bytes",
                    id, fileName, file.length());
        } catch (Exception e) {
            log.error("下载文献文件失败，ID: {}", id, e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_ERROR, "文件下载失败: " + e.getMessage());
        }
    }

    @Override
    public SseEmitter batchImportLiterature(BatchLiteratureImportRequest request) {
        log.info("开始批量导入文献，文件数量: {}", request.getFiles().size());

        // 30分钟超时
        SseEmitter sseEmitter = new SseEmitter(30 * 60 * 1000L);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger totalCount = new AtomicInteger(request.getFiles().size());
        AtomicInteger errorCount = new AtomicInteger(0);

        // 验证API Key
        literatureAiService.validateApiKey(request.getApiKey());
        
        // 发送批量开始事件
        sendSseEvent(sseEmitter, "batch_start", Map.of("total", totalCount.get(), "message", "开始批量处理"));

        for (int index = 0; index < request.getFiles().size(); index++) {
            final int fileIndex = index;
            MultipartFile file = request.getFiles().get(index);
            CompletableFuture.runAsync(() -> {
                Long literatureId = null;
                boolean guideSaved = false;
                String outcome = "file_complete";
                Map<String, Object> event = new HashMap<>();
                event.put("index", fileIndex);
                event.put("filename", file.getOriginalFilename());
                try {
                    sendSseEvent(sseEmitter, "file_start", Map.of("index", fileIndex,
                            "filename", file.getOriginalFilename(), "message", "开始处理文件"));
                    String path = fileProcessingService.saveFile(file);
                    SourceDocument source = fileProcessingService.extractDocument(path);
                    literatureId = createLiterature(file, path, source.toPromptText().length());
                    saveSourceDocument(literatureId, source);
                    sendSseEvent(sseEmitter, "file_saved", Map.of("index", fileIndex,
                            "literatureId", literatureId, "message", "原文已解析，开始生成精读报告"));
                    String guide = literatureAiService.generateReadingGuide(request.getApiKey(), source.toPromptText());
                    literatureAiService.validateSourceReferences(guide, source);
                    updateReadingGuide(literatureId, guide);
                    guideSaved = true;
                    ClassificationResponse classification = literatureAiService.generateClassification(request.getApiKey(), guide);
                    updateClassification(literatureId, classification.getTags(), classification.getDesc());
                    event.put("literatureId", literatureId);
                    event.put("message", "精读报告与分类已完成");
                } catch (Exception e) {
                    log.error("批量文献处理失败：{}", file.getOriginalFilename(), e);
                    errorCount.incrementAndGet();
                    if (literatureId != null) updateFailure(literatureId, guideSaved ? 3 : 2, e.toString());
                    outcome = "file_error";
                    event.put("error", e.toString());
                } finally {
                    // 计数和发送按同一顺序完成，避免并发文件的迟到事件覆盖进度或晚于batch_complete。
                    synchronized (completedCount) {
                        int finished = completedCount.incrementAndGet();
                        event.put("completed", finished);
                        event.put("total", totalCount.get());
                        try {
                            sendSseEvent(sseEmitter, outcome, event);
                            if (finished == totalCount.get()) {
                                sendSseEvent(sseEmitter, "batch_complete", Map.of("total", totalCount.get(),
                                        "errors", errorCount.get(), "message", errorCount.get() == 0
                                        ? "批量处理完成" : "批量处理结束，存在失败文件"));
                                sseEmitter.complete();
                            }
                        } catch (BusinessException sendError) {
                            log.error("批量结果已处理但发送结果事件失败", sendError);
                        }
                    }
                }
            }, virtualThreadExecutor);
        }
        return sseEmitter;
    }

    private void sendSseEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data instanceof String ? data : JSONUtil.toJsonStr(data)));
        } catch (Exception e) {
            log.error("发送SSE事件失败：{}", name, e);
            throw new BusinessException("发送SSE事件失败：" + e.getMessage());
        }
    }

    /**
     * 获取状态描述
     */
    private String getStatusDesc(Integer status) {
        if (status == null) {
            return "未知";
        }

        return switch (status) {
            case 0 -> "处理中";
            case 1 -> "已完成";
            case 2 -> "处理失败";
            case 3 -> "指南已生成，分类失败";
            default -> "未知";
        };
    }
}
