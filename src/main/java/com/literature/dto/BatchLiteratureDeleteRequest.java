package com.literature.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量逻辑删除请求，限制为当前页最多 100 条文献。
 */
@Data
public class BatchLiteratureDeleteRequest {

    @NotEmpty(message = "请选择需要删除的文献")
    @Size(max = 100, message = "每次最多删除100条文献")
    private List<@NotNull(message = "文献ID不能为空") @Positive(message = "文献ID必须为正整数") Long> ids;
}
