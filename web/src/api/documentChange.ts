import axios from 'axios';

import type { DocumentBlockType, TiptapNode } from './block';
import { http } from './http';

export type DocumentChangeStatus = 'PENDING' | 'APPLIED' | 'REJECTED' | 'STALE';
export type DocumentChangeOperationType =
  | 'CREATE_DOCUMENT'
  | 'ADD_BLOCK'
  | 'UPDATE_BLOCK'
  | 'DELETE_BLOCK';

export interface DocumentChangeUser {
  id: string;
  displayName: string;
}

export interface DocumentChangeRequest {
  id: string;
  workspaceId: string;
  status: DocumentChangeStatus;
  summary: string;
  rationale: string;
  sourceType: 'MCP';
  submittedBy: DocumentChangeUser;
  createdAt: string;
  reviewedBy: DocumentChangeUser | null;
  reviewedAt: string | null;
  rejectionReason: string | null;
}

export interface DocumentChangeTarget {
  documentId: string | null;
  documentTitle: string | null;
  blockId: string | null;
  blockType: DocumentBlockType | null;
}

export interface DocumentChangeSnapshot {
  blockVersion: number | null;
  blockType: DocumentBlockType | null;
  plainText: string | null;
  content: {
    schemaVersion?: number;
    document?: TiptapNode;
  } | null;
  sortOrder: number | null;
}

export interface DocumentChangeProposal {
  documentTitle: string | null;
  documentType: string | null;
  parentDocumentId: string | null;
  blockType: DocumentBlockType | null;
  plainText: string | null;
  content: {
    schemaVersion?: number;
    document?: TiptapNode;
  } | null;
}

export interface DocumentChangeConflict {
  conflicted: boolean;
  reason: string | null;
  expectedVersion: number | null;
  actualVersion: number | null;
}

export interface DocumentChangeEvidence {
  id: string;
  repository: {
    id: string;
    name: string;
  };
  filePath: string;
  commitHash: string;
  startLine: number | null;
  endLine: number | null;
  description: string;
  excerptText: string;
}

export type BindingAction = 'UPSERT_BINDING' | 'REMOVE_BINDING';
export type CodeAnchorKind = 'FILE' | 'RANGE' | 'SYMBOL';
export type BindingRole = 'PRIMARY' | 'SUPPORTING';

export interface DocumentChangeBindingProposal {
  bindingProposalId: string;
  clientBindingProposalId: string;
  sequenceNumber: number;
  action: BindingAction;
  repository: {
    id: string;
    name: string;
  };
  filePath: string;
  revision?: string | null;
  anchorKind?: CodeAnchorKind;
  symbolKey?: string | null;
  startLine?: number | null;
  endLine?: number | null;
  createdDocumentClientOperationId?: string | null;
  createdBlockClientOperationId?: string | null;
  blockPreview?: string | null;
  documentTarget: DocumentChangeTarget;
  bindingId: string | null;
  candidateId?: string | null;
  documentAnchorCandidateId?: string | null;
  reason: string;
  confidence?: number | null;
  bindingRole?: BindingRole;
  bindingOrdinal?: number;
}

export interface DocumentChangeApplyResult {
  createdDocuments: Record<string, string>;
  createdBlocks: Record<string, string>;
  bindings: Array<{
    bindingProposalId: string;
    bindingId: string;
    reused: boolean;
  }>;
}

export interface DocumentChangeOperation {
  operationId: string;
  clientOperationId: string;
  sequenceNumber: number;
  operationType: DocumentChangeOperationType;
  target: DocumentChangeTarget;
  baseSnapshot: DocumentChangeSnapshot | null;
  proposal: DocumentChangeProposal;
  currentBlockVersion: number | null;
  conflict: DocumentChangeConflict;
  evidence: DocumentChangeEvidence[];
}

export interface DocumentChangeDetail {
  request: DocumentChangeRequest;
  operations: DocumentChangeOperation[];
  bindingProposals: DocumentChangeBindingProposal[];
  requestEvidence: DocumentChangeEvidence[];
  replayed: boolean;
  applyResult?: DocumentChangeApplyResult | null;
}

export interface DocumentChangeListItem {
  id: string;
  summary: string;
  status: DocumentChangeStatus;
  sourceType: 'MCP';
  submittedByDisplayName: string;
  createdAt: string;
  reviewedAt: string | null;
  operationCount: number;
  bindingProposalCount: number;
  evidenceCount: number;
  affectedDocumentTitles: string[];
}

export interface DocumentChangePage {
  items: DocumentChangeListItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export async function getPendingDocumentChangeCount(workspaceId: string) {
  const { data } = await http.get<{ count: number }>(
    `/workspaces/${workspaceId}/document-change-requests/pending-count`,
  );
  return data.count;
}

export async function listDocumentChanges(
  workspaceId: string,
  params: {
    status: DocumentChangeStatus;
    page?: number;
    size?: number;
    sort?: string;
  },
) {
  const { data } = await http.get<DocumentChangePage>(
    `/workspaces/${workspaceId}/document-change-requests`,
    { params },
  );
  return data;
}

export async function getDocumentChange(
  workspaceId: string,
  requestId: string,
) {
  const { data } = await http.get<DocumentChangeDetail>(
    `/workspaces/${workspaceId}/document-change-requests/${requestId}`,
  );
  return data;
}

export async function applyDocumentChange(
  workspaceId: string,
  requestId: string,
) {
  try {
    const { data } = await http.post<DocumentChangeDetail>(
      `/workspaces/${workspaceId}/document-change-requests/${requestId}/apply`,
    );
    return data;
  } catch (error) {
    if (
      axios.isAxiosError<DocumentChangeDetail>(error)
      && error.response?.status === 409
      && error.response.data?.request
    ) {
      return error.response.data;
    }
    throw error;
  }
}

export async function rejectDocumentChange(
  workspaceId: string,
  requestId: string,
  reason: string,
) {
  const { data } = await http.post<DocumentChangeDetail>(
    `/workspaces/${workspaceId}/document-change-requests/${requestId}/reject`,
    { reason },
  );
  return data;
}
