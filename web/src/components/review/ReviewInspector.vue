<template>
  <aside
    class="review-inspector"
    :class="{ 'is-collapsed': !open }"
    aria-label="Review Inspector"
  >
    <button
      v-if="!open"
      class="inspector-expand"
      type="button"
      aria-label="展开 Review Inspector"
      @click="emit('toggle')"
    >‹</button>

    <template v-else>
      <header>
        <div>
          <span>Review Inspector</span>
          <strong>评审上下文</strong>
        </div>
        <button type="button" aria-label="收起 Review Inspector" @click="emit('toggle')">›</button>
      </header>

      <div class="inspector-scroll">
        <section class="inspector-card request-card">
          <div class="card-title">
            <span>请求概要</span>
            <el-tag :type="statusType" size="small">{{ statusLabel }}</el-tag>
          </div>
          <h3>{{ detail.request.summary }}</h3>
          <p>{{ detail.request.rationale }}</p>
          <dl>
            <div><dt>提交人</dt><dd>{{ detail.request.submittedBy.displayName }}</dd></div>
            <div><dt>Operations</dt><dd>{{ detail.operations.length }}</dd></div>
            <div><dt>Evidence</dt><dd>{{ totalEvidence }}</dd></div>
          </dl>
        </section>

        <section class="inspector-card">
          <div class="card-title">
            <span>Operations</span>
            <small>{{ activeOperationIndex + 1 }} / {{ detail.operations.length }}</small>
          </div>
          <button
            v-for="operation in detail.operations"
            :key="operation.operationId"
            type="button"
            class="operation-item"
            :class="{ 'is-active': operation.operationId === activeOperationId }"
            @click="emit('select-operation', operation.operationId)"
          >
            <b>{{ operation.sequenceNumber }}</b>
            <span>
              <strong>{{ operationLabels[operation.operationType] }}</strong>
              <small>{{ operationDocumentTitle(operation) }}</small>
            </span>
            <em>{{ operation.evidence.length }} 证据</em>
          </button>
        </section>

        <section v-if="activeOperation" class="inspector-card">
          <div class="card-title"><span>修改建议</span><small>{{ activeOperation.operationType }}</small></div>
          <dl class="operation-detail">
            <div><dt>clientOperationId</dt><dd>{{ activeOperation.clientOperationId }}</dd></div>
            <div><dt>目标</dt><dd>{{ operationDocumentTitle(activeOperation) }}</dd></div>
            <div v-if="activeOperation.target.blockId">
              <dt>Block</dt><dd>{{ activeOperation.target.blockId.slice(0, 8) }}</dd>
            </div>
            <div v-if="activeOperation.baseSnapshot?.blockVersion != null">
              <dt>基础版本</dt><dd>V{{ activeOperation.baseSnapshot.blockVersion }}</dd>
            </div>
          </dl>
          <p class="proposal-copy">{{ activeOperation.proposal.plainText || '该操作不包含正文建议。' }}</p>
        </section>

        <el-alert
          v-if="activeOperation?.conflict.conflicted || detail.request.status === 'STALE'"
          class="stale-alert"
          title="目标 Block 已被修改，本请求基于旧版本，不能覆盖人工修改。"
          type="warning"
          :closable="false"
          show-icon
        />

        <section class="inspector-card evidence-index">
          <div class="card-title"><span>Operation Evidence</span><small>{{ activeOperation?.evidence.length || 0 }}</small></div>
          <button
            v-for="evidence in activeOperation?.evidence || []"
            :key="evidence.id"
            type="button"
            :class="{ 'is-active': evidence.id === activeEvidenceId }"
            @click="emit('select-evidence', evidence.id)"
          >
            <strong>{{ evidence.filePath }}</strong>
            <small>{{ evidenceRangeLabel(evidence) }} · {{ evidence.description }}</small>
          </button>
          <p v-if="!activeOperation?.evidence.length" class="empty-copy">当前 Operation 无 Evidence</p>
        </section>

        <section class="inspector-card evidence-index">
          <div class="card-title"><span>Request Evidence</span><small>{{ detail.requestEvidence.length }}</small></div>
          <button
            v-for="evidence in detail.requestEvidence"
            :key="evidence.id"
            type="button"
            :class="{ 'is-active': evidence.id === activeEvidenceId }"
            @click="emit('select-evidence', evidence.id)"
          >
            <strong>{{ evidence.filePath }}</strong>
            <small>{{ evidenceRangeLabel(evidence) }} · {{ evidence.description }}</small>
          </button>
          <p v-if="detail.requestEvidence.length === 0" class="empty-copy">无请求级 Evidence</p>
        </section>

        <section v-if="detail.bindingProposals.length" class="inspector-card evidence-index">
          <div class="card-title"><span>Binding Proposals</span><small>{{ detail.bindingProposals.length }}</small></div>
          <button
            v-for="proposal in detail.bindingProposals"
            :key="proposal.bindingProposalId"
            type="button"
            :class="{ 'is-active': proposal.bindingProposalId === activeEvidenceId }"
            @click="emit('select-evidence', proposal.bindingProposalId)"
          >
            <strong>
              <el-tag
                size="small"
                :type="proposal.bindingRole === 'SUPPORTING' ? 'info' : 'primary'"
                effect="plain"
              >{{ bindingRoleLabel(proposal) }}</el-tag>
              {{ proposal.filePath }}
            </strong>
            <small class="binding-anchor">{{ bindingAnchorLabel(proposal) }}</small>
            <small class="binding-target">{{ bindingTargetLabel(proposal) }}</small>
            <small>{{ proposal.reason }}</small>
            <small v-if="proposal.confidence != null">
              置信度 {{ Math.round(proposal.confidence * 100) }}%
            </small>
            <small
              v-if="proposal.candidateId || proposal.documentAnchorCandidateId"
              class="candidate-ids"
            >
              {{ shortCandidate(proposal.candidateId) }}
              → {{ shortCandidate(proposal.documentAnchorCandidateId) }}
            </small>
          </button>
        </section>

        <section v-if="detail.request.status !== 'PENDING'" class="inspector-card terminal-card">
          <div class="card-title"><span>决策结果</span></div>
          <p>
            {{ detail.request.reviewedBy?.displayName || '-' }} ·
            {{ formatDate(detail.request.reviewedAt) }}
          </p>
          <p v-if="detail.request.rejectionReason">{{ detail.request.rejectionReason }}</p>
        </section>
      </div>

      <footer v-if="detail.request.status === 'PENDING'">
        <el-button
          :disabled="decisionLoading"
          @click="emit('reject')"
        >拒绝</el-button>
        <el-button
          type="primary"
          :loading="decisionLoading"
          :disabled="decisionLoading || hasConflict"
          @click="emit('apply')"
        >批准并应用</el-button>
      </footer>
    </template>
  </aside>
