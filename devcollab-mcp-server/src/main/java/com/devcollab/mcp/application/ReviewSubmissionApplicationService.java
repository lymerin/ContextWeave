package com.devcollab.mcp.application;

import com.devcollab.mcp.client.KnowledgeCoreGateway;
import com.devcollab.mcp.config.ReviewSubmissionProperties;
import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;
import com.devcollab.mcp.security.McpUserIdentity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReviewSubmissionApplicationService {

    private final KnowledgeCoreGateway gateway;
    private final ReviewSubmissionProperties properties;

    public ReviewSubmissionApplicationService(
            KnowledgeCoreGateway gateway,
            ReviewSubmissionProperties properties
    ) {
        this.gateway = gateway;
        this.properties = properties;
    }

    public Map<String, Object> submit(
            UUID workspaceId,
            Map<String, Object> arguments,
            McpUserIdentity identity
    ) {
        validateText(arguments.get("clientRequestId"), 100, "clientRequestId");
        validateText(
                arguments.get("summary"),
                properties.maxSummaryCharacters(),
                "summary"
        );
        validateText(
                arguments.get("rationale"),
                properties.maxRationaleCharacters(),
                "rationale"
        );
        Object operationsValue = arguments.get("operations");
        List<?> operations = operationsValue == null ? List.of() : list(operationsValue, "operations");
        
        Object bindingProposalsValue = arguments.get("bindingProposals");
        List<?> bindingProposals = bindingProposalsValue == null ? List.of() : list(bindingProposalsValue, "bindingProposals");

        if (operations.isEmpty() && bindingProposals.isEmpty()) {
            throw invalid("must contain at least one operation or binding proposal");
        }
        if (operations.size() > properties.maxOperations()) {
            throw invalid(
                    "operations must not exceed "
                            + properties.maxOperations() + " entries"
            );
        }
        if (bindingProposals.size() > properties.maxOperations()) {
            throw invalid(
                    "bindingProposals must not exceed "
                            + properties.maxOperations() + " entries"
            );
        }

        for (Object operation : operations) {
            Map<?, ?> item = map(operation, "operation");
            validateText(item.get("clientOperationId"), 100, "clientOperationId");
            validateOptionalText(
                    item.get("proposedPlainText"),
                    properties.maxProposedCharacters(),
                    "proposedPlainText"
            );
            Object proposedContent = item.get("proposedContent");
            if (proposedContent != null
                    && proposedContent.toString().length()
                    > properties.maxProposedCharacters() * 4) {
                throw invalid("proposedContent exceeds the configured budget");
            }
        }
        
        for (Object proposal : bindingProposals) {
            Map<?, ?> item = map(proposal, "binding proposal");
            validateText(item.get("clientBindingProposalId"), 100, "clientBindingProposalId");
            validateText(item.get("filePath"), 1000, "bindingProposal.filePath");
            validateText(item.get("reason"), 1000, "bindingProposal.reason");
        }
        Object evidenceValue = arguments.get("evidence");
        List<?> evidence = evidenceValue == null
                ? List.of() : list(evidenceValue, "evidence");
        if (evidence.size() > properties.maxEvidence()) {
            throw invalid(
                    "evidence must not exceed "
                            + properties.maxEvidence() + " entries"
            );
        }
        for (Object evidenceItem : evidence) {
            Map<?, ?> item = map(evidenceItem, "evidence item");
            validateText(
                    item.get("filePath"),
                    1000,
                    "evidence.filePath"
            );
            validateText(
                    item.get("description"),
                    properties.maxDescriptionCharacters(),
                    "evidence.description"
            );
        }

        Map<String, Object> body = new LinkedHashMap<>(arguments);
        body.remove("workspaceId");
        Map<String, Object> coreResult = gateway.submitDocumentChange(workspaceId, body, identity);
        Map<String, Object> result = new LinkedHashMap<>(coreResult);
        result.put("workspaceId", workspaceId.toString());
        result.put("repositoryId", uniqueString(bindingProposals, "repositoryId"));
        result.put("documentId", uniqueDocumentId(operations, bindingProposals));
        result.put("operationCount", operations.size());
        result.put("bindingProposalCount", bindingProposals.size());
        return result;
    }

    private Object uniqueDocumentId(List<?> operations, List<?> bindingProposals) {
        Object operationDocumentId = uniqueString(operations, "documentId");
        Object bindingDocumentId = uniqueString(bindingProposals, "documentId");
        if (operationDocumentId == null) {
            return bindingDocumentId;
        }
        if (bindingDocumentId == null || operationDocumentId.equals(bindingDocumentId)) {
            return operationDocumentId;
        }
        return null;
    }

    private Object uniqueString(List<?> values, String field) {
        String selected = null;
        for (Object value : values) {
            Object candidate = map(value, field).get(field);
            if (candidate == null) {
                continue;
            }
            String normalized = candidate.toString();
            if (selected != null && !selected.equals(normalized)) {
                return null;
            }
            selected = normalized;
        }
        return selected;
    }

    private void validateText(Object value, int max, String field) {
        if (!(value instanceof String text)
                || text.trim().isEmpty()
                || text.codePointCount(0, text.length()) > max) {
            throw invalid(field + " is required and exceeds no configured limit");
        }
    }

    private void validateOptionalText(Object value, int max, String field) {
        if (value != null) {
            validateText(value, max, field);
        }
    }

    private List<?> list(Object value, String field) {
        if (!(value instanceof List<?> list)) {
            throw invalid(field + " must be an array");
        }
        return list;
    }

    private Map<?, ?> map(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid(field + " must be an object");
        }
        return map;
    }

    private McpToolException invalid(String message) {
        return new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, message);
    }
}
