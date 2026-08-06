package com.devcollab.mcp.capability.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从共享 JSON Schema（classpath contracts/mcp/）加载 MCP 工具契约。
 *
 * 共享 schema 位于仓库根 contracts/mcp/（单一权威源），经 maven-resources
 * 外部引用进入 classpath。本组件按工具名加载 input/output schema，
 * 替代手写 Java Map。
 */
@Component
public class ContractSchemaLoader {

    private static final String BASE = "contracts/mcp/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 工具注册名 → (input 文件名, output 文件名)
    private static final Map<String, String[]> TOOL_FILES = Map.ofEntries(
            Map.entry("devcollab.workspace.get_context",
                    new String[]{"workspace_get_context_input.json", "workspace_get_context_output.json"}),
            Map.entry("devcollab.code.read",
                    new String[]{"code_read_input.json", "code_read_output.json"}),
            Map.entry("devcollab.binding.list",
                    new String[]{"binding_list_input.json", "binding_list_output.json"}),
            Map.entry("devcollab.binding.list_batch",
                    new String[]{"binding_list_batch_input.json", "binding_list_batch_output.json"}),
            Map.entry("devcollab.document.get_structure",
                    new String[]{"document_get_structure_input.json", "document_get_structure_output.json"}),
            Map.entry("devcollab.document.find_candidates",
                    new String[]{"document_find_candidates_input.json", "document_find_candidates_output.json"}),
            Map.entry("devcollab.repository.list_files",
                    new String[]{"repository_list_files_input.json", "repository_list_files_output.json"}),
            Map.entry("devcollab.repository.list_changes",
                    new String[]{"repository_list_changes_input.json", "repository_list_changes_input.json"}),
            Map.entry("devcollab.repository.inspect_code_metadata",
                    new String[]{"repository_inspect_code_metadata_input.json", "repository_inspect_code_metadata_output.json"}),
            Map.entry("devcollab.review.submit_document_change",
                    new String[]{"submit_document_change_input.json", "submit_document_change_output.json"})
    );

    /** 工具注册名 → input schema Map。 */
    private final Map<String, Map<String, Object>> inputSchemas = new LinkedHashMap<>();
    /** 工具注册名 → output schema Map。 */
    private final Map<String, Map<String, Object>> outputSchemas = new LinkedHashMap<>();

    public ContractSchemaLoader() {
        for (var entry : TOOL_FILES.entrySet()) {
            String tool = entry.getKey();
            String[] files = entry.getValue();
            inputSchemas.put(tool, load(files[0]));
            outputSchemas.put(tool, load(files[1]));
        }
    }

    public Map<String, Object> input(String toolName) {
        return schemaOrEmpty(inputSchemas, toolName);
    }

    public Map<String, Object> output(String toolName) {
        return schemaOrEmpty(outputSchemas, toolName);
    }

    private Map<String, Object> schemaOrEmpty(Map<String, Map<String, Object>> cache, String tool) {
        Map<String, Object> schema = cache.get(tool);
        if (schema == null) {
            return Map.of("type", "object", "additionalProperties", false, "properties", Map.of());
        }
        return schema;
    }

    private Map<String, Object> load(String filename) {
        ClassPathResource resource = new ClassPathResource(BASE + filename);
        try (InputStream in = resource.getInputStream()) {
            return MAPPER.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("无法加载契约 schema: " + filename, e);
        }
    }
}
