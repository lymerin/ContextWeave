package com.devcollab.mcp.capability.tool;

import com.devcollab.mcp.error.McpToolErrorCode;
import com.devcollab.mcp.error.McpToolException;

import java.util.Map;
import java.util.List;
import java.util.UUID;

final class McpToolArguments {

    private McpToolArguments() {
    }

    static UUID requiredUuid(Map<String, Object> arguments, String name) {
        String value = requiredString(arguments, name);
        return parseUuid(value, name);
    }

    static UUID optionalUuid(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " must be a valid UUID");
        }
        return parseUuid(text, name);
    }

    private static UUID parseUuid(String value, String name) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new McpToolException(
                    McpToolErrorCode.INVALID_ARGUMENT,
                    name + " must be a valid UUID"
            );
        }
    }

    static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " is required");
        }
        return text;
    }

    static Integer optionalInteger(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " must be an integer");
    }

    static boolean optionalBoolean(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " must be a boolean");
    }

    static String optionalString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " must be a string");
    }

    static List<String> requiredStringList(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " is required");
        }
        return values.stream().map(item -> {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new McpToolException(
                        McpToolErrorCode.INVALID_ARGUMENT,
                        name + " must contain non-blank strings"
                );
            }
            return text;
        }).toList();
    }

    static List<String> stringList(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof List<?> values)) {
            throw new McpToolException(McpToolErrorCode.INVALID_ARGUMENT, name + " is required");
        }
        return values.stream().map(item -> {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new McpToolException(
                        McpToolErrorCode.INVALID_ARGUMENT,
                        name + " must contain non-blank strings"
                );
            }
            return text;
        }).toList();
    }
}
