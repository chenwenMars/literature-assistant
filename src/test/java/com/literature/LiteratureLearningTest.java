package com.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.literature.dto.SourceDocument;
import com.literature.service.LiteratureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:literature_learning;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
@Transactional
class LiteratureLearningTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired LiteratureService service;
    @TempDir Path directory;

    private void insert(long id) throws Exception {
        Path file = Files.writeString(directory.resolve(id + ".md"), "# 原文章节\n\n项目记录与访谈分析");
        jdbc.update("INSERT INTO literature(id, original_name, file_path, file_size, file_type, reading_guide) VALUES (?,?,?,?,?,?)",
                id, "学习论文.md", file.toString(), Files.size(file), "md", "历史报告");
    }

    @Test
    void sourceSnapshotIsPersistedWithoutChangingHistoricalGuide() throws Exception {
        insert(93001);
        mvc.perform(get("/literature/93001/source")).andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/literature/93001/source"))
                .andExpect(jsonPath("$.data.blocks[0].id").value("S1"))
                .andExpect(jsonPath("$.data.blocks[0].text").value("# 原文章节"));
        mvc.perform(get("/literature/93001/source"))
                .andExpect(jsonPath("$.data.blocks[1].text").value("项目记录与访谈分析"));
        assertEquals("历史报告", jdbc.queryForObject("SELECT reading_guide FROM literature WHERE id=93001", String.class));
        assertNull(jdbc.queryForObject("SELECT report_version FROM literature WHERE id=93001", String.class));
        mvc.perform(get("/literature/93001")).andExpect(jsonPath("$.data.sourceAvailable").value(true));
        var source = new SourceDocument(List.of(new SourceDocument.Block("S1", "PDF文件第1页", "证据")), List.of("未OCR"));
        service.saveSourceDocument(93001L, source);
        assertEquals(source, service.getSourceDocument(93001L));
    }

    @Test
    void notesPersistEditDeleteAndRejectCrossDocumentAccess() throws Exception {
        insert(93002);
        insert(93003);
        String payload = """
                {"title":"问题提出","category":"WRITING","sourceLocation":"第1章 / S1",
                 "excerpt":"从现象收束到问题","reflection":"需要先收集管理记录"}
                """;
        String result = mvc.perform(post("/literature/93002/notes").contentType("application/json").content(payload))
                .andExpect(jsonPath("$.success").value(true)).andReturn().getResponse().getContentAsString();
        long noteId = json.readTree(result).path("data").path("id").asLong();
        mvc.perform(get("/literature/93002/notes"))
                .andExpect(jsonPath("$.data[0].reflection").value("需要先收集管理记录"));
        mvc.perform(put("/literature/93003/notes/" + noteId).contentType("application/json").content(payload))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(delete("/literature/93003/notes/" + noteId)).andExpect(jsonPath("$.success").value(false));
        mvc.perform(put("/literature/93002/notes/" + noteId).contentType("application/json")
                        .content(payload.replace("需要先收集管理记录", "先核对来源")))
                .andExpect(jsonPath("$.data.reflection").value("先核对来源"));
        mvc.perform(delete("/literature/93002/notes/" + noteId)).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/literature/93002/notes")).andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void invalidNotesAndDeletedLiteratureFailAtBoundary() throws Exception {
        insert(93004);
        mvc.perform(post("/literature/93004/notes").contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/literature/93004/notes").contentType("application/json").content("{\"category\":\"INVALID\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(delete("/literature/93004"));
        mvc.perform(get("/literature/93004/notes")).andExpect(jsonPath("$.success").value(false));
        mvc.perform(post("/literature/93004/source")).andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void classificationFailureRemainsVisibleAlongsideUsableGuide() throws Exception {
        insert(93005);
        service.updateReadingGuide(93005L, "完整精读报告");
        service.updateFailure(93005L, 3, "分类请求失败");
        mvc.perform(get("/literature/93005"))
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.errorMessage").value("分类请求失败"))
                .andExpect(jsonPath("$.data.reportVersion").value("mba-reading-v2"))
                .andExpect(jsonPath("$.data.readingGuideSummary").value("完整精读报告"));
        service.updateClassification(93005L, List.of("项目管理"), "案例研究");
        mvc.perform(get("/literature/93005"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.tags[0]").value("项目管理"))
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist());
    }
}
