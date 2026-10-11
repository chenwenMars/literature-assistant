package com.literature;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:literature_deletion;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
@Transactional
class LiteratureDeletionTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LiteratureService literatureService;
    @TempDir
    Path directory;

    @Test
    void deletionHidesRecordFromListDetailAndDownloadAndRetainsOriginal() throws Exception {
        Path file = Files.writeString(directory.resolve("test.md"), "original content");
        jdbc.update("INSERT INTO literature (id, original_name, file_path, file_size, file_type) VALUES (?, ?, ?, ?, ?)",
                91001L, "delete-test.md", file.toString(), Files.size(file), "md");

        mvc.perform(delete("/literature/91001"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertEquals(1, jdbc.queryForObject("SELECT deleted FROM literature WHERE id = 91001", Integer.class));
        assertEquals("original content", Files.readString(file));

        mvc.perform(post("/literature/page").contentType("application/json")
                        .content("{\"keyword\":\"delete-test\"}"))
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(get("/literature/91001"))
                .andExpect(jsonPath("$.code").value(6001));
        mvc.perform(get("/literature/91001/download"))
                .andExpect(jsonPath("$.code").value(6001));
        mvc.perform(delete("/literature/91001"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(6001));

        // 后台生成任务的迟到更新不能使已删除记录重新出现在列表。
        literatureService.updateReadingGuide(91001L, "late guide");
        assertEquals(1, jdbc.queryForObject("SELECT deleted FROM literature WHERE id = 91001", Integer.class));
    }

    @Test
    void missingRecordReturnsFailure() throws Exception {
        mvc.perform(delete("/literature/91002"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(6001));
    }

    @Test
    void invalidIdIsRejectedAtBoundary() throws Exception {
        mvc.perform(delete("/literature/0"))
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(delete("/literature/-1"))
                .andExpect(jsonPath("$.success").value(false));
    }
}
