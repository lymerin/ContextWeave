import type {
  CodeBindingContextItem,
  CodeBindingQueryItem,
  CodeDocumentBinding,
  GitRepositorySource,
} from '@/api/git';
import type { LinkedDocumentChoice, LinkedFixture } from '@/types/linkedWorkbench';
import type { BindingDisplayState } from '@/types/linkedWorkbench';
import { normalizeRepositoryPath } from "./repositoryTree";

export interface BuildBindingFixtureInput {
  repositoryId: string;
  branch: string;
  commitSha: string;
  source: GitRepositorySource;
  bindings: CodeBindingQueryItem[];
  allowCrossFile?: boolean;
  activeDocumentId?: string | null;
  blockSortOrders?: ReadonlyMap<string, number>;
}

export function buildBindingFixture(input: BuildBindingFixtureInput): LinkedFixture {
  const selectedBindings = displayableBindings(input);
  const codeAnchors = selectedBindings.map(binding => ({
    id: `binding-anchor-${binding.bindingId}`,
    bindingId: binding.bindingId,
    repositoryId: binding.repositoryId,
    revision: binding.revision,
    branch: input.branch,
    commitSha: binding.revision || input.commitSha,
    filePath: binding.matchedFilePath || binding.pathPattern,
    language: input.source.language || 'text',
    symbolName: binding.symbolKey || (
      binding.blockId ? 'Block 关联文件' : '文档关联文件'
    ),
    qualifiedSymbol: binding.symbolKey || undefined,
    anchorKind: binding.revision === null ? 'FILE' as const : binding.anchorKind,
    startLine: binding.revision === null ? null : binding.startLine,
    endLine: binding.revision === null ? null : binding.endLine,
    status: 'VALID' as const,
    bindingDisplayState: bindingDisplayState(binding) as 'precise' | 'weak',
  }));
  const links = selectedBindings.map((binding, index) => ({
    id: `binding-link-${binding.bindingId}`,
    bindingId: binding.bindingId,
    codeAnchorId: codeAnchors[index].id,
    repositoryId: binding.repositoryId,
    revision: binding.revision,
    filePath: binding.matchedFilePath || binding.pathPattern,
    documentId: binding.documentId,
    blockId: binding.blockId,
    documentTitle: binding.documentTitle,
    anchorKind: binding.revision === null ? 'FILE' as const : binding.anchorKind,
    symbolKey: binding.revision === null ? null : binding.symbolKey,
    startLine: binding.revision === null ? null : binding.startLine,
    endLine: binding.revision === null ? null : binding.endLine,
    bindingRole: binding.bindingRole ?? 'PRIMARY',
    bindingOrdinal: binding.bindingOrdinal ?? 1,
    relationType: 'DESCRIBES' as const,
    bindingDisplayState: bindingDisplayState(binding) as 'precise' | 'weak',
  }));
  return { codeAnchors, links, issues: [], evidence: [] };
}

/**
 * Trust the backend response: path matching and revision filtering are
 * already done server-side.  The only client-side filters are:
 *
 * 1. Block-level bindings are shown only when the block still exists
 *    AND belongs to the currently active document.
 * 2. When not in cross-file mode, only bindings whose matchedFilePath
 *    equals the current source file path are shown — the rail always
 *    represents the currently open file.
 */
export function displayableBindings(input: BuildBindingFixtureInput): CodeBindingQueryItem[] {
  return sortBindings(
    input.bindings,
    input.commitSha,
    input.blockSortOrders,
  ).filter((binding) => {
    if (!input.allowCrossFile) {
      const bindingPath = normalizeRepositoryPath(binding.matchedFilePath || binding.pathPattern);
      const sourcePath = normalizeRepositoryPath(input.source.path);
      if (bindingPath !== sourcePath) return false;
    }
    if (!binding.blockId) return true;
    if (!binding.blockExists) return false;
    if (input.activeDocumentId && binding.documentId !== input.activeDocumentId) return false;
    return true;
  });
}

/**
 * Purely deterministic: derived from the binding's own fields.
 * No dependency on async state like loaded blocks.
 */
export function bindingDisplayState(binding: CodeBindingQueryItem): BindingDisplayState {
  if (binding.blockId
    && validRange(binding.startLine, binding.endLine)
    && (binding.anchorKind === 'RANGE' || binding.anchorKind === 'SYMBOL')) {
    return 'precise';
  }
  return 'weak';
}

function validRange(startLine: number | null, endLine: number | null) {
  return startLine !== null
    && endLine !== null
    && Number.isInteger(startLine)
    && Number.isInteger(endLine)
    && startLine >= 1
    && endLine >= startLine;
}

export function sortBindings(
  bindings: CodeBindingQueryItem[],
  _revision: string,
  _blockSortOrders?: ReadonlyMap<string, number>,
): CodeBindingQueryItem[] {
  return [...bindings].sort((left, right) => {
    const roleDifference = roleOrder(left) - roleOrder(right);
    if (roleDifference !== 0) return roleDifference;
    const ordinalDifference = bindingOrdinal(left) - bindingOrdinal(right);
    if (ordinalDifference !== 0) return ordinalDifference;
    const precisionDifference = anchorPrecision(left) - anchorPrecision(right);
    if (precisionDifference !== 0) return precisionDifference;
    const startDifference = lineOrder(left.startLine) - lineOrder(right.startLine);
    if (startDifference !== 0) return startDifference;
    return left.bindingId.localeCompare(right.bindingId);
  });
}

