package com.literature;

import com.literature.controller.LiteratureController;
import com.literature.common.handler.GlobalExceptionHandler;
import com.literature.dto.ClassificationResponse;
import com.literature.dto.SourceDocument;
import com.literature.service.FileProcessingService;
import com.literature.service.LiteratureAiService;
import com.literature.service.LiteratureNoteService;
import com.literature.service.LiteratureService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class LiteratureGenerationTest {
    private final FileProcessingService files = mock(FileProcessingService.class);
    private final LiteratureAiService ai = mock(LiteratureAiService.class);
    private final LiteratureService persistence = mock(LiteratureService.class);
    private final LiteratureController controller = new LiteratureController(files, ai, persistence, mock(LiteratureNoteService.class));
    private final MockMultipartFile file = new MockMultipartFile("file", "case.md", "text/plain", "研究材料".getBytes());
    private final SourceDocument source = new SourceDocument(List.of(new SourceDocument.Block("S1", "正文", "研究材料")), List.of());

    private void prepare(CompletableFuture<String> result) {
        when(files.saveFile(file)).thenReturn("case.md");
        when(files.extractDocument("case.md")).thenReturn(source);
        when(persistence.createLiterature(eq(file), eq("case.md"), anyInt())).thenReturn(99L);
        when(ai.generateReadingGuideStream(eq("sk-test"), anyString(), any(), any())).thenReturn(result);
    }

    @Test
    void failedStreamNeverPersistsPartialGuideOrStartsClassification() {
        var future = new CompletableFuture<String>();
        prepare(future);
        controller.generateReadingGuide(file, "sk-test");
        future.completeExceptionally(new IllegalStateException("断流"));
        verify(persistence, timeout(5000)).updateFailure(eq(99L), eq(2), contains("断流"));
        verify(persistence, never()).updateReadingGuide(anyLong(), anyString());
        verify(ai, never()).generateClassification(anyString(), anyString());
    }

    @Test
    void disconnectedClientDoesNotReceiveAnotherErrorEvent() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        var error = new AsyncRequestNotUsableException("客户端连接已断开");

        ReflectionTestUtils.invokeMethod(controller, "sendAnalysisFailure", emitter, error);

        verifyNoInteractions(emitter);
    }

    @Test
    void disconnectedStreamPersistsFailureAndDoesNotWriteJsonDuringAsyncDispatch() throws Exception {
        var future = new CompletableFuture<String>();
        prepare(future);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8)).build();
        var request = mvc.perform(multipart("/literature/generate-guide").file(file).param("apiKey", "sk-test"))
                .andExpect(request().asyncStarted()).andReturn();
        var emitter = org.mockito.ArgumentCaptor.forClass(SseEmitter.class);
        verify(ai).generateReadingGuideStream(eq("sk-test"), anyString(), emitter.capture(), any());
        String eventsBeforeDisconnect = request.getResponse().getContentAsString(StandardCharsets.UTF_8);
        var error = new AsyncRequestNotUsableException("ServletOutputStream failed to flush: Broken pipe");
        // 模拟容器通知断连后的最终 ASYNC dispatch；MockMvc 本身没有真实网络连接。
        emitter.getValue().completeWithError(error);
        future.completeExceptionally(error);

        verify(persistence, timeout(5000)).updateFailure(eq(99L), eq(2), contains("Broken pipe"));
        var resolverLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver.class);
        var resolverLogs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        resolverLogs.start();
        resolverLogger.addAppender(resolverLogs);
        org.springframework.test.web.servlet.MvcResult response;
        try {
            response = mvc.perform(asyncDispatch(request)).andReturn();
            assertTrue(resolverLogs.list.stream().noneMatch(event ->
                    event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN)),
                    "处理断连异常不应触发异常处理器自身的转换错误");
        } finally {
            resolverLogger.detachAppender(resolverLogs);
            resolverLogs.stop();
        }
        org.junit.jupiter.api.Assertions.assertSame(error, response.getResolvedException());
        org.junit.jupiter.api.Assertions.assertEquals(eventsBeforeDisconnect,
                response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        verify(persistence, never()).updateReadingGuide(anyLong(), anyString());
        verify(ai, never()).generateClassification(anyString(), anyString());
    }

    @Test
    void invalidApiKeyIsReportedAsSseErrorWithoutJsonErrorDispatch() throws Exception {
        doThrow(new IllegalArgumentException("API Key格式错误")).when(ai).validateApiKey("invalid");
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8)).build();
        var request = mvc.perform(multipart("/literature/generate-guide").file(file).param("apiKey", "invalid"))
                .andExpect(request().asyncStarted()).andReturn();
        String events = mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(events.contains("event:error"), events);
        assertTrue(events.contains("API Key格式错误"), events);
        assertFalse(events.contains("系统异常"), events);
        verifyNoInteractions(files, persistence);
    }

    @Test
    void successfulGuideIsSavedBeforeClassificationFailureIsExposed() {
        var future = new CompletableFuture<String>();
        prepare(future);
        when(ai.generateClassification("sk-test", "完整报告")).thenThrow(new IllegalStateException("分类失败"));
        controller.generateReadingGuide(file, "sk-test");
        future.complete("完整报告");
        verify(persistence, timeout(5000)).updateFailure(eq(99L), eq(3), contains("分类失败"));
        var order = inOrder(ai, persistence);
        order.verify(ai).validateSourceReferences("完整报告", source);
        order.verify(persistence).updateReadingGuide(99L, "完整报告");
        order.verify(ai).generateClassification("sk-test", "完整报告");
        verify(persistence, never()).updateClassification(anyLong(), anyList(), anyString());
    }

    @Test
    void completedGenerationPersistsBothGuideAndClassification() {
        var future = new CompletableFuture<String>();
        prepare(future);
        ClassificationResponse classification = new ClassificationResponse();
        classification.setTags(List.of("项目管理"));
        classification.setDesc("案例研究");
        when(ai.generateClassification("sk-test", "完整报告")).thenReturn(classification);
        controller.generateReadingGuide(file, "sk-test");
        future.complete("完整报告");
        verify(persistence, timeout(5000)).updateClassification(99L, List.of("项目管理"), "案例研究");
        verify(persistence, never()).updateFailure(anyLong(), anyInt(), anyString());
    }

    @Test
    void closedConnectionDoesNotReclassifySavedReportAsFailure() {
        var future = new CompletableFuture<String>();
        prepare(future);
        ClassificationResponse classification = new ClassificationResponse();
        classification.setTags(List.of("项目管理"));
        classification.setDesc("案例研究");
        when(ai.generateClassification("sk-test", "完整报告")).thenReturn(classification);
        var emitter = controller.generateReadingGuide(file, "sk-test");
        emitter.complete();
        future.complete("完整报告");
        verify(persistence, timeout(5000)).updateClassification(99L, List.of("项目管理"), "案例研究");
        verify(persistence, after(300).never()).updateFailure(anyLong(), anyInt(), anyString());
    }

    @Test
    void upstreamReadTimeoutReachesBrowserAsSseErrorAndPersistsFailure() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch release = new CountDownLatch(1);
        upstream.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().write(("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"核对原文证据\\n\"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("测试响应被中断", e);
                }
            }
        });
        upstream.start();
        try {
            LiteratureAiService realAi = new LiteratureAiService(new DefaultResourceLoader(), 500, 1000);
            ReflectionTestUtils.setField(realAi, "baseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(realAi, "model", "kimi-k3");
            ReflectionTestUtils.setField(realAi, "maxCompletionTokens", 20480);
            ReflectionTestUtils.setField(realAi, "reasoningEffort", "high");
            ReflectionTestUtils.setField(realAi, "systemPromptFile", "classpath:prompts/literature-guide-system-prompt.txt");
            prepare(new CompletableFuture<>());
            var mvc = MockMvcBuilders.standaloneSetup(new LiteratureController(
                    files, realAi, persistence, mock(LiteratureNoteService.class)))
                    .setMessageConverters(new StringHttpMessageConverter(StandardCharsets.UTF_8)).build();
            var request = mvc.perform(multipart("/literature/generate-guide").file(file).param("apiKey", "sk-test"))
                    .andExpect(request().asyncStarted()).andReturn();
            request.getAsyncResult(5000);
            String events = mvc.perform(asyncDispatch(request)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertTrue(events.contains("AI连接已建立"), events);
            assertTrue(events.contains("event:reasoning\ndata:{\"content\":\"核对原文证据\\n\"}"), events);
            assertTrue(events.contains("event:error"));
            assertTrue(events.contains("读取超时"));
            verify(persistence).updateFailure(eq(99L), eq(2), contains("读取超时"));
            verify(persistence, never()).updateReadingGuide(anyLong(), anyString());
            verify(persistence, never()).updateClassification(anyLong(), anyList(), anyString());
        } finally {
            release.countDown();
            upstream.stop(0);
        }
    }
}
