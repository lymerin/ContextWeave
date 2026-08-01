import { http } from './http';

export type GitProvider = 'GITHUB' | 'GITLAB' | 'GITEE' | 'GENERIC';
export type GitChangeType = 'COMMIT' | 'PULL_REQUEST';
export type GitFileChangeType = 'ADDED' | 'MODIFIED' | 'DELETED' | 'RENAMED';
export type CodeAnchorKind = 'FILE' | 'RANGE' | 'SYMBOL';
export type BindingRole = 'PRIMARY' | 'SUPPORTING';

export interface GitRepository {
  id: string;
  workspaceId: string;
  name: string;
  provider: GitProvider;
  remoteUrl: string;
  defaultBranch: string;
  createdAt: string;
  syncStatus: 'REGISTERED' | 'SYNC_PENDING' | 'SYNCING' | 'READY' | 'FAILED';
  lastSyncedCommit: string | null;
  lastSyncedAt: string | null;
  lastSyncError: string | null;
}

export interface GitRepositoryFile {
  id: string;
  path: string;
  blobSha: string;
  sizeBytes: number;
  language: string | null;
  readable: boolean;
}

export interface CodeSymbol {
  filePath: string;
  symbolKey: string;
  language: string;
  symbolKind: string;
  qualifiedName: string;
  simpleName: string;
  signature: string | null;
  parentSymbolKey: string | null;
  startLine: number | null;
  endLine: number | null;
}

export interface GitRepositorySource {
  repositoryId: string;
  commitSha: string | null;
  path: string;
  blobSha: string;
  sizeBytes: number;
  language: string | null;
  readable: boolean;
  content: string | null;
  symbols: CodeSymbol[];
}

export interface GitFileDiff {
  id: string;
  path: string;
  oldPath: string | null;
  changeType: GitFileChangeType;
  additions: number;
  deletions: number;
  binaryFile: boolean;
  patchExcerpt: string | null;
}

export interface GitChange {
  id: string;
  repositoryId: string;
  changeType: GitChangeType;
  externalId: string;
  title: string;
  commitSha: string;
  baseRef: string | null;
  headRef: string | null;
  authorName: string | null;
  authorEmail: string | null;
  authoredAt: string | null;
  committerName: string | null;
  committerEmail: string | null;
  parentCommitSha: string | null;
  webUrl: string | null;
  occurredAt: string;
  duplicate: boolean;
  files: GitFileDiff[];
}

export interface CodeDocumentBinding {
  id: string;
  workspaceId: string;
  repositoryId: string;
  documentId: string;
  blockId: string | null;
  targetKey: string;
  pathPattern: string;
  revision: string | null;
  anchorKind: CodeAnchorKind;
  symbolKey: string | null;
  startLine: number | null;
  endLine: number | null;
  bindingRole?: BindingRole;
  bindingOrdinal?: number;
  createdBy: string;
  createdAt: string;
}

export interface CodeBindingQueryItem {
  bindingId: string;
  workspaceId: string;
  repositoryId: string;
  revision: string | null;
  anchorKind: CodeAnchorKind;
  symbolKey: string | null;
  startLine: number | null;
  endLine: number | null;
  bindingRole?: BindingRole;
  bindingOrdinal?: number;
  documentId: string;
  blockId: string | null;
  targetKey: string;
  pathPattern: string;
  documentTitle: string | null;
}

export interface CodeBindingQueryResult {
  workspaceId: string;
  repositoryId: string;
  filePath: string;
  fileHasBindings: boolean;
  bindings: CodeBindingQueryItem[];
  truncated: boolean;
  omittedBindingCount: number;
}

export async function listGitRepositories(workspaceId: string) {
  const { data } = await http.get<GitRepository[]>(
    `/workspaces/${workspaceId}/git/repositories`,
  );
  return data;
}

export async function registerGitRepository(
  workspaceId: string,
  payload: {
    name: string;
    provider: GitProvider;
    remoteUrl: string;
    defaultBranch: string;
  },
) {
  const { data } = await http.post<GitRepository>(
    `/workspaces/${workspaceId}/git/repositories`,
    payload,
  );
  return data;
}

