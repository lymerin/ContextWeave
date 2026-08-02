package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.application.ReviewSubmissionApplicationService;
import com.devcollab.mcp.capability.McpToolContributor;
import com.devcollab.mcp.error.McpToolErrorMapper;
import com.devcollab.mcp.governance.AuditedToolExecutor;
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
public class SubmitDocumentChangeToolContributor implements McpToolContributor {

    public static final String TOOL_NAME =
            "devcollab.review.submit_document_change";

    private final ReviewSubmissionApplicationService applicationService;
    private final AuditedToolExecutor auditedToolExecutor;
    private final McpToolErrorMapper errorMapper;
    private final ObjectMapper objectMapper;
    private final ContractSchemaLoader contracts;

    public SubmitDocumentChangeToolContributor(
            ReviewSubmissionApplicationService applicationService,
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
                .title("Submit a document change for human review")
                .description(
                        "Create an idempotent PENDING proposal. "
                                + "This tool never changes formal documents."
                )
                .inputSchema(contracts.input(TOOL_NAME))
                .outputSchema(contracts.output(TOOL_NAME))
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(false)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
                .build();
        return List.of(errorMapper.protect(tool, (exchange, request) -> {
            McpUserIdentity identity = McpTransportIdentity.require(exchange);
            Map<String, Object> arguments = request.arguments();
            UUID workspaceId = McpToolArguments.requiredUuid(
                    arguments,
                    "workspaceId"
            );
            Map<String, Object> result = auditedToolExecutor.execute(
                    TOOL_NAME,
                    identity,
                    workspaceId,
                    null,
                    arguments,
                    () -> applicationService.submit(
                            workspaceId,
                            arguments,
                            identity
                    )
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
            throw new IllegalStateException(
                    "Could not serialize document review result",
                    exception
            );
        }
    }
}
