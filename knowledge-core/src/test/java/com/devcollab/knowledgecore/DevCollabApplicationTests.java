package com.devcollab.knowledgecore;

import com.devcollab.knowledgecore.auth.domain.RefreshSessionRepository;
import com.devcollab.knowledgecore.auth.domain.UserRepository;
import com.devcollab.knowledgecore.auth.infrastructure.JdbcRefreshSessionRepository;
import com.devcollab.knowledgecore.auth.infrastructure.JdbcUserRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.domain.DocumentRepository;
import com.devcollab.knowledgecore.document.core.infrastructure.JdbcDocumentBlockRepository;
import com.devcollab.knowledgecore.document.core.infrastructure.JdbcDocumentRepository;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceMemberRepository;
import com.devcollab.knowledgecore.workspace.domain.WorkspaceRepository;
import com.devcollab.knowledgecore.workspace.infrastructure.JdbcWorkspaceMemberRepository;
import com.devcollab.knowledgecore.workspace.infrastructure.JdbcWorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@SpringBootTest
class DevCollabApplicationTests {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshSessionRepository refreshSessionRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private WorkspaceMemberRepository workspaceMemberRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private DocumentBlockRepository documentBlockRepository;

    @Test
    void contextLoads() {
        assertInstanceOf(JdbcUserRepository.class, userRepository);
        assertInstanceOf(
                JdbcRefreshSessionRepository.class,
                refreshSessionRepository
        );
        assertInstanceOf(JdbcWorkspaceRepository.class, workspaceRepository);
        assertInstanceOf(
                JdbcWorkspaceMemberRepository.class,
                workspaceMemberRepository
        );
        assertInstanceOf(JdbcDocumentRepository.class, documentRepository);
        assertInstanceOf(
                JdbcDocumentBlockRepository.class,
                documentBlockRepository
        );
    }
}
