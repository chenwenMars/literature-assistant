package com.literature.dto;

import java.util.List;
import java.util.stream.Collectors;

/** 原文解析快照。编号表示提取位置，不代表作者的章节或印刷页码。 */
public record SourceDocument(List<Block> blocks, List<String> warnings) {
    public record Block(String id, String label, String text) {}

    public String toPromptText() {
        return "材料解析说明：\n" + String.join("\n", warnings) + "\n\n"
                + blocks.stream().map(block -> "[" + block.id() + "] " + block.label()
                + "\n" + block.text()).collect(Collectors.joining("\n\n"));
    }
}
