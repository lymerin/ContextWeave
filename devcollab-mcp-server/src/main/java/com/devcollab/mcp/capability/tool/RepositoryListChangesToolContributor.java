package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.application.RepositoryChangesApplicationService;
import com.devcollab.mcp.capability.McpToolContributor;
import com.devcollab.mcp.config.McpProperties;
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
public class RepositoryListChangesToolContributor implements McpToolContributor {

    public static final String TOOL_NAME = "devcollab.repository.list_changes";

    private final RepositoryChangesApplicationService service;
    private final AuditedToolExecutor executor;
    private final McpToolErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final McpProperties properties;
    private final ContractSchemaLoader contracts;

    public RepositoryListChangesToolContributor(
            RepositoryChangesApplicationService service,
            AuditedToolExecutor executor,
            McpToolErrorMapper errorMapper,
            ObjectMapper objectMapper,
            McpProperties properties,
            ContractSchemaLoader contracts
    ) {
        this.service = service;
        this.executor = executor;
        this.errorMapper = errorMapper;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.contracts = contracts;
    }

    @Override
    public List<McpServerFeatures.SyncToolSpecification> tools() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TOOL_NAME)
                .title("List repository changes")
                .description("Page through the latest persisted Git change projection")
                .inputSchema(contracts.input(TOOL_NAME))
                .outputSchema(contracts.output(TOOL_NAME))
                .annotations(WorkspaceContextToolContributor.readOnlyAnnotations())
                .build();
        return List.of(errorMapper.protect(tool, (exchange, request) -> {
            McpUserIdentity identity = McpTransportIdentity.require(exchange);
            Map<String, Object> arguments = request.arguments();
            UUID workspaceId = McpToolArguments.requiredUuid(arguments, "workspaceId");
            UUID repositoryId = McpToolArguments.requiredUuid(arguments, "repositoryId");
            String cursor = McpToolArguments.optionalString(arguments, "cursor");
            Integer requestedLimit = McpToolArguments.optionalInteger(arguments, "limit");
            int limit = requestedLimit == null
                    ? properties.maxRepositoryPageSize() : requestedLimit;
            Map<String, Object> result = executor.execute(
                    TOOL_NAME, identity, workspaceId, repositoryId, arguments,
                    () -> service.listChanges(
                            workspaceId, repositoryId, cursor, limit, identity
                    )
            );
            return ToolResultFactory.success(result, objectMapper);
        }));
    }
}
