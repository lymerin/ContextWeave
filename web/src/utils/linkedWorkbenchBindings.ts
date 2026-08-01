import type {
  CodeBindingQueryItem,
  CodeDocumentBinding,
  GitRepositorySource,
} from '@/api/git';
import type { LinkedDocumentChoice, LinkedFixture } from '@/types/linkedWorkbench';
import type { BindingDisplayState } from '@/types/linkedWorkbench';
import { normalizeRepositoryPath } from '@/utils/repositoryTree';

export interface BuildBindingFixtureInput {
  repositoryId: string;
  branch: string;
  commitSha: string;
  source: GitRepositorySource;
  bindings: CodeBindingQueryItem[];
  allowCrossFile?: boolean;
  loadedDocumentId?: string | null;
  loadedBlockIds?: ReadonlySet<string>;
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
    filePath: binding.pathPattern,
    language: input.source.language || 'text',
    symbolName: binding.symbolKey || (
      binding.blockId ? 'Block 关联文件' : '文档关联文件'
    ),
    qualifiedSymbol: binding.symbolKey || undefined,
    anchorKind: binding.revision === null ? 'FILE' as const : binding.anchorKind,
    startLine: binding.revision === null ? null : binding.startLine,
    endLine: binding.revision === null ? null : binding.endLine,
    status: 'VALID' as const,
    bindingDisplayState: classifyBinding(binding, input) as 'precise' | 'weak',
  }));
  const links = selectedBindings.map((binding, index) => ({
    id: `binding-link-${binding.bindingId}`,
    bindingId: binding.bindingId,
    codeAnchorId: codeAnchors[index].id,
    repositoryId: binding.repositoryId,
    revision: binding.revision,
    filePath: binding.pathPattern,
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
    bindingDisplayState: classifyBinding(binding, input) as 'precise' | 'weak',
  }));
  return { codeAnchors, links, issues: [], evidence: [] };
}

export function displayableBindings(input: BuildBindingFixtureInput): CodeBindingQueryItem[] {
  return sortBindings(
    input.bindings,
    input.commitSha,
    input.blockSortOrders,
  ).filter((binding) => {
    const state = classifyBinding(binding, input);
    return state === 'precise' || state === 'weak';
  });
}

export function classifyBinding(
  binding: CodeBindingQueryItem,
  input: BuildBindingFixtureInput,
): BindingDisplayState {
  if (!binding.bindingId?.trim()
    || !binding.repositoryId?.trim()
    || !binding.documentId?.trim()
    || !binding.pathPattern?.trim()) {
    return 'invalid';
  }
  if (binding.repositoryId !== input.repositoryId) return 'invalid';
  if (!input.allowCrossFile
    && normalizeRepositoryPath(binding.pathPattern)
      !== normalizeRepositoryPath(input.source.path)) {
    return 'invalid';
  }
  if (binding.revision !== null && binding.revision !== input.commitSha) {
    return 'invalid';
  }

  const hasRange = validRange(binding.startLine, binding.endLine);
  if (binding.anchorKind === 'RANGE' && !hasRange) return 'invalid';
  if (binding.anchorKind === 'SYMBOL' && !binding.symbolKey?.trim()) return 'invalid';

  if (binding.blockId) {
    if (input.loadedDocumentId !== binding.documentId || input.loadedBlockIds === undefined) {
      return 'loading';
    }
    if (!input.loadedBlockIds.has(binding.blockId)) return 'invalid';
  }

  return binding.blockId
    && hasRange
    && (binding.anchorKind === 'RANGE' || binding.anchorKind === 'SYMBOL')
    ? 'precise'
    : 'weak';
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
    ?? sorted.find(item => item.pathPattern === currentFilePath && item.revision === revision)
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
  };
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
