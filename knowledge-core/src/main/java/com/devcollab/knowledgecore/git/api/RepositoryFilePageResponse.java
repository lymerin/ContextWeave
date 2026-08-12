package com.devcollab.knowledgecore.git.api;

import com.devcollab.knowledgecore.git.application.RepositoryFilePageResult;

import java.util.List;
import java.util.UUID;

public record RepositoryFilePageResponse(
        UUID workspaceId,
        UUID repositoryId,
        String revision,
        String pathPrefix,
        boolean recursive,
        List<FileItem> files,
        String nextCursor,
        boolean hasMore
) {
    public static RepositoryFilePageResponse from(RepositoryFilePageResult result) {
        return new RepositoryFilePageResponse(
                result.workspaceId(), result.repositoryId(), result.revision(), result.pathPrefix(),
                result.recursive(),
                result.files().stream().map(file -> new FileItem(
                        file.path(),
                        file.path().substring(file.path().lastIndexOf('/') + 1),
                        extension(file.path()),
                        file.sizeBytes(),
                        file.language(),
                        file.contentText() != null
                )).toList(),
                result.nextCursor(), result.hasMore()
        );
    }

    private static String extension(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)
                : "";
    }

    public record FileItem(
            String filePath,
            String fileName,
            String extension,
            long sizeBytes,
            String language,
            boolean readable
    ) {
    }
}
