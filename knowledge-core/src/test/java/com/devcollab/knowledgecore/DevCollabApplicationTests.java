package com.devcollab.knowledgecore;

import com.devcollab.knowledgecore.auth.infrastructure.JdbcRefreshSessionRepository;
import com.devcollab.knowledgecore.auth.infrastructure.JdbcUserRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.infrastructure.JdbcDocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.infrastructure.JdbcDocumentRepository;
import com.devcollab.knowledgecore.workspace.infrastructure.JdbcWorkspaceMemberRepository;
import com.devcollab.knowledgecore.workspace.infrastructure.JdbcWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest
class DevCollabApplicationTests {

    @Autowired
    private JdbcUserRepository userRepository;

    @Autowired
    private JdbcRefreshSessionRepository refreshSessionRepository;

    @Autowired
    private JdbcWorkspaceRepository workspaceRepository;

    @Autowired
    private JdbcWorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentBlockRepository documentBlockRepository;

    @Test
    void contextLoads() {
        assertInstanceOf(JdbcDocumentRepository.class, documentRepository);
        assertInstanceOf(
                JdbcDocumentBlockRepository.class,
                documentBlockRepository
        );
    }
}
