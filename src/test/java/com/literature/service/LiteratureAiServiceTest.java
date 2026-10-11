package com.literature.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import com.literature.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiteratureAiServiceTest {

    private HttpServer server;
    private LiteratureAiService service;
    private final BlockingQueue<JSONObject> requests = new LinkedBlockingQueue<>();
    private volatile int responseStatus = 200;
    private volatile String responseType = "application/json";
    private volatile String responseBody;
    private volatile long responseDelayMillis;
    private volatile boolean holdResponseOpen;
    private volatile List<String> responsePieces = List.of();
    private final CountDownLatch releaseResponse = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(JSONUtil.parseObj(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                try {
                    releaseResponse.await(responseDelayMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("测试响应被中断", e);
                }
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", responseType);
                exchange.sendResponseHeaders(responseStatus, holdResponseOpen ? 0 : bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.getResponseBody().flush();
                for (String piece : responsePieces) {
                    exchange.getResponseBody().write(piece.getBytes(StandardCharsets.UTF_8));
                    exchange.getResponseBody().flush();
                    try {
                        releaseResponse.await(150, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("测试响应被中断", e);
                    }
                }
                if (holdResponseOpen) {
                    try {
                        releaseResponse.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("测试响应被中断", e);
                    }
                }
            }
        });
        server.start();

        configureService(60000);
    }

    private void configureService(int readTimeout) {
        service = new LiteratureAiService(new DefaultResourceLoader(), readTimeout, 1000);
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        ReflectionTestUtils.setField(service, "model", "kimi-k3");
        ReflectionTestUtils.setField(service, "maxCompletionTokens", 20480);
        ReflectionTestUtils.setField(service, "reasoningEffort", "high");
        ReflectionTestUtils.setField(service, "systemPromptFile", "classpath:prompts/literature-guide-system-prompt.txt");
        ReflectionTestUtils.setField(service, "classificationPromptFile", "classpath:prompts/literature-classification-system-prompt.txt");
    }

    @AfterEach
    void tearDown() {
        releaseResponse.countDown();
        server.stop(0);
    }

    @Test
    void streamingSendsReasoningSeparatelyBeforeFinalContent() throws Exception {
        responseType = "text/event-stream";
        holdResponseOpen = true;
        String firstReasoning = "内部思考\n\n  核对\"证据\" <empty-line> <empty-space>";
        responseBody = "data: " + new JSONObject().set("choices", List.of(new JSONObject()
                .set("delta", new JSONObject().set("reasoning_content", firstReasoning)))) + "\n\n"
                + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"继续思考\"}}]}\n\n";
        SseEmitter emitter = mock(SseEmitter.class);
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        BlockingQueue<String> reasoning = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            SseEmitter.SseEventBuilder event = invocation.getArgument(0);
            StringBuilder wire = new StringBuilder();
            event.build().forEach(part -> wire.append(part.getData()));
            events.add(wire.toString());
            if (wire.toString().startsWith("event:reasoning\n")) {
                String payload = wire.toString().substring("event:reasoning\ndata:".length()).trim();
                reasoning.add(JSONUtil.parseObj(payload).getStr("content"));
            }
            return null;
        }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        StringBuilder collector = new StringBuilder();
        var result = service.generateReadingGuideStream("sk-test", "文献正文", emitter, collector);
        try {
            assertEquals(firstReasoning, reasoning.poll(3, TimeUnit.SECONDS));
            assertEquals("继续思考", reasoning.poll(3, TimeUnit.SECONDS));
            String delivered = String.join("", events);
            assertTrue(delivered.contains("AI连接已建立"));
            assertTrue(delivered.contains("AI正在思考"));
            assertFalse(events.stream().anyMatch(event -> event.startsWith("event:content\n")));
            assertEquals("", collector.toString());
            assertFalse(result.isDone());
        } finally {
            result.cancel(true);
        }
    }

    @Test
    void stalledStreamFailsAtConfiguredReadTimeout() throws Exception {
        responseType = "text/event-stream";
        holdResponseOpen = true;
        responseBody = ": connected\n\n";
        configureService(200);
        var result = service.generateReadingGuideStream("sk-test", "文献正文", mock(SseEmitter.class), new StringBuilder());
        try {
            takeRequest();
            ExecutionException error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(error.getCause().getMessage().contains("超时"));
        } finally {
            result.cancel(true);
        }
    }

    @Test
    void clientErrorPreservesTransportCauseAndCancelsUpstream() throws Exception {
        responseType = "text/event-stream";
        holdResponseOpen = true;
        responseBody = ": connected\n\n";
        SseEmitter emitter = mock(SseEmitter.class);
        var callbacks = new LinkedBlockingQueue<java.util.function.Consumer<Throwable>>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return null;
        }).when(emitter).onError(any());
        var result = service.generateReadingGuideStream("sk-test", "原文", emitter, new StringBuilder());
        takeRequest();
        var disconnected = new java.io.IOException("Broken pipe");
        callbacks.remove().accept(disconnected);

        var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        assertInstanceOf(AsyncRequestNotUsableException.class, failure.getCause());
        assertSame(disconnected, failure.getCause().getCause());
        var client = (okhttp3.OkHttpClient) ReflectionTestUtils.getField(service, "httpClient");
        org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS)
                .until(() -> client.dispatcher().runningCallsCount() == 0);
        verify(emitter, never()).complete();
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void clientCompletionBeforeAiFinishesIsTransportFailure() throws Exception {
        responseType = "text/event-stream";
        holdResponseOpen = true;
        responseBody = ": connected\n\n";
        SseEmitter emitter = mock(SseEmitter.class);
        var callbacks = new LinkedBlockingQueue<Runnable>();
        doAnswer(invocation -> {
            callbacks.add(invocation.getArgument(0));
            return null;
        }).when(emitter).onCompletion(any());
        var result = service.generateReadingGuideStream("sk-test", "原文", emitter, new StringBuilder());
        takeRequest();
        callbacks.remove().run();

        var failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
        assertInstanceOf(AsyncRequestNotUsableException.class, failure.getCause());
        var client = (okhttp3.OkHttpClient) ReflectionTestUtils.getField(service, "httpClient");
        org.awaitility.Awaitility.await().atMost(3, TimeUnit.SECONDS)
                .until(() -> client.dispatcher().runningCallsCount() == 0);
    }

    @Test
    void continuousReasoningCanOutlastReadTimeoutAndStillComplete() throws Exception {
        responseType = "text/event-stream";
        responseBody = "";
        String reasoning = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"内部思考\"}}]}\n\n";
        responsePieces = new java.util.ArrayList<>(java.util.Collections.nCopies(10, reasoning));
        responsePieces.add("data: {\"choices\":[{\"delta\":{\"content\":\"完整报告\"}}]}\n\n");
        responsePieces.add("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
        configureService(1000);
        long startedAt = System.nanoTime();
        var result = service.generateReadingGuideStream("sk-test", "原文", mock(SseEmitter.class), new StringBuilder());

        assertEquals("完整报告", result.get(5, TimeUnit.SECONDS));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) > 1000);
    }

    @Test
    void stalledNonStreamingResponseAlsoUsesConfiguredReadTimeout() {
        holdResponseOpen = true;
        responseBody = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"未关闭的响应\"}}]}";
        configureService(200);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.generateReadingGuide("sk-test", "原文"));
        // JDK socket与Okio超时分别使用“Read timed out”和“timeout”。
        assertTrue(error.getMessage().matches("(?is).*(timeout|timed out).*"), error.getMessage());
    }

    @Test
    void readingGuideSendsK3ParametersAndReturnsOnlyFinalContent() throws Exception {
        responseBody = """
                {"choices":[{"finish_reason":"stop","message":{"reasoning_content":"内部思考","content":"阅读指南"}}]}
                """;

        assertEquals("阅读指南", service.generateReadingGuide("sk-test", "文献正文"));

        JSONObject request = takeRequest();
        assertK3Parameters(request);
        assertFalse(request.getBool("stream"));
        assertTrue(request.getJSONArray("messages").getJSONObject(1).getStr("content").contains("文献正文"));
    }

    @Test
    void classificationSendsK3ParametersAndKeepsJsonMode() throws Exception {
        String classification = "{\"tags\":[\"机器学习\"],\"desc\":\"文献描述\"}";
        JSONObject message = new JSONObject().set("reasoning_content", "内部思考").set("content", classification);
        responseBody = new JSONObject().set("choices", List.of(new JSONObject().set("finish_reason", "stop").set("message", message))).toString();
        LiteratureService literatureService = mock(LiteratureService.class);

        service.generateClassificationWithVirtualThread("sk-test", "阅读指南", 1L, literatureService);

        verify(literatureService, timeout(5000)).updateClassification(1L, List.of("机器学习"), "文献描述");
        JSONObject request = takeRequest();
        assertK3Parameters(request);
        assertFalse(request.getBool("stream"));
        assertEquals("json_object", request.getJSONObject("response_format").getStr("type"));
        assertTrue(request.getJSONArray("messages").getJSONObject(0).getStr("content").contains("JSON"));
    }

    @Test
    void classificationCanWaitLongerThanStreamingReadTimeout() throws Exception {
        configureService(100);
        responseDelayMillis = 400;
        responseBody = new JSONObject().set("choices", List.of(new JSONObject()
                .set("finish_reason", "stop").set("message", new JSONObject()
                        .set("content", "{\"tags\":[\"项目管理\"],\"desc\":\"案例研究\"}")))).toString();

        var classification = service.generateClassification("sk-test", "完整报告");

        assertEquals(List.of("项目管理"), classification.getTags());
        assertEquals("案例研究", classification.getDesc());
        assertFalse(takeRequest().getBool("stream"));
    }

    @Test
    void classificationTimeoutPreservesCauseAndReportsItsOwnLimit() {
        configureService(100);
        holdResponseOpen = true;
        responseBody = " ";

        var error = assertThrows(BusinessException.class,
                () -> service.generateClassification("sk-test", "完整报告"));

        assertInstanceOf(java.io.IOException.class, error.getCause());
        assertTrue(error.getMessage().contains("1000ms"), error.getMessage());
        assertTrue(error.getMessage().contains("kimi-k3"), error.getMessage());
    }

    @Test
    void classificationTotalTimeoutIsEnforcedEvenWhenResponseKeepsSendingData() {
        configureService(100);
        holdResponseOpen = true;
        responseBody = " ";
        responsePieces = java.util.Collections.nCopies(20, " ");
        long startedAt = System.nanoTime();

        var error = assertThrows(BusinessException.class,
                () -> service.generateClassification("sk-test", "完整报告"));

        assertInstanceOf(java.io.InterruptedIOException.class, error.getCause());
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 2000,
                "持续收到数据也不能无限延长分类请求");
    }

    @Test
    void classificationTimeoutCannotDisableTheTimeLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new LiteratureAiService(new DefaultResourceLoader(), 60000, 0));
    }

    @Test
    void streamingIgnoresReasoningAndPreservesFinalContentWhitespace() throws Exception {
        responseType = "text/event-stream";
        responseBody = """
                data: {"choices":[{"delta":{"reasoning_content":"内部思考"}}]}

                data: {"choices":[{"delta":{"content":"阅读 "}}]}

                data: {"choices":[{"delta":{"content":"指南\\n"}}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        SseEmitter emitter = mock(SseEmitter.class);
        StringBuilder collector = new StringBuilder();
        var result = service.generateReadingGuideStream("sk-test", "文献正文", emitter, collector);
        assertEquals("阅读 指南\n", result.get(5, TimeUnit.SECONDS));
        assertEquals("阅读 指南\n", collector.toString());
        verify(emitter, never()).complete();
        JSONObject request = takeRequest();
        assertK3Parameters(request);
        assertTrue(request.getBool("stream"));
    }

    @Test
    void readingGuidePropagatesApiErrors() {
        responseStatus = 400;
        responseBody = "{\"error\":{\"message\":\"invalid request\"}}";

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.generateReadingGuide("sk-test", "文献正文"));

        assertTrue(error.getMessage().contains("400"));
        assertTrue(error.getMessage().contains("invalid request"));
    }

    @Test
    void reasoningWithoutFinalContentIsNotTreatedAsSuccess() {
        responseBody = """
                {"choices":[{"finish_reason":"stop","message":{"reasoning_content":"内部思考","content":""}}]}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.generateReadingGuide("sk-test", "文献正文"));

        assertTrue(error.getMessage().contains("AI 返回内容为空"));
    }

    @Test
    void truncatedReadingGuideFailsInsteadOfReturningPartialText() {
        responseBody = """
                {"choices":[{"finish_reason":"length","message":{"content":"不完整正文"}}]}
                """;
        String message = assertThrows(BusinessException.class,
                () -> service.generateReadingGuide("sk-test", "原文")).getMessage();
        assertTrue(message.contains("length"));
        assertTrue(message.contains("20480 token"));
        assertTrue(message.contains("推理强度：high"));
    }

    @Test
    void streamWithoutDoneAndTruncatedStreamBothFail() throws Exception {
        responseType = "text/event-stream";
        for (String finish : List.of("stop", "length")) {
            responseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"部分正文\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\n";
            var result = service.generateReadingGuideStream("sk-test", "原文", mock(SseEmitter.class), new StringBuilder());
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void classificationFailureHasExplicitFailureStatus() {
        responseStatus = 503;
        responseBody = "service unavailable";
        LiteratureService persistence = mock(LiteratureService.class);
        service.generateClassificationWithVirtualThread("sk-test", "报告", 7L, persistence);
        verify(persistence, timeout(5000)).updateFailure(eq(7L), eq(3), contains("503"));
        verify(persistence, never()).updateStatus(anyLong(), eq(1));
    }

    @Test
    void malformedClassificationAndUnknownSourceReferencesAreRejected() {
        responseBody = new JSONObject().set("choices", List.of(new JSONObject().set("finish_reason", "stop")
                .set("message", new JSONObject().set("content", "{\"tags\":[123],\"desc\":\"描述\"}")))).toString();
        assertThrows(BusinessException.class, () -> service.generateClassification("sk-test", "报告"));
        var source = new com.literature.dto.SourceDocument(
                List.of(new com.literature.dto.SourceDocument.Block("S1", "位置", "原文")), List.of());
        assertDoesNotThrow(() -> service.validateSourceReferences("依据[S1](#source-S1)", source));
        assertThrows(BusinessException.class, () -> service.validateSourceReferences("[S9](#source-S9)", source));
    }

    private JSONObject takeRequest() throws InterruptedException {
        JSONObject request = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "应向本地 HTTP 测试服务发送请求");
        return request;
    }

    private void assertK3Parameters(JSONObject request) {
        assertEquals("kimi-k3", request.getStr("model"));
        assertEquals(20480, request.getInt("max_completion_tokens"));
        assertEquals("high", request.getStr("reasoning_effort"));
        for (String unsupported : List.of("max_tokens", "maxTokens", "maxCompletionTokens", "reasoningEffort",
                "temperature", "top_p", "n", "presence_penalty", "frequency_penalty")) {
            assertFalse(request.containsKey(unsupported), "不应发送参数 " + unsupported);
        }
    }
}