export function selectDefaultBinding(
  bindings: CodeBindingQueryItem[],
  revision: string,
  preferredBindingId?: string | null,
  preferredBlockId?: string | null,
  currentFilePath?: string | null,
  blockSortOrders?: ReadonlyMap<string, number>,
): CodeBindingQueryItem | null {
  const sorted = sortBindings(bindings, revision, blockSortOrders);
  return sorted.find(item => item.bindingId === preferredBindingId)
    ?? (preferredBlockId ? sorted.find(item => item.blockId === preferredBlockId) : undefined)
    ?? sorted.find(item => (item.matchedFilePath || item.pathPattern) === currentFilePath && item.revision === revision)
    ?? sorted[0]
    ?? null;
}

export function documentBindingToQueryItem(
  binding: CodeDocumentBinding,
  documentTitle: string | null,
): CodeBindingQueryItem {
  return {
    bindingId: binding.id,
    workspaceId: binding.workspaceId,
    repositoryId: binding.repositoryId,
    revision: binding.revision,
    anchorKind: binding.anchorKind,
    symbolKey: binding.symbolKey,
    startLine: binding.startLine,
    endLine: binding.endLine,
    bindingRole: binding.bindingRole ?? 'PRIMARY',
    bindingOrdinal: binding.bindingOrdinal ?? 1,
    documentId: binding.documentId,
    blockId: binding.blockId,
    targetKey: binding.targetKey,
    pathPattern: binding.pathPattern,
    documentTitle,
    matchedFilePath: binding.pathPattern,
    blockExists: false,
  };
}

export function contextBindingToQueryItem(
  item: CodeBindingContextItem,
  currentFilePath?: string | null,
): CodeBindingQueryItem {
  const concretePath = currentFilePath && item.matchingFilePaths.includes(currentFilePath)
    ? currentFilePath
    : item.matchingFilePaths[0] || item.pathPattern;
  return {
    bindingId: item.bindingId,
    workspaceId: item.workspaceId,
    repositoryId: item.repositoryId,
    revision: item.revision,
    anchorKind: item.anchorKind,
    symbolKey: item.symbolKey,
    startLine: item.startLine,
    endLine: item.endLine,
    bindingRole: item.bindingRole ?? 'PRIMARY',
    bindingOrdinal: item.bindingOrdinal ?? 1,
    documentId: item.documentId,
    blockId: item.blockId,
    targetKey: item.targetKey,
    pathPattern: item.pathPattern,
    documentTitle: item.documentTitle,
    matchedFilePath: concretePath,
    blockExists: item.blockExists,
  };
}

/**
 * Expand context items so that each concrete matching file path
 * produces its own CodeBindingQueryItem.  A single wildcard binding
 * that matches A.java, B.java, C.java becomes three query items,
 * each with a unique synthetic bindingId and its own matchedFilePath.
 *
 * When a context item has only one matching path the behaviour is
 * identical to {@link contextBindingToQueryItem}.
 */
export function expandContextBindingsToQueryItems(
  items: CodeBindingContextItem[],
  currentFilePath?: string | null,
): CodeBindingQueryItem[] {
  return items.flatMap(item => {
    if (item.matchingFilePaths.length === 0) {
      return [contextBindingToQueryItem(item, currentFilePath)];
    }
    return item.matchingFilePaths.map(filePath => {
      const base = contextBindingToQueryItem(item, currentFilePath);
      return {
        ...base,
        bindingId: `${item.bindingId}@${normalizeRepositoryPath(filePath)}`,
        matchedFilePath: filePath,
      };
    });
  });
}

function roleOrder(binding: CodeBindingQueryItem) {
  return binding.bindingRole === 'SUPPORTING' ? 1 : 0;
}

function bindingOrdinal(binding: CodeBindingQueryItem) {
  return binding.bindingOrdinal && binding.bindingOrdinal > 0
    ? binding.bindingOrdinal
    : 1;
}

function anchorPrecision(binding: CodeBindingQueryItem) {
  if (binding.anchorKind === 'SYMBOL' && validRange(binding.startLine, binding.endLine)) return 0;
  if (binding.anchorKind === 'RANGE' && validRange(binding.startLine, binding.endLine)) return 1;
  if (binding.anchorKind === 'SYMBOL') return 2;
  return 3;
}

function lineOrder(line: number | null) {
  return line ?? Number.MAX_SAFE_INTEGER;
}

/**
 * Compute per-file binding counts from document-scope context items,
 * expanding each item's matchingFilePaths so that wildcard patterns
 * contribute counts for every concrete file they resolve to.
 */
export function computeDocumentScopeFileLinkCounts(
  items: CodeBindingContextItem[],
  repositoryId: string,
): Record<string, number> {
  const counts: Record<string, number> = {};
  for (const item of items) {
    if (item.repositoryId !== repositoryId) continue;
    for (const path of item.matchingFilePaths) {
      const normalized = normalizeRepositoryPath(path);
      counts[normalized] = (counts[normalized] || 0) + 1;
    }
  }
  return counts;
}

export function bindingDocumentChoices(
  bindings: CodeBindingQueryItem[],
  documentChoices: LinkedDocumentChoice[],
): LinkedDocumentChoice[] {
  const choiceById = new Map(documentChoices.map(item => [item.id, item]));
  const seen = new Set<string>();
  return bindings.flatMap((binding) => {
    if (seen.has(binding.documentId)) return [];
    seen.add(binding.documentId);
    const existing = choiceById.get(binding.documentId);
    return [{
      id: binding.documentId,
      title: existing?.title || binding.documentTitle || binding.documentId,
      depth: existing?.depth ?? 0,
      version: existing?.version,
      reviewStatus: existing?.reviewStatus,
    }];
  });
}