</template>

<script setup lang="ts">
import { computed } from 'vue';

import type {
  DocumentChangeDetail,
  DocumentChangeBindingProposal,
  DocumentChangeOperation,
} from '@/api/documentChange';
import {
  evidenceRangeLabel,
  operationDocumentTitle,
  operationLabels,
  reviewStatusLabels,
} from './reviewPresentation';

function bindingAnchorLabel(proposal: DocumentChangeBindingProposal) {
  const revision = proposal.revision ? proposal.revision.slice(0, 8) : 'legacy';
  if (proposal.anchorKind === 'SYMBOL') {
    const range = proposal.startLine == null
      ? ''
      : ` · L${proposal.startLine}-${proposal.endLine}`;
    return `SYMBOL · ${proposal.symbolKey || '-'}${range} · ${revision}`;
  }
  if (proposal.anchorKind === 'RANGE') {
    return `RANGE · L${proposal.startLine}-${proposal.endLine} · ${revision}`;
  }
  return `FILE · ${revision}`;
}

function bindingRoleLabel(proposal: DocumentChangeBindingProposal) {
  const ordinal = proposal.bindingOrdinal ?? 1;
  return proposal.bindingRole === 'SUPPORTING'
    ? `辅助代码 ${ordinal}`
    : '主要代码';
}

function bindingTargetLabel(proposal: DocumentChangeBindingProposal) {
  const title = proposal.documentTarget.documentTitle
    || proposal.createdDocumentClientOperationId
    || '目标文档';
  if (proposal.documentTarget.blockId) {
    const preview = proposal.blockPreview?.trim();
    return `${title} · Block ${proposal.documentTarget.blockId.slice(0, 8)}${preview ? ` · ${preview.slice(0, 60)}` : ''}`;
  }
  if (proposal.createdBlockClientOperationId) {
    const preview = proposal.blockPreview?.trim();
    return `${title} · 新建 Block ${proposal.createdBlockClientOperationId}${preview ? ` · ${preview.slice(0, 60)}` : ''}`;
  }
  return `${title} · 整篇文档`;
}

function shortCandidate(value: string | null | undefined) {
  if (!value) return '-';
  return value.length > 16 ? `${value.slice(0, 8)}…${value.slice(-5)}` : value;
}

