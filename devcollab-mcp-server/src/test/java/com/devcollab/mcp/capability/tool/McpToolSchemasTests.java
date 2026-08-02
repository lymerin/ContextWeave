package com.devcollab.mcp.capability.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolSchemasTests {

    private final ContractSchemaLoader loader = new ContractSchemaLoader();

    @Test
    @SuppressWarnings("unchecked")
    void bindingListBatchOutputAcceptsDocumentTitleReturnedByTheService() {
        Map<String, Object> schema = loader.output("devcollab.binding.list_batch");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> files = (Map<String, Object>) properties.get("files");
        Map<String, Object> file = (Map<String, Object>) files.get("items");
        Map<String, Object> fileProperties =
                (Map<String, Object>) file.get("properties");
        Map<String, Object> bindings =
                (Map<String, Object>) fileProperties.get("bindings");
        Map<String, Object> binding =
                (Map<String, Object>) bindings.get("items");
        Map<String, Object> bindingProperties =
                (Map<String, Object>) binding.get("properties");

        assertThat(bindingProperties).containsKey("documentTitle");
        assertThat((List<String>) binding.get("required"))
                .contains("documentTitle");
    }

    @Test
    void allRegisteredToolsHaveSchemas() {
        // 确保所有已注册工具都能从共享 schema 加载（不返回空兜底）
        for (String tool : new String[]{
                "devcollab.workspace.get_context",
                "devcollab.code.read",
                "devcollab.binding.list",
                "devcollab.binding.list_batch",
                "devcollab.document.get_structure",
                "devcollab.document.find_candidates",
                "devcollab.repository.list_files",
                "devcollab.repository.list_changes",
                "devcollab.repository.inspect_code_metadata",
                "devcollab.review.submit_document_change",
        }) {
            Map<String, Object> input = loader.input(tool);
            assertThat(input).isNotEmpty();
            assertThat(input.get("type")).isEqualTo("object");
        }
    }
}
