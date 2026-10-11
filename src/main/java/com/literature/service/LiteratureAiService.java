package com.literature.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.literature.common.exception.BusinessException;
import com.literature.common.result.ResultCode;
import com.literature.dto.KimiChatRequest;
import com.literature.dto.ClassificationResponse;
import com.literature.dto.SourceDocument;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import cn.hutool.json.JSONArray;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 文献 AI 服务
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Slf4j
@Service
public class LiteratureAiService {

    @Value("${literature.ai.base-url:https://api.moonshot.cn/v1}")
    private String baseUrl;

    @Value("${literature.ai.model}")
    private String model;

    @Value("${literature.ai.max-completion-tokens}")
    private Integer maxCompletionTokens;

    @Value("${literature.ai.reasoning-effort}")
    private String reasoningEffort;

    private final int timeout;
    private final int classificationTimeout;

    @Value("${literature.ai.system-prompt-file}")
    private String systemPromptFile;

    @Value("${literature.ai.classification-prompt-file}")
    private String classificationPromptFile;

    private final OkHttpClient httpClient;
    private final OkHttpClient classificationHttpClient;
    private final ResourceLoader resourceLoader;

    // 缓存系统提示词内容
    private String cachedSystemPrompt;
    private String cachedClassificationPrompt;

    public LiteratureAiService(ResourceLoader resourceLoader,
                               @Value("${literature.ai.timeout:60000}") int timeout,
                               @Value("${literature.ai.classification-timeout}") int classificationTimeout) {
        org.springframework.util.Assert.isTrue(classificationTimeout > 0,
                "literature.ai.classification-timeout 必须大于0，不允许无限等待");
        this.resourceLoader = resourceLoader;
        this.timeout = timeout;
        this.classificationTimeout = classificationTimeout;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                // 确保使用 UTF-8 编码
                .addInterceptor(chain -> {
                    Request original = chain.request();
                    Request.Builder requestBuilder = original.newBuilder()
                            .header("Accept-Charset", "UTF-8");
                    return chain.proceed(requestBuilder.build());
                })
                .build();
        // 非流式分类需要等待完整结果；独立设置等待上限，并复用连接池和线程池。
        this.classificationHttpClient = httpClient.newBuilder()
                .readTimeout(classificationTimeout, TimeUnit.MILLISECONDS)
                .callTimeout(classificationTimeout, TimeUnit.MILLISECONDS)
                .build();
    }

    /**
     * 获取系统提示词（带缓存）
     */
    private String getSystemPrompt() {
        if (cachedSystemPrompt == null) {
            cachedSystemPrompt = loadPromptFromFile(systemPromptFile);
        }
        return cachedSystemPrompt;
    }

    /**
     * 获取分类提示词（带缓存）
     */
    private String getClassificationPrompt() {
        if (cachedClassificationPrompt == null) {
            cachedClassificationPrompt = loadPromptFromFile(classificationPromptFile);
        }
        return cachedClassificationPrompt;
    }

