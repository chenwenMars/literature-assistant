package com.literature.controller;

import cn.hutool.core.util.StrUtil;
import com.literature.common.request.PageResult;
import com.literature.common.result.Result;
import com.literature.dto.BatchLiteratureImportRequest;
import com.literature.dto.BatchLiteratureDeleteRequest;
import com.literature.dto.LiteratureQueryRequest;
import com.literature.dto.LiteratureVO;
import com.literature.dto.SourceDocument;
import com.literature.dto.LiteratureNoteRequest;
import com.literature.entity.LiteratureNote;
import com.literature.service.LiteratureNoteService;
import com.literature.entity.Literature;
import com.literature.service.FileProcessingService;
import com.literature.service.LiteratureAiService;
import com.literature.service.LiteratureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 文献助手控制器
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@RestController
@RequestMapping("/literature")
@Tag(name = "文献助手", description = "文献分析和阅读指南生成")
@RequiredArgsConstructor
@Validated
public class LiteratureController {

    private final FileProcessingService fileProcessingService;
    private final LiteratureAiService literatureAiService;
    private final LiteratureService literatureService;
    private final LiteratureNoteService noteService;

    /**
     * 生成文献阅读指南
     *
     * @param file   上传的文献文件
     * @param apiKey Kimi AI API Key
     * @return SSE 流式响应
     */
    @PostMapping(value = "/generate-guide", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "生成文献阅读指南", description = "上传文献文件，AI 生成阅读指南（SSE 流式响应）")
    public SseEmitter generateReadingGuide(
            @Parameter(description = "文献文件（支持 PDF、Word、Markdown）", required = true)
            @RequestParam("file") MultipartFile file,
            @Parameter(description = "Kimi AI API Key", required = true)
            @RequestParam("apiKey") @NotBlank(message = "API Key 不能为空") String apiKey) {

        log.info("开始生成文献阅读指南，文件名: {}, 文件大小: {} bytes",
                file.getOriginalFilename(), file.getSize());

        // 创建 SSE 发射器，设置超时时间为 10 分钟
        SseEmitter sseEmitter = new SseEmitter(TimeUnit.MINUTES.toMillis(10));

        Long literatureId = null;
        String filePath = null;

        try {
            // 验证 API Key
            literatureAiService.validateApiKey(apiKey);

            // 发送开始事件
            sseEmitter.send(SseEmitter.event()
                    .name("start")
                    .data("开始处理文献文件..."));

            // 保存文件
            filePath = fileProcessingService.saveFile(file);

            // 发送文件保存成功事件
            sseEmitter.send(SseEmitter.event()
                    .name("progress")
                    .data("文件保存成功，开始解析内容..."));

            // 解析文件内容
            SourceDocument source = fileProcessingService.extractDocument(filePath);
            literatureId = literatureService.createLiterature(file, filePath, source.toPromptText().length());
            literatureService.saveSourceDocument(literatureId, source);
            sseEmitter.send(SseEmitter.event().name("progress").data("原文已解析，开始生成精读报告..."));
            final Long id = literatureId;
            literatureAiService.generateReadingGuideStream(apiKey, source.toPromptText(), sseEmitter, new StringBuilder())
                    .whenComplete((guide, error) -> Thread.startVirtualThread(() -> {
                        if (error != null) {
                            log.error("文献精读失败，ID: {}", id, error);
                            literatureService.updateFailure(id, 2, error.toString());
                            sendAnalysisFailure(sseEmitter, error);
                            return;
                        }
                        boolean saved = false;
                        try {
                            literatureAiService.validateSourceReferences(guide, source);
                            literatureService.updateReadingGuide(id, guide);
                            saved = true;
                            var classification = literatureAiService.generateClassification(apiKey, guide);
                            literatureService.updateClassification(id, classification.getTags(), classification.getDesc());
                            try {
                                sseEmitter.send(SseEmitter.event().name("complete").data("精读报告与分类已完成"));
                                sseEmitter.complete();
                            } catch (Exception sendError) {
                                log.error("文献已完整保存，但完成事件发送失败，ID: {}", id, sendError);
                            }
                        } catch (Exception e) {
                            log.error("文献保存或分类失败，ID: {}", id, e);
                            literatureService.updateFailure(id, saved ? 3 : 2, e.toString());
                            sendAnalysisFailure(sseEmitter, e);
                        }
                    }));

        } catch (Exception e) {
            log.error("处理文献文件失败", e);
            if (literatureId != null) {
                literatureService.updateFailure(literatureId, 2, e.toString());
            }
            sendAnalysisFailure(sseEmitter, e);
        }

        return sseEmitter;
    }

