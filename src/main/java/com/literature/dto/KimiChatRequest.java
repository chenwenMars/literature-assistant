package com.literature.dto;

import cn.hutool.core.annotation.Alias;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Kimi AI 聊天请求 DTO
 *
 * @author Literature Assistant
 * @since 1.0.0
 */
@Data
public class KimiChatRequest {

    /**
     * 模型名称
     */
    private String model;

    /**
     * 消息列表
     */
    private List<Message> messages;

    /**
     * 总生成预算，包含思考 token 和最终回答 token
     */
    @Alias("max_completion_tokens")
    private Integer maxCompletionTokens;

    /**
     * 响应格式
     */
    @Alias("response_format")
    private ResponseFormat responseFormat;

    /**
     * K3 推理强度：low、high、max。采样参数固定，不发送 temperature。
     */
    @Alias("reasoning_effort")
    private String reasoningEffort;

    /**
     * 是否流式响应
     */
    private Boolean stream;

    @Builder
    @Data
    public static class ResponseFormat {
        private String type;
    }

    /**
     * 消息对象
     */
    @Data
    public static class Message {
        /**
         * 角色：system、user、assistant
         */
        private String role;

        /**
         * 消息内容
         */
        private String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
