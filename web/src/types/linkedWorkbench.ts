import type { DocumentBlock } from '@/api/block';
import type { GitRepositoryFile } from '@/api/git';

export type WorkbenchMode =
  | 'LINKED'
  | 'CODE_FOCUS'
  | 'DOCUMENT_FOCUS'
  | 'DRIFT_REVIEW';

export type LinkActivationSource =
  | 'code'
  | 'rail'
  | 'document'
  | 'inspector'
  | 'system';

export type BindingDisplayState = 'precise' | 'weak';

export interface CodeAnchor {
  id: string;
  bindingId?: string;
  repositoryId: string;
  revision?: string | null;
  branch: string;
  commitSha: string;
  filePath: string;
  language: string;
  symbolName?: string;
  qualifiedSymbol?: string;
  anchorKind?: 'FILE' | 'RANGE' | 'SYMBOL';
  startLine: number | null;
  endLine: number | null;
  contentHash?: string;
  status: 'VALID' | 'DRIFTED' | 'BROKEN';
  bindingDisplayState?: Exclude<BindingDisplayState, 'loading' | 'invalid'>;
}

export interface CodeDocumentLink {
  id: string;
  bindingId?: string;
  codeAnchorId: string;
  repositoryId?: string;
  revision?: string | null;
  filePath?: string;
  documentId: string;
  blockId: string | null;
  documentTitle?: string | null;
  anchorKind?: 'FILE' | 'RANGE' | 'SYMBOL';
  symbolKey?: string | null;
  startLine?: number | null;
  endLine?: number | null;
  bindingRole?: 'PRIMARY' | 'SUPPORTING';
  bindingOrdinal?: number;
  relationType:
    | 'IMPLEMENTS'
    | 'DESCRIBES'
    | 'TESTS'
    | 'EVIDENCE'
    | 'CONFLICTS_WITH';
  bindingDisplayState?: Exclude<BindingDisplayState, 'loading' | 'invalid'>;
}

export interface EngineeringIssue {
  id: string;
  linkId: string;
  title: string;
  description: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH';
  status: 'OPEN' | 'ACCEPTED' | 'FALSE_POSITIVE' | 'RESOLVED';
  blockId?: string;
  codeAnchorId?: string;
  commitSha?: string;
  sourceType: 'RULE' | 'HUMAN' | 'AI';
  sourceKey?: string;
}

export interface LinkedEvidence {
  id: string;
  linkId: string;
  title: string;
  summary: string;
  kind: 'COMMIT' | 'TEST' | 'REVIEW' | 'RULE';
  commitSha?: string;
}

export type LinkedDocumentBlock = DocumentBlock;

export interface LinkedFixture {
  codeAnchors: CodeAnchor[];
  links: CodeDocumentLink[];
  issues: EngineeringIssue[];
  evidence: LinkedEvidence[];
}

export interface LinkedFileTreeNode {
  key: string;
  label: string;
  kind: 'directory' | 'file' | 'unknown';
  children?: LinkedFileTreeNode[];
  file?: GitRepositoryFile;
}

export interface LinkedDocumentChoice {
  id: string;
  title: string;
  depth: number;
  version?: number;
  reviewStatus?: string;
}
