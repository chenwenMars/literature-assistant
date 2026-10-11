package com.literature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LiteratureNoteRequest {
    public enum Category { WRITING, METHOD, IDEA, MATERIAL }

    @NotBlank @Size(max = 200)
    private String title;
    @NotNull
    private Category category;
    @NotBlank @Size(max = 500)
    private String sourceLocation;
    @NotBlank @Size(max = 20000)
    private String excerpt;
    @NotNull @Size(max = 20000)
    private String reflection;
}
