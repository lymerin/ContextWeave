package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.application.BindingListApplicationService;
import com.devcollab.mcp.capability.McpToolContributor;
import com.devcollab.mcp.governance.AuditedToolExecutor;
import com.devcollab.mcp.error.McpToolErrorMapper;
import com.devcollab.mcp.security.McpTransportIdentity;
import com.devcollab.mcp.security.McpUserIdentity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class BindingListToolContributor implements McpToolContributor {

    public static final String TOOL_NAME = "devcollab.binding.list";

    private final BindingListApplicationService applicationService;
    private final AuditedToolExecutor auditedToolExecutor;
    private final McpToolErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final ContractSchemaLoader contracts;

    public BindingListToolContributor(
            BindingListApplicationService applicationService,
            AuditedToolExecutor auditedToolExecutor,
            McpToolErrorMapper errorMapper,
            ObjectMapper objectMapper,
            ContractSchemaLoader contracts
    ) {
        this.applicationService = applicationService;
        this.auditedToolExecutor = auditedToolExecutor;
        this.errorMapper = errorMapper;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("List file bindings")
                .description("Fetch document bindings for a repository file")
                .inputSchema(contracts.input(TOOL_NAME))
                .outputSchema(contracts.output(TOOL_NAME))
                .annotations(WorkspaceContextToolContributor.readOnlyAnnotations())
                .build();
                
        return List.of(errorMapper.protect(tool, (exchange, request) -> {
            McpUserIdentity identity = McpTransportIdentity.require(exchange);
            Map<String, Object> arguments = request.arguments();
            
            UUID workspaceId = McpToolArguments.requiredUuid(arguments, "workspaceId");
            UUID repositoryId = McpToolArguments.requiredUuid(arguments, "repositoryId");
            String filePath = McpToolArguments.requiredString(arguments, "filePath");

            Map<String, Object> result = auditedToolExecutor.execute(
                    TOOL_NAME, identity, workspaceId, repositoryId, arguments,
                    () -> applicationService.getFileBindings(workspaceId, repositoryId, filePath, identity)
            );
            return success(result);
        }));
    }

    private McpSchema.CallToolResult success(Map<String, Object> result) {
        try {
            return McpSchema.CallToolResult.builder()
                    .addTextContent(objectMapper.writeValueAsString(result))
                    .structuredContent(result)
                    .isError(false)
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize", exception);
        }
    }
}