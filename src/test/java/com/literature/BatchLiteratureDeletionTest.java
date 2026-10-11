package com.literature;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:batch_literature_deletion;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always"
})
@AutoConfigureMockMvc
class BatchLiteratureDeletionTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @TempDir
    Path directory;

    @BeforeEach
    void createLiteratures() throws Exception {
        for (long id : new long[]{92001, 92002, 92003}) {
            Path file = Files.writeString(directory.resolve(id + ".md"), "original content");
            jdbc.update("INSERT INTO literature (id, original_name, file_path, file_size, file_type) VALUES (?, ?, ?, ?, ?)",
                    id, "batch-delete-test-" + id + ".md", file.toString(), Files.size(file), "md");
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM literature WHERE id IN (92001, 92002, 92003)");
    }

    @Test
    void batchDeletionHidesSelectedRecordsAndRetainsFilesAndUnselectedRecord() throws Exception {
        mvc.perform(delete("/literature/batch").contentType("application/json")
                        .content("{\"ids\":[92001,92002]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));

        for (long id : new long[]{92001, 92002}) {
            assertEquals(1, deletedFlag(id));
            assertEquals("original content", Files.readString(directory.resolve(id + ".md")));
            mvc.perform(get("/literature/{id}", id)).andExpect(jsonPath("$.code").value(6001));
            mvc.perform(get("/literature/{id}/download", id)).andExpect(jsonPath("$.code").value(6001));
        }
        assertEquals(0, deletedFlag(92003));
        mvc.perform(post("/literature/page").contentType("application/json")
                        .content("{\"keyword\":\"batch-delete-test\"}"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(92003));
    }

    @Test
    void missingRecordRollsBackEntireBatch() throws Exception {
        mvc.perform(delete("/literature/batch").contentType("application/json")
                        .content("{\"ids\":[92001,92999]}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value(6001));
        // 不在测试事务中执行，确保此处读到的是服务事务回滚后的真实状态。
        assertEquals(0, deletedFlag(92001));
    }

    @Test
    void alreadyDeletedRecordRollsBackOtherDeletions() throws Exception {
        jdbc.update("UPDATE literature SET deleted = 1 WHERE id = 92002");
        mvc.perform(delete("/literature/batch").contentType("application/json")
                        .content("{\"ids\":[92001,92002]}"))
                .andExpect(jsonPath("$.success").value(false));
        assertEquals(0, deletedFlag(92001));
        assertEquals(1, deletedFlag(92002));
    }

    @Test
    void repeatedIdsDeleteEachRecordOnce() throws Exception {
        mvc.perform(delete("/literature/batch").contentType("application/json")
                        .content("{\"ids\":[92001,92001,92002]}"))
                .andExpect(jsonPath("$.success").value(true));
        assertEquals(1, deletedFlag(92001));
        assertEquals(1, deletedFlag(92002));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"ids\":null}", "{\"ids\":[]}", "{\"ids\":[null]}",
            "{\"ids\":[92001,0]}", "{\"ids\":[92001,-1]}"})
    void invalidIdsAreRejectedBeforeDeletion(String body) throws Exception {
        mvc.perform(delete("/literature/batch").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertEquals(0, deletedFlag(92001));
    }

    @Test
    void moreThanOnePageIsRejected() throws Exception {
        String ids = String.join(",", Collections.nCopies(101, "92001"));
        mvc.perform(delete("/literature/batch").contentType("application/json")
                        .content("{\"ids\":[" + ids + "]}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        assertEquals(0, deletedFlag(92001));
    }

    private int deletedFlag(long id) {
        return jdbc.queryForObject("SELECT deleted FROM literature WHERE id = ?", Integer.class, id);
    }
}