export async function listGitChanges(workspaceId: string, repositoryId: string) {
  const { data } = await http.get<GitChange[]>(
    `/workspaces/${workspaceId}/git/repositories/${repositoryId}/changes`,
  );
  return data;
}

export async function syncGitRepository(workspaceId: string, repositoryId: string) {
  const { data } = await http.post<GitRepository>(
    `/workspaces/${workspaceId}/git/repositories/${repositoryId}/sync`,
  );
  return data;
}

export async function deleteGitRepository(workspaceId: string, repositoryId: string) {
  await http.delete(`/workspaces/${workspaceId}/git/repositories/${repositoryId}`);
}

export async function listGitRepositoryFiles(workspaceId: string, repositoryId: string) {
  const { data } = await http.get<GitRepositoryFile[]>(
    `/workspaces/${workspaceId}/git/repositories/${repositoryId}/files`,
  );
  return data;
}

export async function getGitRepositorySource(
  workspaceId: string,
  repositoryId: string,
  path: string,
) {
  const { data } = await http.get<GitRepositorySource>(
    `/workspaces/${workspaceId}/git/repositories/${repositoryId}/source`,
    { params: { path } },
  );
  return data;
}

export interface GitMarkdownImportResult {
  importedDocuments: number;
  skippedDocuments: number;
  unavailableDocuments: number;
}

export async function importGitMarkdownDocuments(
  workspaceId: string,
  repositoryId: string,
) {
  const { data } = await http.post<GitMarkdownImportResult>(
    `/workspaces/${workspaceId}/git/repositories/${repositoryId}/documents/import`,
  );
  return data;
}

export async function ingestGitChange(
  workspaceId: string,
  repositoryId: string,
  payload: {
    changeType: GitChangeType;
    externalId: string;
    title: string;
    commitSha: string;
    baseRef?: string | null;
    headRef?: string | null;
    authorName?: string | null;
    authorEmail?: string | null;
    authoredAt?: string | null;
    committerName?: string | null;
    committerEmail?: string | null;
    parentCommitSha?: string | null;
    webUrl?: string | null;
    occurredAt: string;
    files: Array<{
      path: string;
      oldPath?: string | null;
      changeType: GitFileChangeType;
      additions: number;
      deletions: number;
      binaryFile?: boolean;
      patchExcerpt?: string | null;
    }>;
  },
) {
  const { data } = await http.post<GitChange>(
    `/workspaces/${workspaceId}/git/repositories/${repositoryId}/changes`,
    payload,
  );
  return data;
}

export async function listCodeBindings(
  documentId: string,
  options?: {
    revision?: string;
    includeLegacy?: boolean;
    blockId?: string;
  },
) {
  const { data } = await http.get<CodeDocumentBinding[]>(
    `/documents/${documentId}/code-bindings`,
    { params: options },
  );
  return data;
}

export async function createCodeBinding(
  documentId: string,
  payload: {
    repositoryId: string;
    blockId?: string | null;
    pathPattern: string;
    revision?: string | null;
    anchorKind?: CodeAnchorKind;
    symbolKey?: string | null;
    startLine?: number | null;
    endLine?: number | null;
    bindingRole?: BindingRole;
    bindingOrdinal?: number;
  },
) {
  const { data } = await http.post<CodeDocumentBinding>(
    `/documents/${documentId}/code-bindings`,
    payload,
  );
  return data;
}

export async function deleteCodeBinding(bindingId: string) {
  await http.delete(`/code-bindings/${bindingId}`);
}

export async function queryCodeBindings(
  workspaceId: string,
  repositoryId: string,
  revision: string | undefined,
  filePath: string,
  includeLegacy = true,
  maxBindings?: number,
) {
  const { data } = await http.get<CodeBindingQueryResult>(
    `/workspaces/${workspaceId}/repositories/${repositoryId}/code-bindings`,
    { params: { revision, filePath, includeLegacy, maxBindings } },
  );
  return data;
}
