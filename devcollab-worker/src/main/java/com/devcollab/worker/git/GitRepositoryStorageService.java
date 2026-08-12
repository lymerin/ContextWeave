package com.devcollab.worker.git;

import com.devcollab.worker.observability.RuntimeMemoryProfiler;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.io.DisabledOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class GitRepositoryStorageService {

    private static final long MAX_READABLE_SOURCE_BYTES = 512 * 1024;

    private static final int PATCH_EXCERPT_LIMIT = 8_000;

    private final GitRepositoryStorageProperties properties;
    private final GitRepositoryProjectionStore store;
    private final JavaCodeGraphAnalyzer codeGraphAnalyzer;
    private RuntimeMemoryProfiler memoryProfiler;

    public GitRepositoryStorageService(
            GitRepositoryStorageProperties properties,
            GitRepositoryProjectionStore store,
            JavaCodeGraphAnalyzer codeGraphAnalyzer
    ) {
        this.properties = properties;
        this.store = store;
        this.codeGraphAnalyzer = codeGraphAnalyzer;
    }

    @Autowired(required = false)
    void setMemoryProfiler(RuntimeMemoryProfiler memoryProfiler) {
        this.memoryProfiler = memoryProfiler;
    }

    public void synchronize(
            UUID workspaceId,
            UUID repositoryId,
            String remoteUrl,
            String defaultBranch
    ) {
        if (!store.markSyncing(repositoryId)) {
            return;
        }
        Path repositoryDirectory = repositoryDirectory(workspaceId, repositoryId);
        RuntimeMemoryProfiler.Stage syncStage = stage(
                "REPOSITORY_SYNC", repositoryId, null
        );
        try {
            validateRemote(remoteUrl);
            Files.createDirectories(repositoryDirectory.getParent());
            try (Git git = openOrClone(
                    repositoryId, repositoryDirectory, remoteUrl, defaultBranch
            )) {
                RuntimeMemoryProfiler.Stage sizeStage = stage(
                        "REPOSITORY_SIZE_CHECK", repositoryId, null
                );
                try {
                    verifyRepositorySize(repositoryDirectory);
                } catch (RuntimeException | IOException exception) {
                    if (sizeStage != null) sizeStage.failed(exception);
                    throw exception;
                } finally {
                    if (sizeStage != null) sizeStage.close();
                }
                Repository repository = git.getRepository();
                String head = repository.resolve("HEAD").name();
                GitRepositoryProjectionStore.ProjectionCheckpoint checkpoint =
                        store.readProjectionCheckpoint(repositoryId);
                if (isUnchangedProjection(
                        head, checkpoint.lastSyncedCommit(),
                        checkpoint.projectionVersion()
                )) {
                    store.markReady(repositoryId, head);
                    if (syncStage != null) {
                        syncStage.attribute("fastReturn", true);
                        syncStage.attribute("head", head);
                    }
                    return;
                }
                List<GitRepositoryFileProjection> files;
                RuntimeMemoryProfiler.Stage scanStage = stage(
                        "FILE_SCAN", repositoryId, head
                );
                try {
                    files = scanFiles(repository);
                    if (scanStage != null) {
                        scanStage.attribute("fileCount", files.size());
                        scanStage.attribute("javaFileCount", files.stream()
                                .filter(file -> "Java".equals(file.language()))
                                .count());
                        scanStage.attribute("sourceBytes", files.stream()
                                .mapToLong(GitRepositoryFileProjection::sizeBytes).sum());
                    }
                } catch (RuntimeException | IOException exception) {
                    if (scanStage != null) scanStage.failed(exception);
                    throw exception;
                } finally {
                    if (scanStage != null) scanStage.close();
                }
                List<GitCommitProjection> commits;
                RuntimeMemoryProfiler.Stage commitStage = stage(
                        "COMMIT_SCAN", repositoryId, head
                );
                try {
                    commits = scanCommits(git, repository);
                    if (commitStage != null) {
                        commitStage.attribute("commitCount", commits.size());
                        commitStage.attribute("diffCount", commits.stream()
                                .mapToLong(commit -> commit.files().size()).sum());
                    }
                } catch (Exception exception) {
                    if (commitStage != null) commitStage.failed(exception);
                    throw exception;
                } finally {
                    if (commitStage != null) commitStage.close();
                }
                CodeGraphProjection codeGraph;
                RuntimeMemoryProfiler.Stage graphStage = stage(
                        "CODE_GRAPH", repositoryId, head
                );
                try {
                    codeGraph = codeGraphAnalyzer.analyze(repositoryDirectory, files);
                    if (graphStage != null) {
                        graphStage.attribute("fileCount", files.size());
                        graphStage.attribute("symbolCount", codeGraph.symbols().size());
                        graphStage.attribute("dependencyCount",
                                codeGraph.fileDependencies().size()
                                        + codeGraph.symbolDependencies().size());
                    }
                } catch (RuntimeException exception) {
                    if (graphStage != null) graphStage.failed(exception);
                    throw exception;
                } finally {
                    if (graphStage != null) graphStage.close();
                }
                RuntimeMemoryProfiler.Stage filesStage = stage(
                        "DB_FILES", repositoryId, head
                );
                try {
                    store.replaceFiles(repositoryId, files);
                } catch (RuntimeException exception) {
                    if (filesStage != null) filesStage.failed(exception);
                    throw exception;
                } finally {
                    if (filesStage != null) filesStage.close();
                }
                RuntimeMemoryProfiler.Stage commitsStage = stage(
                        "DB_COMMITS", repositoryId, head
                );
                try {
                    store.saveCommits(repositoryId, commits);
                } catch (RuntimeException exception) {
                    if (commitsStage != null) commitsStage.failed(exception);
                    throw exception;
                } finally {
                    if (commitsStage != null) commitsStage.close();
                }
                RuntimeMemoryProfiler.Stage graphDbStage = stage(
                        "DB_CODE_GRAPH", repositoryId, head
                );
                try {
                    store.replaceCodeGraph(repositoryId, codeGraph);
                } catch (RuntimeException exception) {
                    if (graphDbStage != null) graphDbStage.failed(exception);
                    throw exception;
                } finally {
                    if (graphDbStage != null) graphDbStage.close();
                }
                store.markReady(repositoryId, head);
                if (syncStage != null) {
                    syncStage.attribute("fileCount", files.size());
                    syncStage.attribute("commitCount", commits.size());
                }
            }
        } catch (Exception exception) {
            if (syncStage != null) syncStage.failed(exception);
            store.markFailed(repositoryId, rootMessage(exception));
            throw new IllegalStateException(
                    "Git repository synchronization failed repository=" + repositoryId,
                    exception
            );
        } finally {
            if (syncStage != null) syncStage.close();
        }
    }

    private RuntimeMemoryProfiler.Stage stage(
            String name,
            UUID repositoryId,
            String revision
    ) {
        return memoryProfiler == null ? null : memoryProfiler.stage(
                name, null, repositoryId.toString(), revision, null
        );
    }

    public void delete(UUID workspaceId, UUID repositoryId) {
        Path directory = repositoryDirectory(workspaceId, repositoryId).getParent();
        if (!Files.exists(directory)) {
            try {
                deleteWorkspaceDirectoryIfEmpty(workspaceId);
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Git workspace directory cleanup failed workspace="
                                + workspaceId,
                        exception
                );
            }
            return;
        }
        try {
            deleteDirectory(directory);
            deleteWorkspaceDirectoryIfEmpty(workspaceId);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Git repository directory deletion failed repository=" + repositoryId,
                    exception
            );
        }
    }

    private void deleteWorkspaceDirectoryIfEmpty(UUID workspaceId)
            throws IOException {
        Path root = properties.dataRoot().toAbsolutePath().normalize();
        Path workspaceDirectory = root.resolve(workspaceId.toString())
                .toAbsolutePath()
                .normalize();
        if (workspaceDirectory.equals(root)
                || !workspaceDirectory.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Workspace storage path escapes data root"
            );
        }
        try {
            Files.deleteIfExists(workspaceDirectory);
        } catch (DirectoryNotEmptyException ignored) {
            // Other repositories in this workspace still need the directory.
        }
    }

    Path repositoryDirectory(UUID workspaceId, UUID repositoryId) {
        Path root = properties.dataRoot().toAbsolutePath().normalize();
        Path target = root.resolve(workspaceId.toString())
                .resolve(repositoryId.toString())
                .resolve("repository")
                .normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Repository storage path escapes data root");
        }
        return target;
    }

    private Git openOrClone(
            UUID repositoryId,
            Path directory,
            String remoteUrl,
            String defaultBranch
    ) throws Exception {
        RuntimeMemoryProfiler.Stage transferStage = stage(
                "GIT_TRANSFER", repositoryId, null
        );
        try {
            if (Files.isDirectory(directory.resolve(".git"))) {
                if (transferStage != null) {
                    transferStage.attribute("operation", "fetch");
                }
                Git git = Git.open(directory.toFile());
                git.fetch()
                        .setRemoveDeletedRefs(true)
                        .setTimeout(properties.timeoutSeconds())
                        .call();
                String remoteRef = "refs/remotes/origin/" + defaultBranch;
                if (git.getRepository().resolve(remoteRef) == null) {
                    git.close();
                    throw new IllegalArgumentException(
                            "Default branch does not exist: " + defaultBranch
                    );
                }
                git.reset().setMode(ResetCommand.ResetType.HARD)
                        .setRef(remoteRef).call();
                return git;
            }
            if (Files.exists(directory)) {
                deleteDirectory(directory);
            }
            if (transferStage != null) {
                transferStage.attribute("operation", "clone");
            }
            return Git.cloneRepository()
                    .setURI(remoteUrl)
                    .setDirectory(directory.toFile())
                    .setBranch(defaultBranch)
                    .setCloneAllBranches(false)
                    .setTimeout(properties.timeoutSeconds())
                    .call();
        } catch (Exception exception) {
            if (transferStage != null) transferStage.failed(exception);
            throw exception;
        } finally {
            if (transferStage != null) transferStage.close();
        }
    }

    List<GitRepositoryFileProjection> scanFiles(Repository repository)
            throws IOException {
        ObjectId treeId = repository.resolve("HEAD^{tree}");
        if (treeId == null) {
            throw new IllegalStateException("Repository HEAD tree is unavailable");
        }
        List<GitRepositoryFileProjection> files = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(treeId);
            walk.setRecursive(true);
            while (walk.next()) {
                if (files.size() >= properties.maxFiles()) {
                    throw new IllegalStateException(
                            "Repository file count exceeds limit " + properties.maxFiles()
                    );
                }
                ObjectId objectId = walk.getObjectId(0);
                ObjectLoader loader = repository.open(objectId);
                String path = walk.getPathString();
                files.add(new GitRepositoryFileProjection(
                        path, objectId.name(), loader.getSize(), language(path),
                        readableContent(path, loader)
                ));
            }
        }
        return List.copyOf(files);
    }

    private String readableContent(String path, ObjectLoader loader)
            throws IOException {
        if (!isReadableText(path)
                || loader.getSize() > MAX_READABLE_SOURCE_BYTES) {
            return null;
        }
        byte[] bytes = loader.getBytes((int) MAX_READABLE_SOURCE_BYTES);
        for (byte value : bytes) {
            if (value == 0) {
                return null;
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            return null;
        }
    }

    private boolean isReadableText(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return language(path) != null
                || lower.endsWith(".xml")
                || lower.endsWith(".json")
                || lower.endsWith(".properties")
                || lower.endsWith(".html")
                || lower.endsWith(".css")
                || lower.endsWith(".scss")
                || lower.endsWith(".sh")
                || lower.endsWith(".ps1")
                || lower.endsWith(".gradle")
                || lower.endsWith(".toml")
                || lower.endsWith(".txt")
                || lower.endsWith(".proto")
                || lower.endsWith("dockerfile")
                || lower.endsWith("makefile");
    }

    List<GitCommitProjection> scanCommits(Git git, Repository repository)
            throws Exception {
        List<GitCommitProjection> commits = new ArrayList<>();
        Iterable<RevCommit> log = git.log()
                .setMaxCount(properties.maxCommits())
                .call();
        try (RevWalk revWalk = new RevWalk(repository);
             DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(repository);
            formatter.setDetectRenames(true);
            for (RevCommit commit : log) {
                AbstractTreeIterator previous = commit.getParentCount() == 0
                        ? new EmptyTreeIterator()
                        : treeIterator(repository, revWalk.parseCommit(
                                commit.getParent(0).getId()
                        ));
                AbstractTreeIterator current = treeIterator(repository, commit);
                List<GitDiffProjection> diffs = new ArrayList<>();
                for (DiffEntry entry : formatter.scan(previous, current)) {
                    diffs.add(toProjection(repository, formatter, entry));
                }
                var author = commit.getAuthorIdent();
                var committer = commit.getCommitterIdent();
                commits.add(new GitCommitProjection(
                        commit.name(), truncate(commit.getShortMessage(), 500),
                        author == null ? null : author.getName(),
                        author == null ? null : author.getEmailAddress(),
                        author == null ? null : author.getWhenAsInstant(),
                        committer == null ? null : committer.getName(),
                        committer == null ? null : committer.getEmailAddress(),
                        committer == null
                                ? Instant.ofEpochSecond(commit.getCommitTime())
                                : committer.getWhenAsInstant(),
                        commit.getParentCount() == 0
                                ? null : commit.getParent(0).name(),
                        diffs
                ));
            }
        }
        return List.copyOf(commits);
    }

    private AbstractTreeIterator treeIterator(
            Repository repository,
            RevCommit commit
    ) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (var reader = repository.newObjectReader()) {
            parser.reset(reader, commit.getTree().getId());
        }
        return parser;
    }

    private GitDiffProjection toProjection(
            Repository repository,
            DiffFormatter formatter,
            DiffEntry entry
    ) throws IOException {
        FileHeader header = formatter.toFileHeader(entry);
        boolean binary = header.getPatchType() == FileHeader.PatchType.BINARY;
        int additions = 0;
        int deletions = 0;
        if (!binary) {
            for (var edit : header.toEditList()) {
                additions += edit.getLengthB();
                deletions += edit.getLengthA();
            }
        }
        String patch = binary ? null : patchExcerpt(repository, entry);
        return switch (entry.getChangeType()) {
            case ADD -> new GitDiffProjection(
                    entry.getNewPath(), null, "ADDED",
                    additions, deletions, binary, patch
            );
            case DELETE -> new GitDiffProjection(
                    entry.getOldPath(), null, "DELETED",
                    additions, deletions, binary, patch
            );
            case RENAME, COPY -> new GitDiffProjection(
                    entry.getNewPath(), entry.getOldPath(), "RENAMED",
                    additions, deletions, binary, patch
            );
            case MODIFY -> new GitDiffProjection(
                    entry.getNewPath(), null, "MODIFIED",
                    additions, deletions, binary, patch
            );
        };
    }

    private String patchExcerpt(Repository repository, DiffEntry entry)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DiffFormatter patchFormatter = new DiffFormatter(output)) {
            patchFormatter.setRepository(repository);
            patchFormatter.format(entry);
            patchFormatter.flush();
        }
        String patch = output.toString(StandardCharsets.UTF_8);
        if (patch.isBlank()) {
            return null;
        }
        return patch.length() <= PATCH_EXCERPT_LIMIT
                ? patch : patch.substring(0, PATCH_EXCERPT_LIMIT);
    }

    private void validateRemote(String remoteUrl) {
        URI uri;
        try {
            uri = URI.create(remoteUrl);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid Git remote URL", exception);
        }
        boolean allowedHost = properties.allowedHosts().stream()
                .anyMatch(host -> host.equalsIgnoreCase(uri.getHost()));
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getUserInfo() != null
                || !allowedHost
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getPath() == null || uri.getPath().isBlank()) {
            throw new IllegalArgumentException(
                    "Only public HTTPS repositories on allowed hosts are supported"
            );
        }
    }

    private void verifyRepositorySize(Path directory) throws IOException {
        long total;
        try (var paths = Files.walk(directory)) {
            total = paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException exception) {
                    throw new RepositorySizeException(exception);
                }
            }).sum();
        } catch (RepositorySizeException exception) {
            throw (IOException) exception.getCause();
        }
        if (total > properties.maxRepositoryBytes()) {
            deleteDirectory(directory);
            throw new IllegalStateException(
                    "Repository size exceeds limit " + properties.maxRepositoryBytes()
            );
        }
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                clearReadOnly(path);
                Files.deleteIfExists(path);
            }
        }
    }

    private void clearReadOnly(Path path) throws IOException {
        try {
            Boolean readOnly = (Boolean) Files.getAttribute(path, "dos:readonly");
            if (Boolean.TRUE.equals(readOnly)) {
                Files.setAttribute(path, "dos:readonly", false);
            }
        } catch (UnsupportedOperationException ignored) {
            // Non-Windows file systems do not expose DOS attributes.
        }
    }

    private String language(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return null;
        }
        return switch (lower.substring(dot + 1)) {
            case "java" -> "Java";
            case "kt", "kts" -> "Kotlin";
            case "ts", "tsx" -> "TypeScript";
            case "js", "jsx", "mjs" -> "JavaScript";
            case "py" -> "Python";
            case "go" -> "Go";
            case "rs" -> "Rust";
            case "vue" -> "Vue";
            case "sql" -> "SQL";
            case "md" -> "Markdown";
            case "markdown" -> "Markdown";
            case "yml", "yaml" -> "YAML";
            case "xml" -> "XML";
            case "json" -> "JSON";
            case "html" -> "HTML";
            case "css", "scss" -> "CSS";
            case "sh" -> "Shell";
            case "ps1" -> "PowerShell";
            case "proto" -> "Protocol Buffers";
            default -> null;
        };
    }

    /**
     * Fast-return predicate: the fetched HEAD is exactly the last successfully
     * projected commit and the projection was built with the current version,
     * so no scan, AST or projection rebuild is needed.
     */
    static boolean isUnchangedProjection(
            String head,
            String lastSyncedCommit,
            Integer projectionVersion
    ) {
        return head != null
                && lastSyncedCommit != null
                && head.equals(lastSyncedCommit)
                && Integer.valueOf(
                        GitRepositoryProjectionStore.CURRENT_PROJECTION_VERSION
                ).equals(projectionVersion);
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String truncate(String value, int maxCodePoints) {
        if (value.codePointCount(0, value.length()) <= maxCodePoints) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maxCodePoints));
    }

    private static class RepositorySizeException extends RuntimeException {
        RepositorySizeException(IOException cause) {
            super(cause);
        }
    }
}
