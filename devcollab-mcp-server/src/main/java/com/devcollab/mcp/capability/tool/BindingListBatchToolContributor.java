package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.application.BindingListBatchApplicationService;
import com.devcollab.mcp.capability.McpToolContributor;
import com.devcollab.mcp.error.McpToolErrorMapper;
import com.devcollab.mcp.governance.AuditedToolExecutor;
import com.devcollab.mcp.security.McpTransportIdentity;
import com.devcollab.mcp.security.McpUserIdentity;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class BindingListBatchToolContributor implements McpToolContributor {

    public static final String TOOL_NAME = "devcollab.binding.list_batch";

    private final BindingListBatchApplicationService service;
    private final AuditedToolExecutor executor;
    private final McpToolErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final ContractSchemaLoader contracts;

    public BindingListBatchToolContributor(
            BindingListBatchApplicationService service,
            AuditedToolExecutor executor,
            McpToolErrorMapper errorMapper,
            ObjectMapper objectMapper,
            ContractSchemaLoader contracts
    ) {
        this.service = service;
        this.executor = executor;
        this.errorMapper = errorMapper;
        this.objectMapper = objectMapper;
        this.contracts = contracts;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("List bindings for files")
                .description("Batch query formal document bindings for repository files")
                .inputSchema(contracts.input(TOOL_NAME))
                .outputSchema(contracts.output(TOOL_NAME))
                .annotations(WorkspaceContextToolContributor.readOnlyAnnotations())
                .build();
        return List.of(errorMapper.protect(tool, (exchange, request) -> {
            McpUserIdentity identity = McpTransportIdentity.require(exchange);
            Map<String, Object> arguments = request.arguments();
            UUID workspaceId = McpToolArguments.requiredUuid(arguments, "workspaceId");
            UUID repositoryId = McpToolArguments.requiredUuid(arguments, "repositoryId");
            List<String> filePaths = McpToolArguments.requiredStringList(
                    arguments, "filePaths"
            );
            Map<String, Object> result = executor.execute(
                    TOOL_NAME, identity, workspaceId, repositoryId, arguments,
                    () -> service.listBindings(
                            workspaceId, repositoryId, filePaths, identity
                    )
            );
            return ToolResultFactory.success(result, objectMapper);
        }));
    }
}
