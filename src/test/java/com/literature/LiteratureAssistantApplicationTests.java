package com.literature;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:context_test;DB_CLOSE_DELAY=-1")
class LiteratureAssistantApplicationTests {

    @Test
    void contextLoads() {
    }

}
