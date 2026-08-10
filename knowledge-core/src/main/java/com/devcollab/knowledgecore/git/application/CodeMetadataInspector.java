package com.devcollab.knowledgecore.git.application;

import com.devcollab.knowledgecore.git.domain.GitRepositoryFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CodeMetadataInspector {

    private static final Pattern JAVA_PACKAGE =
            Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final Pattern JAVA_IMPORT =
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.*]+)\\s*;");
    private static final Pattern JAVA_TYPE =
            Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
    private static final Pattern JAVA_ANNOTATION =
            Pattern.compile("@([A-Z][A-Za-z0-9_]*)");
    private static final Pattern PYTHON_IMPORT =
            Pattern.compile("(?m)^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");
    private static final Pattern PYTHON_SYMBOL =
            Pattern.compile("(?m)^\\s*(?:async\\s+)?(?:class|def)\\s+(\\w+)");
    private static final Pattern JS_IMPORT =
            Pattern.compile(
                    "(?m)(?:import\\s+(?:[^\"']*?\\s+from\\s+)?|require\\s*\\()"
                            + "[\"']([^\"']+)[\"']"
            );
    private static final Pattern JS_EXPORT =
            Pattern.compile("(?m)\\bexport\\s+(?:default\\s+)?(?:class|function|const|let|var|interface|type)\\s+(\\w+)");
    private static final Pattern JS_SYMBOL =
            Pattern.compile("(?m)^(?:export\\s+)?(?:default\\s+)?(?:class|function|const|let|var|interface|type)\\s+(\\w+)");
    private static final Pattern ROUTE =
            Pattern.compile("(?:@(?:Get|Post|Put|Patch|Delete)Mapping\\s*\\(([^)]*)\\)|\\bpath\\s*:\\s*[\"']([^\"']+)[\"'])");

    public CodeMetadataBatchResult.FileMetadata inspect(GitRepositoryFile file) {
        String content = file.contentText();
        if (content == null) {
            return failed(file, "SOURCE_NOT_READABLE");
        }
        try {
            String extension = extension(file.path());
            return switch (extension) {
                case "java", "kt", "kts" -> inspectJava(file, content);
                case "py" -> inspectPython(file, content);
                case "ts", "tsx", "js", "jsx", "vue" -> inspectJavaScript(file, content);
                default -> failed(file, "UNSUPPORTED_LANGUAGE");
            };
        } catch (RuntimeException ignored) {
            return failed(file, "PARSE_FAILED");
        }
    }

    private CodeMetadataBatchResult.FileMetadata inspectJava(
            GitRepositoryFile file,
            String content
    ) {
        String packageName = firstGroup(JAVA_PACKAGE, content);
        List<String> imports = groups(JAVA_IMPORT, content, 1);
        List<String> symbols = groups(JAVA_TYPE, content, 1);
        List<String> annotations = groups(JAVA_ANNOTATION, content, 1);
        List<String> roles = inferRoles(file.path(), annotations, content);
        return parsed(
                file, packageName, imports, List.of(), symbols, annotations,
                groups(ROUTE, content, 1, 2), roles
        );
    }

    private CodeMetadataBatchResult.FileMetadata inspectPython(
            GitRepositoryFile file,
            String content
    ) {
        List<String> imports = groups(PYTHON_IMPORT, content, 1, 2);
        List<String> symbols = groups(PYTHON_SYMBOL, content, 1);
        return parsed(
                file, pythonPackage(file.path()), imports, List.of(), symbols,
                List.of(), groups(ROUTE, content, 1, 2),
                inferRoles(file.path(), List.of(), content)
        );
    }

    private CodeMetadataBatchResult.FileMetadata inspectJavaScript(
            GitRepositoryFile file,
            String content
    ) {
        List<String> exported = groups(JS_EXPORT, content, 1);
        List<String> symbols = groups(JS_SYMBOL, content, 1);
        return parsed(
                file, null, groups(JS_IMPORT, content, 1), exported, symbols,
                List.of(), groups(ROUTE, content, 1, 2),
                inferRoles(file.path(), List.of(), content)
        );
    }

    private CodeMetadataBatchResult.FileMetadata parsed(
            GitRepositoryFile file,
            String packageName,
            List<String> imports,
            List<String> exports,
            List<String> symbols,
            List<String> annotations,
            List<String> routes,
            List<String> roles
    ) {
        return new CodeMetadataBatchResult.FileMetadata(
                file.path(), file.blobSha(), file.language(), packageName, moduleKey(file.path()),
                layerHint(roles), imports, exports, symbols, annotations, routes,
                roles, "PARSED", null
        );
    }

    private CodeMetadataBatchResult.FileMetadata failed(
            GitRepositoryFile file,
            String errorCode
    ) {
        return new CodeMetadataBatchResult.FileMetadata(
                file.path(), file.blobSha(), file.language(), null, moduleKey(file.path()), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                "FAILED", errorCode
        );
    }

    private List<String> inferRoles(
            String path,
            List<String> annotations,
            String content
    ) {
        String normalized = path.toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        Set<String> roles = new LinkedHashSet<>();
        if (annotations.stream().anyMatch(value -> value.contains("Controller"))
                || fileName.endsWith("controller.java")) {
            roles.add("CONTROLLER");
        }
        if (fileName.endsWith("service.java") || fileName.endsWith("_service.py")) {
            roles.add("SERVICE");
        }
        if (fileName.endsWith("repository.java") || fileName.endsWith("dao.java")) {
            roles.add("REPOSITORY");
        }
        if (normalized.contains("/security/")
                || fileName.contains("security")
                || fileName.contains("authenticationfilter")
                || fileName.contains("jwtfilter")) {
            roles.add("SECURITY");
        }
        if (fileName.contains("filter")) {
            roles.add("FILTER");
        }
        if (fileName.contains("worker") || fileName.contains("consumer")) {
            roles.add("WORKER");
        }
        if (fileName.endsWith("api.ts") || fileName.endsWith("api.js")
                || (normalized.contains("/api/") && !normalized.endsWith(".java"))) {
            roles.add("API_CLIENT");
        }
        if (fileName.contains("gateway") || fileName.contains("client")) {
            roles.add("INTEGRATION");
        }
        if (normalized.contains("/config/") || annotations.contains("Configuration")) {
            roles.add("CONFIG");
        }
        if (normalized.contains("/views/") || normalized.endsWith(".vue")) {
            roles.add("VIEW");
        }
        if (normalized.contains("/stores/") || fileName.contains("store")) {
            roles.add("STORE");
        }
        if (fileName.contains("model") || fileName.contains("entity")) {
            roles.add("MODEL");
        }
        if (fileName.contains("dto") || normalized.contains("/dto/")) {
            roles.add("DTO");
        }
        if (normalized.contains("/test/") || fileName.contains("test")) {
            roles.add("TEST");
        }
        if (content.contains("@KafkaListener")) {
            roles.add("CONSUMER");
        }
        return List.copyOf(roles);
    }

    private String layerHint(List<String> roles) {
        return roles.isEmpty() ? null : roles.get(0);
    }

    private String moduleKey(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.indexOf('/');
        return slash < 0 ? "" : normalized.substring(0, slash);
    }

    private String pythonPackage(String path) {
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? null : normalized.substring(0, slash).replace('/', '.');
    }

    private String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String firstGroup(Pattern pattern, String content) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    private List<String> groups(Pattern pattern, String content, int... indexes) {
        Set<String> values = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(content);
        while (matcher.find() && values.size() < 200) {
            for (int index : indexes) {
                String value = matcher.group(index);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                    break;
                }
            }
        }
        return new ArrayList<>(values);
    }
}
