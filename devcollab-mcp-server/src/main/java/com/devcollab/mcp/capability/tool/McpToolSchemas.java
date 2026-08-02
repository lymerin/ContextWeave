package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.config.ReviewSubmissionProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class McpToolSchemas {

    private McpToolSchemas() {
    }

    static Map<String, Object> workspaceContextInput() {
        return objectSchema(
                Map.of("workspaceId", uuidProperty("DevCollab workspace identifier")),
                List.of("workspaceId")
        );
    }

    static Map<String, Object> codeReadInput(int maxPathCharacters) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("DevCollab workspace identifier"));
        properties.put("repositoryId", uuidProperty("Registered repository identifier"));
        properties.put("path", Map.of(
                "type", "string",
                "description", "Repository-relative file path",
                "minLength", 1,
                "maxLength", maxPathCharacters
        ));
        properties.put("startLine", Map.of("type", "integer", "minimum", 1));
        properties.put("endLine", Map.of("type", "integer", "minimum", 1));
        properties.put("includeExistingBindings", Map.of(
                "type", "boolean",
                "default", false,
                "description", "Request existing links when that backend capability is available"
        ));
        return objectSchema(properties, List.of("workspaceId", "repositoryId", "path"));
    }

    static Map<String, Object> workspaceContextOutput() {
        Map<String, Object> repository = objectSchema(
                Map.of(
                        "repositoryId", uuidProperty("Repository identifier"),
                        "name", Map.of("type", "string"),
                        "provider", Map.of("type", "string"),
                        "remoteUrl", Map.of("type", "string"),
                        "defaultBranch", Map.of("type", "string"),
                        "syncStatus", Map.of("type", "string"),
                        "lastSyncedCommit", Map.of("type", "string")
                ),
                List.of("repositoryId", "name", "provider", "remoteUrl", "defaultBranch", "syncStatus")
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "name", Map.of("type", "string"),
                        "currentUserRole", Map.of("type", "string"),
                        "repositories", Map.of("type", "array", "items", repository)
                ),
                List.of("workspaceId", "name", "currentUserRole", "repositories")
        );
    }

    static Map<String, Object> codeReadOutput() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("Workspace identifier"));
        properties.put("repositoryId", uuidProperty("Repository identifier"));
        properties.put("path", Map.of("type", "string"));
        properties.put("commitHash", Map.of("type", "string"));
        properties.put("language", Map.of("type", List.of("string", "null")));
        properties.put("sizeBytes", Map.of("type", "integer"));
        properties.put("startLine", Map.of("type", "integer"));
        properties.put("endLine", Map.of("type", "integer"));
        properties.put("totalLines", Map.of("type", "integer"));
        properties.put("content", Map.of("type", "string"));
        properties.put("truncated", Map.of("type", "boolean"));
        properties.put("omittedLineCount", Map.of("type", "integer"));
        properties.put("omittedCharacterCount", Map.of("type", "integer"));
        properties.put("existingBindings", Map.of("type", "array", "items", Map.of("type", "object")));
        properties.put("existingBindingsAvailable", Map.of("type", "boolean"));
        properties.put("existingBindingsRequested", Map.of("type", "boolean"));
        return toolOutputSchema(
                properties,
                List.of(
                        "workspaceId", "repositoryId", "path", "commitHash", "sizeBytes",
                        "startLine", "endLine", "totalLines", "content", "truncated",
                        "omittedLineCount", "omittedCharacterCount", "existingBindings",
                        "existingBindingsAvailable", "existingBindingsRequested"
                )
        );
    }
    
    static Map<String, Object> documentStructureInput() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("DevCollab workspace identifier"));
        properties.put("documentId", uuidProperty("DevCollab document identifier"));
        properties.put("includeBlockContent", Map.of(
                "type", "boolean",
                "default", false
        ));
        properties.put("maxBlocks", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 500
        ));
        properties.put("maxContentCharacters", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 100000
        ));
        return objectSchema(properties, List.of("workspaceId", "documentId"));
    }

    static Map<String, Object> documentStructureOutput() {
        Map<String, Object> blockProperties = new LinkedHashMap<>();
        blockProperties.put("blockId", uuidProperty("Block identifier"));
        blockProperties.put("blockType", Map.of("type", "string"));
        blockProperties.put("sortOrder", Map.of("type", "integer"));
        blockProperties.put("version", Map.of("type", "integer"));
        blockProperties.put("plainText", Map.of("type", List.of("string", "null")));
        blockProperties.put("content", Map.of("type", List.of("string", "null")));
        blockProperties.put("contentTruncated", Map.of("type", "boolean"));
        Map<String, Object> block = objectSchema(
                blockProperties,
                List.of("blockId", "blockType", "sortOrder", "version")
        );
        return toolOutputSchema(
                Map.of(
                        "documentId", uuidProperty("Document identifier"),
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "title", Map.of("type", "string"),
                        "documentType", Map.of("type", "string"),
                        "reviewStatus", Map.of("type", "string"),
                        "updatedAt", Map.of("type", "string", "format", "date-time"),
                        "blocks", Map.of("type", "array", "items", block),
                        "truncated", Map.of("type", "boolean"),
                        "omittedBlockCount", Map.of("type", "integer"),
                        "omittedCharacterCount", Map.of("type", "integer")
                ),
                List.of("documentId", "workspaceId", "title", "documentType", "reviewStatus", "updatedAt", "blocks", "truncated", "omittedBlockCount", "omittedCharacterCount")
        );
    }
    
    static Map<String, Object> bindingListInput() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("DevCollab workspace identifier"));
        properties.put("repositoryId", uuidProperty("Registered repository identifier"));
        properties.put("filePath", Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", 1000
        ));
        return objectSchema(properties, List.of("workspaceId", "repositoryId", "filePath"));
    }

    static Map<String, Object> bindingListOutput() {
        Map<String, Object> bindingProperties = new LinkedHashMap<>();
        bindingProperties.put("bindingId", uuidProperty("Binding identifier"));
        bindingProperties.put("pathPattern", Map.of("type", "string"));
        bindingProperties.put("documentId", uuidProperty("Document identifier"));
        bindingProperties.put("documentTitle", Map.of("type", List.of("string", "null")));
        bindingProperties.put("blockId", uuidProperty("Block identifier", true));
        bindingProperties.put("revision", Map.of("type", List.of("string", "null")));
        bindingProperties.put("anchorKind", Map.of("type", List.of("string", "null")));
        bindingProperties.put("symbolKey", Map.of("type", List.of("string", "null")));
        bindingProperties.put("startLine", Map.of("type", List.of("integer", "null")));
        bindingProperties.put("endLine", Map.of("type", List.of("integer", "null")));
        bindingProperties.put("bindingRole", Map.of("type", List.of("string", "null")));
        bindingProperties.put("bindingOrdinal", Map.of("type", "integer"));
        Map<String, Object> binding = objectSchema(
                bindingProperties,
                List.of("bindingId", "pathPattern", "documentId", "documentTitle")
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "filePath", Map.of("type", "string"),
                        "fileHasBindings", Map.of("type", "boolean"),
                        "bindings", Map.of("type", "array", "items", binding),
                        "truncated", Map.of("type", "boolean"),
                        "omittedBindingCount", Map.of("type", "integer")
                ),
                List.of("workspaceId", "repositoryId", "filePath", "fileHasBindings", "bindings", "truncated", "omittedBindingCount")
        );
    }

    static Map<String, Object> repositoryListFilesInput(
            int maxPathCharacters,
            int maxPageSize
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("Workspace identifier"));
        properties.put("repositoryId", uuidProperty("Repository identifier"));
        properties.put("revision", Map.of("type", "string", "minLength", 1));
        properties.put("pathPrefix", Map.of(
                "type", "string", "maxLength", maxPathCharacters
        ));
        properties.put("recursive", Map.of("type", "boolean", "default", true));
        properties.put("cursor", Map.of("type", "string", "minLength", 1));
        properties.put("limit", Map.of(
                "type", "integer", "minimum", 1, "maximum", maxPageSize
        ));
        return objectSchema(properties, List.of("workspaceId", "repositoryId"));
    }

    static Map<String, Object> repositoryListFilesOutput() {
        Map<String, Object> file = objectSchema(
                Map.of(
                        "filePath", Map.of("type", "string"),
                        "fileName", Map.of("type", "string"),
                        "extension", Map.of("type", "string"),
                        "sizeBytes", Map.of("type", "integer", "minimum", 0),
                        "language", Map.of("type", List.of("string", "null")),
                        "readable", Map.of("type", "boolean"),
                        "isDirectory", Map.of("type", "boolean")
                ),
                List.of(
                        "filePath", "fileName", "extension", "sizeBytes",
                        "language", "readable", "isDirectory"
                )
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "revision", Map.of("type", "string"),
                        "pathPrefix", Map.of("type", "string"),
                        "recursive", Map.of("type", "boolean"),
                        "files", Map.of("type", "array", "items", file),
                        "nextCursor", Map.of("type", List.of("string", "null")),
                        "hasMore", Map.of("type", "boolean")
                ),
                List.of(
                        "workspaceId", "repositoryId", "revision", "pathPrefix", "recursive",
                        "files", "nextCursor", "hasMore"
                )
        );
    }

    static Map<String, Object> repositoryListChangesInput(int maxPageSize) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("Workspace identifier"));
        properties.put("repositoryId", uuidProperty("Repository identifier"));
        properties.put("cursor", Map.of("type", "string", "minLength", 1));
        properties.put("limit", Map.of(
                "type", "integer", "minimum", 1, "maximum", maxPageSize
        ));
        return objectSchema(properties, List.of("workspaceId", "repositoryId"));
    }

    static Map<String, Object> repositoryCodeMetadataInput(
            int maxPathCharacters,
            int maxPaths
    ) {
        return objectSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "revision", Map.of(
                                "type", "string",
                                "minLength", 1,
                                "maxLength", 64
                        ),
                        "filePaths", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "maxItems", maxPaths,
                                "items", Map.of(
                                        "type", "string",
                                        "minLength", 1,
                                        "maxLength", maxPathCharacters
                                )
                        )
                ),
                List.of("workspaceId", "repositoryId", "revision", "filePaths")
        );
    }

    static Map<String, Object> repositoryCodeMetadataOutput() {
        Map<String, Object> nullableText = Map.of(
                "type", List.of("string", "null")
        );
        Map<String, Object> stringArray = Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
        Map<String, Object> file = objectSchema(
                Map.ofEntries(
                        Map.entry("filePath", Map.of("type", "string")),
                        Map.entry("language", nullableText),
                        Map.entry("packageName", nullableText),
                        Map.entry("moduleKey", nullableText),
                        Map.entry("moduleName", nullableText),
                        Map.entry("layerHint", nullableText),
                        Map.entry("imports", stringArray),
                        Map.entry("resolvedRepositoryImports", stringArray),
                        Map.entry("exportedSymbols", stringArray),
                        Map.entry("topLevelSymbols", stringArray),
                        Map.entry("annotations", stringArray),
                        Map.entry("routeHints", stringArray),
                        Map.entry("roleHints", stringArray),
                        Map.entry("parseStatus", Map.of(
                                "type", "string",
                                "enum", List.of("PARSED", "FAILED")
                        )),
                        Map.entry("errorCode", nullableText)
                ),
                List.of(
                        "filePath", "language", "packageName", "moduleKey", "moduleName",
                        "layerHint", "imports", "exportedSymbols",
                        "resolvedRepositoryImports",
                        "topLevelSymbols", "annotations", "routeHints",
                        "roleHints", "parseStatus", "errorCode"
                )
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "revision", Map.of("type", "string"),
                        "files", Map.of("type", "array", "items", file)
                ),
                List.of("workspaceId", "repositoryId", "revision", "files")
        );
    }

    static Map<String, Object> repositoryListChangesOutput() {
        Map<String, Object> file = objectSchema(
                Map.of(
                        "status", Map.of(
                                "type", "string",
                                "enum", List.of("ADDED", "MODIFIED", "DELETED", "RENAMED")
                        ),
                        "filePath", Map.of("type", "string"),
                        "oldPath", Map.of("type", List.of("string", "null")),
                        "binaryFile", Map.of("type", "boolean")
                ),
                List.of("status", "filePath", "oldPath", "binaryFile")
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "changeId", uuidProperty("Latest persisted change identifier", true),
                        "changeType", Map.of("type", List.of("string", "null")),
                        "commitSha", Map.of("type", List.of("string", "null")),
                        "files", Map.of("type", "array", "items", file),
                        "nextCursor", Map.of("type", List.of("string", "null")),
                        "hasMore", Map.of("type", "boolean")
                ),
                List.of(
                        "workspaceId", "repositoryId", "changeId", "changeType",
                        "commitSha", "files", "nextCursor", "hasMore"
                )
        );
    }

    static Map<String, Object> bindingListBatchInput(
            int maxPathCharacters,
            int maxPaths
    ) {
        return objectSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "filePaths", Map.of(
                                "type", "array",
                                "minItems", 1,
                                "maxItems", maxPaths,
                                "items", Map.of(
                                        "type", "string",
                                        "minLength", 1,
                                        "maxLength", maxPathCharacters
                                )
                        )
                ),
                List.of("workspaceId", "repositoryId", "filePaths")
        );
    }

    static Map<String, Object> bindingListBatchOutput() {
        Map<String, Object> bindingProperties = new LinkedHashMap<>();
        bindingProperties.put("bindingId", uuidProperty("Binding identifier"));
        bindingProperties.put("repositoryId", uuidProperty("Repository identifier"));
        bindingProperties.put("documentId", uuidProperty("Document identifier"));
        bindingProperties.put("documentTitle", Map.of("type", List.of("string", "null")));
        bindingProperties.put("blockId", uuidProperty("Block identifier", true));
        bindingProperties.put("pathPattern", Map.of("type", "string"));
        bindingProperties.put("revision", Map.of("type", List.of("string", "null")));
        bindingProperties.put("anchorKind", Map.of("type", List.of("string", "null")));
        bindingProperties.put("symbolKey", Map.of("type", List.of("string", "null")));
        bindingProperties.put("startLine", Map.of("type", List.of("integer", "null")));
        bindingProperties.put("endLine", Map.of("type", List.of("integer", "null")));
        bindingProperties.put("bindingRole", Map.of("type", List.of("string", "null")));
        bindingProperties.put("bindingOrdinal", Map.of("type", "integer"));
        Map<String, Object> binding = objectSchema(
                bindingProperties,
                List.of(
                        "bindingId", "repositoryId", "documentId",
                        "documentTitle", "blockId", "pathPattern"
                )
        );
        Map<String, Object> file = objectSchema(
                Map.of(
                        "filePath", Map.of("type", "string"),
                        "fileHasBindings", Map.of("type", "boolean"),
                        "bindings", Map.of("type", "array", "items", binding),
                        "truncated", Map.of("type", "boolean"),
                        "omittedBindingCount", Map.of("type", "integer")
                ),
                List.of("filePath", "bindings")
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier"),
                        "files", Map.of("type", "array", "items", file)
                ),
                List.of("workspaceId", "repositoryId", "files")
        );
    }

    static Map<String, Object> findCandidatesInput(
            int maxPathCharacters,
            int maxQueryCharacters,
            int maxCandidates
    ) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("workspaceId", uuidProperty("DevCollab workspace identifier"));
        properties.put("repositoryId", uuidProperty("Registered repository identifier"));
        properties.put("filePath", Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", maxPathCharacters,
                "description", "Repository-relative file path"
        ));
        properties.put("query", Map.of(
                "type", "string",
                "minLength", 1,
                "maxLength", maxQueryCharacters,
                "description", "Optional deterministic document query"
        ));
        properties.put("limit", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", maxCandidates
        ));
        Map<String, Object> schema = new LinkedHashMap<>(objectSchema(
                properties, List.of("workspaceId")
        ));
        schema.put("allOf", List.of(
                Map.of("anyOf", List.of(
                        Map.of("required", List.of("filePath")),
                        Map.of("required", List.of("query"))
                )),
                Map.of(
                        "if", Map.of("required", List.of("filePath")),
                        "then", Map.of("required", List.of("repositoryId"))
                )
        ));
        return schema;
    }

    static Map<String, Object> findCandidatesOutput() {
        Map<String, Object> reason = objectSchema(
                Map.of(
                        "code", Map.of("type", "string"),
                        "weight", Map.of("type", "integer"),
                        "matchedTerm", Map.of("type", List.of("string", "null")),
                        "matchedBlockIds", Map.of(
                                "type", "array",
                                "items", uuidProperty("Matched Block identifier")
                        )
                ),
                List.of("code", "weight", "matchedTerm", "matchedBlockIds")
        );
        Map<String, Object> candidateProperties = new LinkedHashMap<>();
        candidateProperties.put("documentId", uuidProperty("Document identifier"));
        candidateProperties.put("title", Map.of("type", "string"));
        candidateProperties.put("score", Map.of("type", "integer", "minimum", 1));
        candidateProperties.put("matchReasons", Map.of("type", "array", "items", reason));
        candidateProperties.put("matchedBlockIds", Map.of(
                "type", "array",
                "items", uuidProperty("Matched Block identifier")
        ));
        candidateProperties.put("existingBindingCount", Map.of("type", "integer", "minimum", 0));
        Map<String, Object> candidate = objectSchema(
                candidateProperties,
                List.of(
                        "documentId", "title", "score", "matchReasons",
                        "matchedBlockIds", "existingBindingCount"
                )
        );
        return toolOutputSchema(
                Map.of(
                        "workspaceId", uuidProperty("Workspace identifier"),
                        "repositoryId", uuidProperty("Repository identifier", true),
                        "filePath", Map.of("type", List.of("string", "null")),
                        "query", Map.of("type", List.of("string", "null")),
                        "candidates", Map.of("type", "array", "items", candidate),
                        "truncated", Map.of("type", "boolean"),
                        "omittedCandidateCount", Map.of("type", "integer", "minimum", 0)
                ),
                List.of(
                        "workspaceId", "repositoryId", "filePath", "query",
                        "candidates", "truncated", "omittedCandidateCount"
                )
        );
    }

    static Map<String, Object> submitDocumentChangeInput(
            ReviewSubmissionProperties limits,
            int maxPathCharacters
    ) {
        Map<String, Object> content = objectSchema(
                Map.of(
                        "schemaVersion", Map.of(
                                "type", "integer",
                                "minimum", 1
                        ),
                        "document", Map.of("type", "object")
                ),
                List.of("document")
        );
        Map<String, Object> operationProperties = new LinkedHashMap<>();
        operationProperties.put(
                "clientOperationId",
                stringProperty(1, 100)
        );
        operationProperties.put("sequenceNumber", Map.of(
                "type", "integer",
                "minimum", 1
        ));
        operationProperties.put("operationType", Map.of(
                "type", "string",
                "enum", List.of(
                        "CREATE_DOCUMENT",
                        "ADD_BLOCK",
                        "UPDATE_BLOCK",
                        "DELETE_BLOCK"
                )
        ));
        operationProperties.put(
                "documentId",
                uuidProperty("Existing document identifier", true)
        );
        operationProperties.put(
                "createdDocumentClientOperationId",
                nullableStringProperty(1, 100)
        );
        operationProperties.put(
                "blockId",
                uuidProperty("Existing Block identifier", true)
        );
        operationProperties.put("baseBlockVersion", Map.of(
                "type", List.of("integer", "null"),
                "minimum", 0
        ));
        operationProperties.put(
                "proposedDocumentTitle",
                nullableStringProperty(1, 200)
        );
        operationProperties.put("proposedDocumentType", Map.of(
                "type", List.of("string", "null")
        ));
        operationProperties.put(
                "proposedParentDocumentId",
                uuidProperty("Optional parent document identifier", true)
        );
        operationProperties.put("proposedBlockType", Map.of(
                "type", "string",
                "enum", List.of("PARAGRAPH", "HEADING", "CODE", "TODO")
        ));
        operationProperties.put(
                "proposedPlainText",
                nullableStringProperty(1, limits.maxProposedCharacters())
        );
        operationProperties.put("proposedContentFormat", Map.of(
                "type", "string",
                "enum", List.of(
                        "MARKDOWN",
                        "TIPTAP_JSON",
                        "PLAIN_TEXT"
                )
        ));
        operationProperties.put("proposedContent", Map.of(
                "oneOf", List.of(content, Map.of("type", "null"))
        ));
        Map<String, Object> operation = objectSchema(
                operationProperties,
                List.of(
                        "clientOperationId",
                        "sequenceNumber",
                        "operationType"
                )
        );

        Map<String, Object> evidenceProperties = new LinkedHashMap<>();
        evidenceProperties.put(
                "clientOperationId",
                nullableStringProperty(1, 100)
        );
        evidenceProperties.put(
                "repositoryId",
                uuidProperty("Evidence repository identifier")
        );
        evidenceProperties.put("filePath", stringProperty(1, maxPathCharacters));
        evidenceProperties.put("startLine", Map.of(
                "type", List.of("integer", "null"),
                "minimum", 1
        ));
        evidenceProperties.put("endLine", Map.of(
                "type", List.of("integer", "null"),
                "minimum", 1
        ));
        evidenceProperties.put(
                "description",
                stringProperty(1, limits.maxDescriptionCharacters())
        );
        Map<String, Object> evidence = objectSchema(
                evidenceProperties,
                List.of("repositoryId", "filePath", "description")
        );

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(
                "workspaceId",
                uuidProperty("DevCollab workspace identifier")
        );
        properties.put(
                "clientRequestId",
                stringProperty(1, 100)
        );
        properties.put(
                "summary",
                stringProperty(1, limits.maxSummaryCharacters())
        );
        properties.put(
                "rationale",
                stringProperty(1, limits.maxRationaleCharacters())
        );
        Map<String, Object> bindingProposalProperties = new LinkedHashMap<>();
        bindingProposalProperties.put("clientBindingProposalId", stringProperty(1, 100));
        bindingProposalProperties.put("sequenceNumber", Map.of("type", "integer", "minimum", 1));
        bindingProposalProperties.put("action", Map.of("type", "string", "enum", List.of("UPSERT_BINDING", "REMOVE_BINDING")));
        bindingProposalProperties.put("repositoryId", uuidProperty("Binding repository identifier"));
        bindingProposalProperties.put("revision", nullableStringProperty(1, 255));
        bindingProposalProperties.put("filePath", stringProperty(1, maxPathCharacters));
        bindingProposalProperties.put("anchorKind", Map.of(
                "type", "string",
                "enum", List.of("FILE", "RANGE", "SYMBOL")
        ));
        bindingProposalProperties.put("symbolKey", nullableStringProperty(1, 1000));
        bindingProposalProperties.put("startLine", Map.of(
                "type", List.of("integer", "null"),
                "minimum", 1
        ));
        bindingProposalProperties.put("endLine", Map.of(
                "type", List.of("integer", "null"),
                "minimum", 1
        ));
        bindingProposalProperties.put("documentId", uuidProperty("Target document identifier", true));
        bindingProposalProperties.put("createdDocumentClientOperationId", nullableStringProperty(1, 100));
        bindingProposalProperties.put("blockId", uuidProperty("Target Block identifier", true));
        bindingProposalProperties.put("createdBlockClientOperationId", nullableStringProperty(1, 100));
        bindingProposalProperties.put("bindingId", uuidProperty("Existing binding identifier", true));
        bindingProposalProperties.put("candidateId", nullableStringProperty(1, 100));
        bindingProposalProperties.put("documentAnchorCandidateId", nullableStringProperty(1, 100));
        bindingProposalProperties.put("bindingRole", Map.of(
                "type", "string",
                "enum", List.of("PRIMARY", "SUPPORTING")
        ));
        bindingProposalProperties.put("bindingOrdinal", Map.of(
                "type", "integer",
                "minimum", 1
        ));
        bindingProposalProperties.put("reason", stringProperty(1, 1000));
        bindingProposalProperties.put("confidence", Map.of(
                "type", List.of("number", "null"),
                "minimum", 0,
                "maximum", 1
        ));

        Map<String, Object> bindingProposal = objectSchema(
                bindingProposalProperties,
                List.of("clientBindingProposalId", "sequenceNumber", "action", "repositoryId", "filePath", "reason")
        );

        properties.put("operations", Map.of(
                "type", "array",
                "minItems", 0,
                "maxItems", limits.maxOperations(),
                "items", operation
        ));
        properties.put("bindingProposals", Map.of(
                "type", "array",
                "maxItems", limits.maxOperations(),
                "items", bindingProposal
        ));
        properties.put("evidence", Map.of(
                "type", "array",
                "maxItems", limits.maxEvidence(),
                "items", evidence
        ));
        return objectSchema(
                properties,
                List.of(
                        "workspaceId",
                        "clientRequestId",
                        "summary",
                        "rationale",
                        "operations"
                )
        );
    }

    static Map<String, Object> submitDocumentChangeOutput() {
        return toolOutputSchema(
                Map.of(
                        "changeRequestId",
                        uuidProperty("Created change request identifier"),
                        "status",
                        Map.of("type", "string", "enum", List.of("PENDING")),
                        "createdAt",
                        Map.of("type", "string", "format", "date-time"),
                        "idempotentReplay",
                        Map.of("type", "boolean"),
                        "workspaceId",
                        uuidProperty("Workspace containing the pending proposal"),
                        "repositoryId",
                        uuidProperty("Single repository referenced by the proposal", true),
                        "documentId",
                        uuidProperty("Single existing document referenced by the proposal", true),
                        "operationCount",
                        Map.of("type", "integer", "minimum", 0),
                        "bindingProposalCount",
                        Map.of("type", "integer", "minimum", 0)
                ),
                List.of(
                        "changeRequestId",
                        "status",
                        "createdAt",
                        "idempotentReplay",
                        "workspaceId",
                        "repositoryId",
                        "documentId",
                        "operationCount",
                        "bindingProposalCount"
                )
        );
    }

    private static Map<String, Object> toolOutputSchema(
            Map<String, Object> successProperties,
            List<String> successRequired
    ) {
        Map<String, Object> properties = new LinkedHashMap<>(successProperties);
        properties.put("error", objectSchema(
                Map.of(
                        "code", Map.of("type", "string"),
                        "message", Map.of("type", "string"),
                        "retryable", Map.of("type", "boolean"),
                        "details", Map.of("type", "object")
                ),
                List.of("code", "message", "retryable", "details")
        ));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("oneOf", List.of(
                Map.of("required", successRequired),
                Map.of("required", List.of("error"))
        ));
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> objectSchema(
            Map<String, Object> properties,
            List<String> required
    ) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> uuidProperty(String description) {
        return uuidProperty(description, false);
    }

    private static Map<String, Object> uuidProperty(String description, boolean nullable) {
        if (nullable) {
            return Map.of(
                    "type", List.of("string", "null"),
                    "format", "uuid",
                    "description", description
            );
        }
        return Map.of(
                "type", "string",
                "format", "uuid",
                "description", description
        );
    }

    private static Map<String, Object> stringProperty(int min, int max) {
        return Map.of(
                "type", "string",
                "minLength", min,
                "maxLength", max
        );
    }

    private static Map<String, Object> nullableStringProperty(int min, int max) {
        return Map.of(
                "type", List.of("string", "null"),
                "minLength", min,
                "maxLength", max
        );
    }
}
