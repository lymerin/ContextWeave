package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.application.CodeReadApplicationService;
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
public class CodeReadBatchToolContributor implements McpToolContributor {

    public static final String TOOL_NAME = "devcollab.code.read_batch";

    private final CodeReadApplicationService applicationService;
    private final AuditedToolExecutor auditedToolExecutor;
    private final McpToolErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final ContractSchemaLoader contracts;

    public CodeReadBatchToolContributor(
            CodeReadApplicationService applicationService,
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
                .title("Read DevCollab repository source files")
                .description("Batch read authorized text source from DevCollab repository projections.")
                .inputSchema(contracts.input(TOOL_NAME))
                .outputSchema(contracts.output(TOOL_NAME))
                .annotations(WorkspaceContextToolContributor.readOnlyAnnotations())
                .build();
        return List.of(errorMapper.protect(tool, (exchange, request) -> {
            McpUserIdentity identity = McpTransportIdentity.require(exchange);
            Map<String, Object> arguments = request.arguments();
            UUID workspaceId = McpToolArguments.requiredUuid(arguments, "workspaceId");
            UUID repositoryId = McpToolArguments.requiredUuid(arguments, "repositoryId");
            List<String> paths = McpToolArguments.stringList(arguments, "paths");
            String revision = McpToolArguments.requiredString(arguments, "revision");
            Map<String, Object> result = auditedToolExecutor.execute(
                    TOOL_NAME, identity, workspaceId, repositoryId, arguments,
                    () -> applicationService.readBatch(
                            workspaceId, repositoryId, paths, revision, identity
                    )
            );
            return ToolResultFactory.success(result, objectMapper);
        }));
    }
}