const props = defineProps<{
  detail: DocumentChangeDetail;
  activeOperationId: string;
  activeEvidenceId: string | null;
  open: boolean;
  decisionLoading?: boolean;
}>();

const emit = defineEmits<{
  toggle: [];
  'select-operation': [operationId: string];
  'select-evidence': [evidenceId: string];
  apply: [];
  reject: [];
}>();

const activeOperation = computed<DocumentChangeOperation | null>(() =>
  props.detail.operations.find(item => item.operationId === props.activeOperationId) ?? null);
const activeOperationIndex = computed(() => Math.max(
  0,
  props.detail.operations.findIndex(item => item.operationId === props.activeOperationId),
));
const totalEvidence = computed(() => props.detail.requestEvidence.length
  + props.detail.operations.reduce((total, item) => total + item.evidence.length, 0));
const hasConflict = computed(() => props.detail.operations.some(item => item.conflict.conflicted));
const statusLabel = computed(() => reviewStatusLabels[props.detail.request.status]);
const statusType = computed(() => {
  if (props.detail.request.status === 'APPLIED') return 'success';
  if (props.detail.request.status === 'REJECTED') return 'danger';
  if (props.detail.request.status === 'STALE') return 'warning';
  return 'primary';
});

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '-';
}
</script>

<style scoped>
.review-inspector {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr) auto;
  border-left: 1px solid #e1e6ee;
  background: #f8fafc;
}

.review-inspector.is-collapsed {
  display: grid;
  width: 42px;
  place-items: start center;
  padding-top: 10px;
}

.inspector-expand,
header button {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid #d8e0ea;
  border-radius: 6px;
  background: #fff;
  color: #475467;
  cursor: pointer;
}

header {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  padding: 9px 12px;
  border-bottom: 1px solid #e1e6ee;
  background: #fff;
}

header div {
  display: grid;
  gap: 3px;
}

header span {
  color: #667085;
  font-size: 10px;
}

.inspector-scroll {
  min-height: 0;
  overflow: auto;
  padding: 10px;
}

.inspector-card {
  margin-bottom: 10px;
  border: 1px solid #e1e6ee;
  border-radius: 8px;
  padding: 10px;
  background: #fff;
}

.card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 9px;
  color: #475467;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .05em;
  text-transform: uppercase;
}

.card-title small {
  color: #98a2b3;
}

.request-card h3 {
  margin: 8px 0;
  font-size: 14px;
}

.request-card p,
.proposal-copy,
.terminal-card p {
  margin: 0;
  color: #667085;
  font-size: 11px;
  line-height: 1.55;
}

.request-card dl {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin: 10px 0 0;
}

.request-card dl div {
  display: grid;
  justify-items: center;
  border-right: 1px solid #e8edf3;
}

.request-card dl div:last-child {
  border-right: 0;
}

dt {
  color: #98a2b3;
  font-size: 9px;
}

dd {
  margin: 2px 0 0;
  color: #344054;
  font-size: 11px;
}

.operation-item,
.evidence-index button {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
  border: 1px solid transparent;
  border-radius: 7px;
  padding: 8px;
  background: #f8fafc;
  color: #475467;
  text-align: left;
  cursor: pointer;
}

.operation-item.is-active,
.evidence-index button.is-active {
  border-color: #b8ccff;
  background: #edf3ff;
  color: #175cd3;
}

.operation-item b {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 50%;
  background: #3168e8;
  color: #fff;
  font-size: 10px;
}

.operation-item > span,
.evidence-index button {
  min-width: 0;
}

.operation-item > span {
  display: grid;
  flex: 1;
}

.operation-item strong,
.evidence-index strong {
  overflow: hidden;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.operation-item small,
.evidence-index small {
  color: #667085;
  font-size: 9px;
}

.operation-item em {
  flex: 0 0 auto;
  color: #98a2b3;
  font-size: 9px;
  font-style: normal;
}

.operation-detail {
  display: grid;
  gap: 7px;
  margin: 0 0 10px;
}

.operation-detail div {
  display: grid;
  gap: 2px;
}

.operation-detail dd {
  overflow-wrap: anywhere;
}

.evidence-index button {
  display: grid;
}

.empty-copy {
  margin: 4px 0 0;
  color: #98a2b3;
  font-size: 10px;
}

.stale-alert {
  margin-bottom: 10px;
}

footer {
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: 8px;
  border-top: 1px solid #e1e6ee;
  padding: 10px;
  background: #fff;
}
</style>
