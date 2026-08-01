import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it } from 'vitest';

import type {
  DocumentChangeBindingProposal,
  DocumentChangeDetail,
} from '@/api/documentChange';
import ReviewInspector from './ReviewInspector.vue';

function proposal(
  overrides: Partial<DocumentChangeBindingProposal> = {},
): DocumentChangeBindingProposal {
  return {
    bindingProposalId: 'proposal-1',
    clientBindingProposalId: 'client-proposal-1',
    sequenceNumber: 1,
    action: 'UPSERT_BINDING',
    repository: { id: 'repository-1', name: 'Repository' },
    filePath: 'agent-service/app/context/budget.py',
    revision: 'abcdef1234567890',
    anchorKind: 'FILE',
    symbolKey: null,
    startLine: null,
    endLine: null,
    documentTarget: {
      documentId: 'document-1',
      documentTitle: '上下文构建与预算模块',
      blockId: null,
      blockType: null,
    },
    createdDocumentClientOperationId: null,
    createdBlockClientOperationId: null,
    blockPreview: null,
    bindingId: null,
    candidateId: 'code_candidate_123456',
    documentAnchorCandidateId: 'doc_candidate_123456',
    reason: '代码实现了对应文档职责。',
    confidence: 0.92,
    bindingRole: 'PRIMARY',
    bindingOrdinal: 1,
    ...overrides,
  };
}

function detail(
  bindingProposals: DocumentChangeBindingProposal[],
): DocumentChangeDetail {
  return {
    request: {
      id: 'request-1',
      workspaceId: 'workspace-1',
      status: 'PENDING',
      summary: '块级 Binding 评审',
      rationale: '验证真实锚点。',
      sourceType: 'MCP',
      submittedBy: { id: 'user-1', displayName: 'Agent' },
      createdAt: '2026-07-29T00:00:00Z',
      reviewedBy: null,
      reviewedAt: null,
      rejectionReason: null,
    },
    operations: [],
    bindingProposals,
    requestEvidence: [],
    replayed: false,
    applyResult: null,
  };
}

function mountInspector(bindingProposals: DocumentChangeBindingProposal[]) {
  return mount(ReviewInspector, {
    props: {
      detail: detail(bindingProposals),
      activeOperationId: '',
      activeEvidenceId: null,
      open: true,
    },
    global: { plugins: [ElementPlus] },
  });
}

describe('ReviewInspector precise binding proposals', () => {
  it('renders legacy FILE and document-level target', () => {
    const wrapper = mountInspector([
      proposal({
        revision: null,
        candidateId: null,
        documentAnchorCandidateId: null,
        confidence: null,
      }),
    ]);
    expect(wrapper.text()).toContain('FILE · legacy');
    expect(wrapper.text()).toContain('整篇文档');
  });

  it('renders RANGE and existing Block without editable fields', () => {
    const wrapper = mountInspector([
      proposal({
        anchorKind: 'RANGE',
        startLine: 10,
        endLine: 30,
        documentTarget: {
          documentId: 'document-1',
          documentTitle: '上下文构建与预算模块',
          blockId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
          blockType: 'PARAGRAPH',
        },
        blockPreview: '预算模块负责限制上下文大小。',
      }),
    ]);
    expect(wrapper.text()).toContain('RANGE · L10-30');
    expect(wrapper.text()).toContain('Block aaaaaaaa');
    expect(wrapper.find('input').exists()).toBe(false);
  });

  it('renders SYMBOL, created Block summary, reason and confidence', () => {
    const wrapper = mountInspector([
      proposal({
        anchorKind: 'SYMBOL',
        symbolKey: 'PYTHON:builder.py:build:FUNCTIONDEF',
        startLine: 4,
        endLine: 18,
        createdBlockClientOperationId: 'add-builder',
        blockPreview: '构建器负责组装上下文。',
      }),
    ]);
    expect(wrapper.text()).toContain(
      'SYMBOL · PYTHON:builder.py:build:FUNCTIONDEF',
    );
    expect(wrapper.text()).toContain('新建 Block add-builder');
    expect(wrapper.text()).toContain('代码实现了对应文档职责。');
    expect(wrapper.text()).toContain('置信度 92%');
    expect(wrapper.text()).toContain('主要代码');
  });

  it('renders supporting role and its stable ordinal', () => {
    const wrapper = mountInspector([
      proposal({ bindingRole: 'SUPPORTING', bindingOrdinal: 2 }),
    ]);
    expect(wrapper.text()).toContain('辅助代码 2');
  });

  it('handles a review without binding proposals', () => {
    const wrapper = mountInspector([]);
    expect(wrapper.text()).not.toContain('Binding Proposals');
    expect(wrapper.find('.candidate-ids').exists()).toBe(false);
  });
});