    private void sendAnalysisFailure(SseEmitter emitter, Throwable error) {
        // 传输连接已不可用时，由容器完成异步请求；失败仍由调用方记录和持久化。
        if (error instanceof AsyncRequestNotUsableException) {
            log.warn("客户端SSE连接不可用，无法发送分析失败事件", error);
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("error").data("分析失败：" + error.getMessage()));
            emitter.complete();
        } catch (Exception sendError) {
            // IOException时由Spring/容器处理断开的传输连接，业务失败已单独持久化。
            log.error("发送分析失败事件失败", sendError);
        }
    }

    @GetMapping("/{id}/source")
    public Result<SourceDocument> getSource(@PathVariable @Positive Long id) {
        return Result.success(literatureService.getSourceDocument(id));
    }

    @PostMapping("/{id}/source")
    public Result<SourceDocument> reextractSource(@PathVariable @Positive Long id) {
        return Result.success(literatureService.reextractSourceDocument(id));
    }

    @GetMapping("/{id}/notes")
    public Result<List<LiteratureNote>> getNotes(@PathVariable @Positive Long id) {
        return Result.success(noteService.list(id));
    }

    @PostMapping("/{id}/notes")
    public Result<LiteratureNote> createNote(@PathVariable @Positive Long id,
                                           @Valid @RequestBody LiteratureNoteRequest request) {
        return Result.success(noteService.save(id, null, request));
    }

    @PutMapping("/{id}/notes/{noteId}")
    public Result<LiteratureNote> updateNote(@PathVariable @Positive Long id,
                                           @PathVariable @Positive Long noteId,
                                           @Valid @RequestBody LiteratureNoteRequest request) {
        return Result.success(noteService.save(id, noteId, request));
    }

    @DeleteMapping("/{id}/notes/{noteId}")
    public Result<Void> deleteNote(@PathVariable @Positive Long id, @PathVariable @Positive Long noteId) {
        noteService.delete(id, noteId);
        return Result.success();
    }


    /**
     * 分页查询文献
     */
    @PostMapping("/page")
    @Operation(summary = "分页查询文献", description = "根据条件分页查询文献列表")
    public Result<PageResult<LiteratureVO>> pageLiteratures(
            @Valid @RequestBody LiteratureQueryRequest request) {

        log.info("分页查询文献，条件: {}", request);

        PageResult<LiteratureVO> result = literatureService.pageLiteratures(request);
        return Result.success(result);
    }

    /**
     * 获取文献详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取文献详情", description = "根据ID获取文献完整信息")
    public Result<LiteratureVO> getLiteratureDetail(
            @Parameter(description = "文献ID", required = true)
            @PathVariable @NotNull(message = "文献ID不能为空") Long id) {

        log.info("获取文献详情，ID: {}", id);

        LiteratureVO result = literatureService.getLiteratureDetail(id);
        return Result.success(result);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除文献", description = "逻辑删除文献，保留原始文件")
    public Result<Void> deleteLiterature(
            @PathVariable @Positive(message = "文献ID必须为正整数") Long id) {
        literatureService.deleteLiterature(id);
        return Result.success();
    }

    @DeleteMapping("/batch")
    @Operation(summary = "批量删除文献", description = "批量逻辑删除文献并保留原始文件，任一文献不存在或已删除则整批回滚")
    public Result<Void> deleteLiteratures(@Valid @RequestBody BatchLiteratureDeleteRequest request) {
        literatureService.deleteLiteratures(request.getIds());
        return Result.success();
    }

    /**
     * 下载文献文件
     */
    @GetMapping("/{id}/download")
    @Operation(summary = "下载文献文件", description = "根据文献ID下载对应的原始文件")
    public void downloadLiteratureFile(
            @Parameter(description = "文献ID", required = true)
            @PathVariable @NotNull(message = "文献ID不能为空") Long id,
            jakarta.servlet.http.HttpServletResponse response) {

        log.info("下载文献文件，ID: {}", id);
        literatureService.downloadLiteratureFile(id, response);
    }

    /**
     * 批量导入文献
     */
    @PostMapping(value = "/batch-import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "批量导入文献", description = "批量上传文献文件，AI生成阅读指南并实时返回处理状态")
    public SseEmitter batchImportLiterature(
            @Parameter(description = "文献文件列表", required = true)
            @RequestPart("files") List<MultipartFile> files,
            @Parameter(description = "Kimi AI API Key", required = true)
            @RequestParam("apiKey") @NotBlank(message = "API Key 不能为空") String apiKey) {

        log.info("开始批量导入文献，文件数量: {}", files.size());

        BatchLiteratureImportRequest request = new BatchLiteratureImportRequest();
        request.setFiles(files);
        request.setApiKey(apiKey);

        return literatureService.batchImportLiterature(request);
    }

    /**
     * 健康检查接口
     */
    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "检查文献助手服务状态")
    public String health() {
        return "Literature Assistant is running!";
    }
}
