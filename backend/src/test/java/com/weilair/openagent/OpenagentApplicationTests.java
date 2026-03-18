package com.weilair.openagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.weilair.openagent.knowledge.service.KnowledgeFileStorageService;
import com.weilair.openagent.knowledge.service.LocalKnowledgeFileStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OpenagentApplicationTests {

    @Autowired
    private KnowledgeFileStorageService knowledgeFileStorageService;

    @Test
    void contextLoads() {
    }

    @Test
    void shouldUseLocalKnowledgeFileStorageServiceBean() {
        assertThat(knowledgeFileStorageService).isInstanceOf(LocalKnowledgeFileStorageService.class);
    }

}
