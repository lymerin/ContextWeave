package com.devcollab.knowledgecore.common.util;

import com.devcollab.knowledgecore.git.application.CodeMetadataBatchResult;
import com.devcollab.knowledgecore.git.application.CodeMetadataInspector;
import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CodeMetadataInspectorTests {

    private final CodeMetadataInspector inspector = new CodeMetadataInspector();

    @Test
    void extractsJavaPackageImportsSymbolsAnnotationsRoutesAndRoles() {
        CodeMetadataBatchResult.FileMetadata controller = inspect(
                "knowledge-core/src/main/java/com/example/AuthController.java",
                "Java",
                """
                package com.example;
                import com.example.auth.AuthService;
                @RestController
                @RequestMapping("/auth")
                class AuthController {}
                """
        );
        CodeMetadataBatchResult.FileMetadata service = inspect(
                "knowledge-core/src/main/java/com/example/AuthService.java",
                "Java",
                "package com.example; class AuthService {}"
        );
        CodeMetadataBatchResult.FileMetadata repository = inspect(
                "knowledge-core/src/main/java/com/example/AuthRepository.java",
                "Java",
                "package com.example; interface AuthRepository {}"
        );

        assertThat(controller.packageName()).isEqualTo("com.example");
        assertThat(controller.blobSha()).isEqualTo("blob");
        assertThat(controller.imports()).containsExactly("com.example.auth.AuthService");
        assertThat(controller.topLevelSymbols()).contains("AuthController");
        assertThat(controller.annotations()).contains("RestController", "RequestMapping");
        assertThat(controller.roleHints()).contains("CONTROLLER");
        assertThat(service.roleHints()).contains("SERVICE");
        assertThat(repository.roleHints()).contains("REPOSITORY");
    }

    @Test
    void extractsTypeScriptVueAndPythonWithoutReturningSource() {
        CodeMetadataBatchResult.FileMetadata api = inspect(
                "web/src/api/auth.ts",
                "TypeScript",
                """
                import http from './http'
                export const login = () => http.post('/login')
                """
        );
        CodeMetadataBatchResult.FileMetadata vue = inspect(
                "web/src/views/LoginView.vue",
                "Vue",
                """
                <script setup lang="ts">
                import { login } from '@/api/auth'
                </script>
                """
        );
        CodeMetadataBatchResult.FileMetadata python = inspect(
                "agent-service/app/runtime/worker.py",
                "Python",
                """
                import asyncio
                from app.config import Settings
                class AgentWorker:
                    pass
                """
        );

        assertThat(api.imports()).contains("./http");
        assertThat(api.exportedSymbols()).contains("login");
        assertThat(api.roleHints()).contains("API_CLIENT");
        assertThat(vue.imports()).contains("@/api/auth");
        assertThat(vue.roleHints()).contains("VIEW");
        assertThat(python.imports()).contains("asyncio", "app.config");
        assertThat(python.topLevelSymbols()).contains("AgentWorker");
        assertThat(api.toString()).doesNotContain("http.post");
    }

    @Test
    void unreadableOrUnsupportedFileDegradesPerFile() {
        GitRepositoryFile unreadable = new GitRepositoryFile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "src/Broken.java",
                "blob",
                10,
                "Java",
                null
        );
        CodeMetadataBatchResult.FileMetadata failed = inspector.inspect(unreadable);
        CodeMetadataBatchResult.FileMetadata unsupported = inspect(
                "src/native.rs",
                "Rust",
                "fn main() {}"
        );

        assertThat(failed.parseStatus()).isEqualTo("FAILED");
        assertThat(failed.blobSha()).isEqualTo("blob");
        assertThat(failed.errorCode()).isEqualTo("SOURCE_NOT_READABLE");
        assertThat(unsupported.parseStatus()).isEqualTo("FAILED");
        assertThat(unsupported.errorCode()).isEqualTo("UNSUPPORTED_LANGUAGE");
    }

    private CodeMetadataBatchResult.FileMetadata inspect(
            String path,
            String language,
            String source
    ) {
        return inspector.inspect(new GitRepositoryFile(
                UUID.randomUUID(),
                UUID.randomUUID(),
                path,
                "blob",
                source.length(),
                language,
                source
        ));
    }
}
