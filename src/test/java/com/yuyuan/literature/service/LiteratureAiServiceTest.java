package com.yuyuan.literature.service;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import com.yuyuan.literature.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
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

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                requests.add(JSONUtil.parseObj(new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", responseType);
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();

        service = new LiteratureAiService(new DefaultResourceLoader());
        ReflectionTestUtils.setField(service, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        ReflectionTestUtils.setField(service, "model", "kimi-k3");
        ReflectionTestUtils.setField(service, "maxCompletionTokens", 20480);
        ReflectionTestUtils.setField(service, "reasoningEffort", "max");
        ReflectionTestUtils.setField(service, "systemPromptFile", "classpath:prompts/literature-guide-system-prompt.txt");
        ReflectionTestUtils.setField(service, "classificationPromptFile", "classpath:prompts/literature-classification-system-prompt.txt");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void readingGuideSendsK3ParametersAndReturnsOnlyFinalContent() throws Exception {
        responseBody = """
                {"choices":[{"message":{"reasoning_content":"内部思考","content":"阅读指南"}}]}
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
        responseBody = new JSONObject().set("choices", List.of(new JSONObject().set("message", message))).toString();
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
        CountDownLatch completed = new CountDownLatch(1);
        doAnswer(invocation -> {
            completed.countDown();
            return null;
        }).when(emitter).complete();
        StringBuilder collector = new StringBuilder();

        service.generateReadingGuideStream("sk-test", "文献正文", emitter, collector);

        assertTrue(completed.await(5, TimeUnit.SECONDS), "流式请求应正常完成");
        assertEquals("阅读 指南\n", collector.toString());
        verify(emitter, never()).completeWithError(any());
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
                {"choices":[{"message":{"reasoning_content":"内部思考","content":""}}]}
                """;

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.generateReadingGuide("sk-test", "文献正文"));

        assertTrue(error.getMessage().contains("AI 返回内容为空"));
    }

    private JSONObject takeRequest() throws InterruptedException {
        JSONObject request = requests.poll(5, TimeUnit.SECONDS);
        assertNotNull(request, "应向本地 HTTP 测试服务发送请求");
        return request;
    }

    private void assertK3Parameters(JSONObject request) {
        assertEquals("kimi-k3", request.getStr("model"));
        assertEquals(20480, request.getInt("max_completion_tokens"));
        assertEquals("max", request.getStr("reasoning_effort"));
        for (String unsupported : List.of("max_tokens", "maxTokens", "maxCompletionTokens", "reasoningEffort",
                "temperature", "top_p", "n", "presence_penalty", "frequency_penalty")) {
            assertFalse(request.containsKey(unsupported), "不应发送参数 " + unsupported);
        }
    }
}