    /**
     * 从资源文件加载提示词内容
     */
    private String loadPromptFromFile(String filePath) {
        String resourcePath = filePath.replace("classpath:", "");

        // 使用 try-with-resources 确保流被正确关闭
        try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(resourcePath)) {

            if (inputStream == null) {
                log.error("无法在 classpath 中找到资源文件: {}", resourcePath);
                throw new BusinessException(ResultCode.ERROR, "系统提示词文件不存在: " + filePath);
            }

            // 使用 BufferedReader 逐行读取，效率更高
            try (InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                 BufferedReader bufferedReader = new BufferedReader(reader)) {

                StringBuilder contentBuilder = new StringBuilder();
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    contentBuilder.append(line).append(System.lineSeparator());
                }

                String content = contentBuilder.toString();

                if (StrUtil.isBlank(content)) {
                    throw new BusinessException(ResultCode.ERROR, "系统提示词文件内容为空: " + filePath);
                }

                log.info("成功加载系统提示词文件: {}, 内容长度: {}", filePath, content.length());
                return content;
            }

        } catch (IOException e) {
            log.error("读取系统提示词文件失败: {}", filePath, e);
            throw new BusinessException(ResultCode.ERROR, "读取系统提示词文件失败: " + e.getMessage());
        }
    }

    /**
     * 生成文献阅读指南（非流式，用于批量导入）
     *
     * @param apiKey      API Key
     * @param fileContent 文献内容
     * @return 生成的阅读指南内容
     */
    public String generateReadingGuide(String apiKey, String fileContent) {
        try {
            // 构建请求
            KimiChatRequest chatRequest = buildChatRequest(fileContent);
            // 设置为非流式
            chatRequest.setStream(false);
            String requestBody = JSONUtil.toJsonStr(chatRequest);

            log.info("发送 Kimi AI 非流式请求，模型: {}, 内容长度: {}", model, fileContent.length());

            // 构建 HTTP 请求（非流式）
            Request request = new Request.Builder()
                    .url(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Accept-Charset", "UTF-8")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json; charset=utf-8")))
                    .build();

            // 同步调用获取完整响应
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String errorMsg = "Kimi AI 请求失败: " + response.code() + " - " + response.message();
                    if (response.body() != null) {
                        errorMsg += " - " + response.body().string();
                    }
                    throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, errorMsg);
                }

                if (response.body() == null) {
                    throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "Kimi AI 响应体为空");
                }

                return readFinalContent(response);

            } catch (IOException e) {
                log.error("Kimi AI 请求异常", e);
                throw new BusinessException(ResultCode.NETWORK_ERROR, "AI 服务请求异常: " + e.getMessage());
            }

        } catch (Exception e) {
            log.error("生成阅读指南失败", e);
            if (e instanceof BusinessException) {
                throw (BusinessException) e;
            }
            throw new BusinessException(ResultCode.THIRD_PARTY_SERVICE_ERROR, "生成阅读指南失败: " + e.getMessage());
        }
    }

    /**
     * 生成文献阅读指南（SSE 流式响应）
     *
     * @param apiKey           API Key
     * @param fileContent      文献内容
     * @param sseEmitter       SSE 发射器
     * @param contentCollector 内容收集器（保留原始空白）
     */
    public CompletableFuture<String> generateReadingGuideStream(String apiKey, String fileContent,
                                                                 SseEmitter emitter, StringBuilder collector) {
        CompletableFuture<String> result = new CompletableFuture<>();
        // 在启动上游请求前绑定生命周期，保留传输异常类型，供 HTTP 边界决定是否还能发送事件。
        emitter.onError(error -> result.completeExceptionally(
                new AsyncRequestNotUsableException("客户端SSE连接异常，分析未完成", error)));
        emitter.onTimeout(() -> result.completeExceptionally(new BusinessException("文献分析超时")));
        emitter.onCompletion(() -> result.completeExceptionally(
                new AsyncRequestNotUsableException("客户端连接已关闭，分析未完成")));
        Thread.startVirtualThread(() -> {
            try {
                long startedAt = System.nanoTime();
                Request request = new Request.Builder()
                        .url(baseUrl + "/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Accept", "text/event-stream")
                        .post(RequestBody.create(JSONUtil.toJsonStr(buildChatRequest(fileContent)),
                                MediaType.parse("application/json; charset=utf-8")))
                        .build();
                log.info("发送AI流式请求，地址: {}, 模型: {}, 推理强度: {}, 原文长度: {}, 读取超时: {}ms",
                        request.url(), model, reasoningEffort, fileContent.length(), timeout);
                EventSource source = EventSources.createFactory(httpClient).newEventSource(request,
                        new EventSourceListener() {
                            private boolean stopped;
                            private boolean contentStarted;
                            private long reasoningChunks;
                            private long lastReasoningProgress;

                            @Override
                            public void onOpen(EventSource source, Response response) {
                                if (result.isDone()) return;
                                log.info("AI流式连接已建立，HTTP: {}, 耗时: {}ms", response.code(),
                                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                                try {
                                    emitter.send(SseEmitter.event().name("progress").data("AI连接已建立，等待模型响应..."));
                                } catch (IOException e) {
                                    log.error("发送AI连接进度失败", e);
                                    result.completeExceptionally(e);
                                }
                            }

                            @Override
                            public void onEvent(EventSource source, String id, String type, String data) {
                                if (result.isDone()) return;
                                try {
                                    if ("[DONE]".equals(data)) {
                                        if (!stopped || StrUtil.isBlank(collector.toString())) {
                                            throw new BusinessException("AI流式响应未完整结束或正文为空");
                                        }
                                        result.complete(collector.toString());
                                        return;
                                    }
                                    JSONObject chunk = JSONUtil.parseObj(data);
                                    if (chunk.containsKey("error")) {
                                        throw new BusinessException("AI返回错误：" + chunk.get("error"));
                                    }
                                    JSONObject choice = chunk.getJSONArray("choices").getJSONObject(0);
                                    String finishReason = choice.getStr("finish_reason");
                                    if (finishReason != null) {
                                        requireNormalFinish(finishReason);
                                        stopped = true;
                                    }
                                    JSONObject delta = choice.getJSONObject("delta");
                                    // 思考通过独立事件提供实时预览，不写入报告收集器或日志。
                                    String reasoning = delta.getStr("reasoning_content");
                                    if (StrUtil.isNotEmpty(reasoning)) {
                                        emitter.send(SseEmitter.event().name("reasoning")
                                                .data(new JSONObject().set("content", reasoning).toString()));
                                        reasoningChunks++;
                                        long now = System.nanoTime();
                                        if (!contentStarted && (reasoningChunks == 1
                                                || now - lastReasoningProgress >= TimeUnit.SECONDS.toNanos(5))) {
                                            long elapsed = TimeUnit.NANOSECONDS.toSeconds(now - startedAt);
                                            log.info("AI正在思考，已耗时: {}s，收到思考片段: {}", elapsed, reasoningChunks);
                                            emitter.send(SseEmitter.event().name("progress")
                                                    .data("AI正在思考，已耗时 " + elapsed + " 秒，已收到 " + reasoningChunks + " 个思考片段..."));
                                            lastReasoningProgress = now;
                                        }
                                    }
                                    String content = delta.getStr("content");
                                    if (content != null) {
                                        if (!content.isEmpty() && !contentStarted) {
                                            contentStarted = true;
                                            log.info("收到AI首段正文，耗时: {}ms",
                                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                                            emitter.send(SseEmitter.event().name("progress").data("AI正在生成精读报告..."));
                                        }
                                        collector.append(content);
                                        emitter.send(SseEmitter.event().name("content")
                                                .data(content.replace("\n", "<empty-line>")
                                                        .replace(" ", "<empty-space>")));
                                    }
                                } catch (Exception e) {
                                    log.error("AI流式响应处理失败", e);
                                    result.completeExceptionally(e);
                                }
                            }

                            @Override
                            public void onClosed(EventSource source) {
                                result.completeExceptionally(new BusinessException("AI连接提前关闭，未收到完整结束事件"));
                            }

                            @Override
                            public void onFailure(EventSource source, Throwable error, Response response) {
                                if (result.isDone()) return;
                                String message = "AI流式请求失败";
                                if (error instanceof java.net.SocketTimeoutException) {
                                    message += "：连续 " + timeout + "ms 未收到AI响应数据，读取超时";
                                }
                                try {
                                    if (response != null) {
                                        message += "：HTTP " + response.code();
                                        if (response.body() != null) message += " " + response.body().string();
                                    } else if (error != null) {
                                        message += "：" + error.getMessage();
                                    }
                                } catch (IOException e) {
                                    log.error("读取AI错误响应失败", e);
                                    message += "；读取错误响应失败：" + e.getMessage();
                                }
                                log.error(message, error);
                                result.completeExceptionally(new BusinessException(message));
                            }
                        });
                result.whenComplete((guide, error) -> {
                    source.cancel();
                    long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
                    if (error != null) {
                        log.error("AI流式生成失败，模型: {}, 耗时: {}ms，已收到正文长度: {}",
                                model, elapsed, collector.length(), error);
                    } else {
                        log.info("AI流式生成完成，模型: {}, 耗时: {}ms，正文长度: {}", model, elapsed, guide.length());
                    }
                });
            } catch (Exception e) {
                log.error("启动AI流式请求失败", e);
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void requireNormalFinish(String finishReason) {
        if ("length".equals(finishReason)) {
            throw new BusinessException("AI输出未完整生成，结束原因：length；思考与正文的总生成预算（"
                    + maxCompletionTokens + " token）已耗尽，当前推理强度：" + reasoningEffort
                    + "。请降低推理强度或提高生成预算后重新生成");
        }
        if (!"stop".equals(finishReason)) {
            throw new BusinessException("AI输出未完整生成，结束原因：" + finishReason);
        }
    }

    private String readFinalContent(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new BusinessException("AI请求失败：HTTP " + response.code() + " "
                    + (response.body() == null ? response.message() : response.body().string()));
        }
        if (response.body() == null) throw new BusinessException("AI响应体为空");
        JSONObject json = JSONUtil.parseObj(response.body().string());
        JSONObject choice = json.getJSONArray("choices").getJSONObject(0);
        requireNormalFinish(choice.getStr("finish_reason"));
        String content = choice.getJSONObject("message").getStr("content");
        if (StrUtil.isBlank(content)) throw new BusinessException("AI 返回内容为空");
        return content;
    }

    /** 只校验可验证的编号有效性，不把这项检查当作语义正确性认证。 */
    public void validateSourceReferences(String guide, SourceDocument document) {
        Set<String> ids = new HashSet<>();
        document.blocks().forEach(block -> ids.add(block.id()));
        var matcher = Pattern.compile("#source-(S[0-9]+)").matcher(guide);
        while (matcher.find()) {
            if (!ids.contains(matcher.group(1))) {
                throw new BusinessException("报告引用了不存在的原文片段：" + matcher.group(1));
            }
        }
    }

    /**
     * 构建聊天请求
     */
    private KimiChatRequest buildChatRequest(String fileContent) {
        KimiChatRequest request = new KimiChatRequest();
        request.setModel(model);
        request.setMaxCompletionTokens(maxCompletionTokens);
        request.setReasoningEffort(reasoningEffort);
        request.setStream(true);

        // 构建消息，使用从文件加载的系统提示词
        request.setMessages(Arrays.asList(
                new KimiChatRequest.Message("system", getSystemPrompt()),
                new KimiChatRequest.Message("user", "请为以下文献生成阅读指南：\n\n" + fileContent)
        ));

        return request;
    }

    public ClassificationResponse generateClassification(String apiKey, String readingGuide) {
        long startedAt = System.nanoTime();
        Request request = new Request.Builder()
                .url(baseUrl + "/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSONUtil.toJsonStr(buildClassificationRequest(readingGuide)),
                        MediaType.parse("application/json; charset=utf-8")))
                .build();
        log.info("发送AI分类请求，模型: {}, 报告长度: {}, 请求超时上限: {}ms",
                model, readingGuide.length(), classificationTimeout);
        try (Response response = classificationHttpClient.newCall(request).execute()) {
            JSONObject json = JSONUtil.parseObj(readFinalContent(response));
            if (!json.keySet().equals(Set.of("tags", "desc"))
                    || !(json.get("desc") instanceof String)
                    || !(json.get("tags") instanceof JSONArray)) {
                throw new BusinessException("AI分类格式错误，必须且仅包含tags数组和desc字符串");
            }
            var tags = json.getJSONArray("tags");
            if (tags.size() > 5 || tags.stream().anyMatch(tag -> !(tag instanceof String) || StrUtil.isBlank((String) tag))
                    || new HashSet<>(tags).size() != tags.size() || StrUtil.isBlank(json.getStr("desc")) || json.getStr("desc").length() > 200) {
                throw new BusinessException("AI分类不符合标签数量、类型、去重或描述长度要求");
            }
            ClassificationResponse classification = JSONUtil.toBean(json, ClassificationResponse.class);
            log.info("AI分类完成，模型: {}, 耗时: {}ms", model,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            return classification;
        } catch (IOException e) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            String message = "AI分类请求失败（模型: " + model + "，耗时: " + elapsed
                    + "ms，请求超时上限: " + classificationTimeout + "ms）：" + e.getMessage();
            log.error(message, e);
            throw new BusinessException(message, e);
        }
    }

    public void generateClassificationWithVirtualThread(String apiKey, String readingGuide, Long literatureId,
                                                        LiteratureService service) {
        Thread.startVirtualThread(() -> {
            try {
                ClassificationResponse result = generateClassification(apiKey, readingGuide);
                service.updateClassification(literatureId, result.getTags(), result.getDesc());
            } catch (Exception e) {
                log.error("文献分类失败，ID: {}", literatureId, e);
                service.updateFailure(literatureId, 3, e.getMessage());
            }
        });
    }

    /**
     * 构建分类请求
     */
    private KimiChatRequest buildClassificationRequest(String readingGuide) {
        KimiChatRequest request = new KimiChatRequest();
        request.setModel(model);
        // K3 始终开启思考，预算必须同时覆盖思考过程和最终 JSON。
        request.setMaxCompletionTokens(maxCompletionTokens);
        request.setReasoningEffort(reasoningEffort);
        request.setResponseFormat(
                KimiChatRequest.ResponseFormat.builder()
                        .type("json_object")
                        .build()
        );
        request.setStream(false); // 非流式响应

        // 构建消息，使用从文件加载的分类提示词
        request.setMessages(Arrays.asList(
                new KimiChatRequest.Message("system", getClassificationPrompt()),
                new KimiChatRequest.Message("user", "请为以下文献阅读指南生成分类和描述：\n\n" + readingGuide)
        ));

        return request;
    }

    /**
     * 验证 API Key
     */
    public void validateApiKey(String apiKey) {
        if (StrUtil.isBlank(apiKey)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "API Key 不能为空");
        }

        if (!apiKey.startsWith("sk-")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "API Key 格式错误");
        }
    }
}
