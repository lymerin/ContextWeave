<template>
  <aside
    class="review-inspector"
    :class="{ 'is-collapsed': !open }"
    aria-label="审查决策"
  >
    <button
      v-if="!open"
      class="inspector-expand"
      type="button"
      aria-label="展开审查决策"
      @click="emit('toggle')"
    >‹</button>

    <template v-else>
      <header class="inspector-header">
        <div><span>REVIEW DECISION</span><strong>审查决策</strong></div>
        <button type="button" aria-label="收起审查决策" @click="emit('toggle')">›</button>
      </header>

      <div class="inspector-scroll">
        <section class="request-summary">
          <div class="section-title"><span>REQUEST SUMMARY</span><el-tag :type="statusType" size="small">{{ statusLabel }}</el-tag></div>
          <h3>{{ detail.request.summary }}</h3>
          <p>{{ detail.request.rationale || '该请求没有补充说明' }}</p>
          <dl>
            <div><dt>提交人</dt><dd>{{ detail.request.submittedBy.displayName }}</dd></div>
            <div><dt>变更</dt><dd>{{ detail.operations.length }}</dd></div>
            <div><dt>绑定</dt><dd>{{ bindingCount }}</dd></div>
          </dl>
        </section>

        <section class="review-checks">
          <div class="section-title"><span>REVIEW CHECKS</span><small>{{ activeOperationIndex + 1 }} / {{ detail.operations.length }}</small></div>
          <div class="check-row is-ready"><i>✓</i><span><strong>变更结构完整</strong><small>{{ activeOperation ? operationLabels[activeOperation.operationType] : '无可执行变更' }}</small></span></div>
          <div class="check-row" :class="{ 'is-ready': hasCodeContext }"><i>{{ hasCodeContext ? '✓' : '○' }}</i><span><strong>代码上下文</strong><small>{{ codeContextLabel }}</small></span></div>
          <div class="check-row" :class="{ 'is-warning': hasConflict, 'is-ready': !hasConflict }"><i>{{ hasConflict ? '!' : '✓' }}</i><span><strong>版本冲突</strong><small>{{ hasConflict ? '目标内容已变化，不能覆盖人工修改' : '目标版本可安全应用' }}</small></span></div>
        </section>

        <el-alert
          v-if="hasConflict || detail.request.status === 'STALE'"
          class="stale-alert"
          title="目标 Block 已被修改，本请求基于旧版本，不能覆盖人工修改。"
          type="warning"
          :closable="false"
          show-icon
        />

        <details v-if="detail.bindingProposals.length" class="binding-details">
          <summary>绑定变更 <span>{{ detail.bindingProposals.length }}</span></summary>
          <div
            v-for="proposal in detail.bindingProposals"
            :key="proposal.bindingProposalId"
            class="binding-proposal"
          >
            <strong><span>{{ bindingRoleLabel(proposal) }}</span>{{ proposal.filePath }}</strong>
            <small>{{ bindingAnchorLabel(proposal) }}</small>
            <small>{{ bindingTargetLabel(proposal) }}</small>
            <p>{{ proposal.reason }}</p>
            <small v-if="proposal.confidence != null">置信度 {{ Math.round(proposal.confidence * 100) }}%</small>
            <small v-if="proposal.candidateId || proposal.documentAnchorCandidateId" class="candidate-ids">
              {{ shortCandidate(proposal.candidateId) }} → {{ shortCandidate(proposal.documentAnchorCandidateId) }}
            </small>
          </div>
        </details>

        <section v-if="detail.request.status !== 'PENDING'" class="terminal-card">
          <div class="section-title"><span>DECISION RESULT</span></div>
          <p>{{ detail.request.reviewedBy?.displayName || '-' }} · {{ formatDate(detail.request.reviewedAt) }}</p>
          <p v-if="detail.request.rejectionReason">{{ detail.request.rejectionReason }}</p>
        </section>
      </div>

      <footer v-if="detail.request.status === 'PENDING'">
        <button class="decision-button" type="button" :disabled="decisionLoading" @click="emit('reject')">退回修改</button>
        <button class="decision-button is-primary" type="button" :disabled="decisionLoading || hasConflict" @click="emit('apply')">
          {{ decisionLoading ? '处理中' : '通过审查' }} <span aria-hidden="true">↗</span>
        </button>
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
import { operationLabels, reviewStatusLabels } from './reviewPresentation';

function bindingAnchorLabel(proposal: DocumentChangeBindingProposal) {
  const revision = proposal.revision ? proposal.revision.slice(0, 8) : 'legacy';
  if (proposal.anchorKind === 'SYMBOL') {
    const range = proposal.startLine == null ? '' : ` · L${proposal.startLine}-${proposal.endLine}`;
    return `SYMBOL · ${proposal.symbolKey || '-'}${range} · ${revision}`;
  }
  if (proposal.anchorKind === 'RANGE') return `RANGE · L${proposal.startLine}-${proposal.endLine} · ${revision}`;
  return `FILE · ${revision}`;
}
function bindingRoleLabel(proposal: DocumentChangeBindingProposal) {
  return proposal.bindingRole === 'SUPPORTING' ? `辅助代码 ${proposal.bindingOrdinal ?? 1}` : '主要代码';
}
function bindingTargetLabel(proposal: DocumentChangeBindingProposal) {
  const title = proposal.documentTarget.documentTitle || proposal.createdDocumentClientOperationId || '目标文档';
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
  hasCodeContext?: boolean;
}>();
const emit = defineEmits<{
  toggle: [];
  'select-operation': [operationId: string];
  'select-evidence': [evidenceId: string];
  apply: [];
  reject: [];
}>();
const activeOperation = computed<DocumentChangeOperation | null>(() => props.detail.operations.find(item => item.operationId === props.activeOperationId) ?? null);
const activeOperationIndex = computed(() => Math.max(0, props.detail.operations.findIndex(item => item.operationId === props.activeOperationId)));
const totalEvidence = computed(() => props.detail.requestEvidence.length + props.detail.operations.reduce((total, item) => total + item.evidence.length, 0));
const bindingCount = computed(() => props.detail.bindingProposals.length || totalEvidence.value);
const codeContextLabel = computed(() => {
  if (totalEvidence.value > 0) return `已提供 ${totalEvidence.value} 条 Evidence`;
  if (props.hasCodeContext) return '已自动定位可审查代码';
  return '暂无 Binding 或 Evidence';
});
const hasConflict = computed(() => props.detail.operations.some(item => item.conflict.conflicted));
const statusLabel = computed(() => reviewStatusLabels[props.detail.request.status]);
const statusType = computed(() => {
  if (props.detail.request.status === 'APPLIED') return 'success';
  if (props.detail.request.status === 'REJECTED') return 'danger';
  if (props.detail.request.status === 'STALE') return 'warning';
  return 'primary';
});
function formatDate(value: string | null) { return value ? new Date(value).toLocaleString('zh-CN') : '-'; }
</script>

<style scoped>
.review-inspector { display: grid; min-width: 0; min-height: 0; grid-template-rows: 64px minmax(0,1fr) auto; background: #fafcfc; }
.review-inspector.is-collapsed { display: grid; width: 42px; place-items: start center; padding-top: 11px; border-left: 1px solid #e2e9eb; }
.inspector-expand,.inspector-header button { display: grid; width: 28px; height: 28px; place-items: center; border: 1px solid #dce4e7; border-radius: 6px; background: #fff; color: #4d5a60; cursor: pointer; }
.inspector-header { display: flex; align-items: center; justify-content: space-between; padding: 0 13px; border-bottom: 1px solid #e3eaec; background: #fff; }
.inspector-header div { display: grid; gap: 4px; }
.inspector-header span,.section-title > span { color: #168ba5; font: 600 9px Consolas, monospace; letter-spacing: .12em; }
.inspector-header strong { font-size: 14px; }
.inspector-scroll { min-height: 0; overflow: auto; padding: 13px; }
.request-summary,.review-checks,.terminal-card,.binding-details { margin: 0 0 11px; border: 1px solid #e1e8ea; border-radius: 9px; padding: 12px; background: #fff; }
.section-title { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; }
.section-title small { color: #8a969b; font: 9px Consolas, monospace; }
.request-summary h3 { margin: 7px 0 5px; font-size: 13px; }
.request-summary p,.terminal-card p { margin: 0; color: #6e7b81; font-size: 10px; line-height: 1.55; }
.request-summary dl { display: grid; grid-template-columns: repeat(3,1fr); margin: 11px 0 0; padding-top: 10px; border-top: 1px solid #edf1f2; }
.request-summary dl div { display: grid; justify-items: center; border-right: 1px solid #edf1f2; }.request-summary dl div:last-child{border-right:0}
dt { color:#9aa5a9;font-size:9px } dd{margin:3px 0 0;color:#344148;font-size:10px}
.check-row { display: flex; gap: 9px; padding: 9px 0; border-bottom: 1px solid #edf1f2; }.check-row:last-child{border-bottom:0}
.check-row i { color:#8a969b;font-style:normal }.check-row.is-ready i{color:#169878}.check-row.is-warning i{color:#c57a24}
.check-row span{display:grid;gap:2px}.check-row strong{font-size:10px}.check-row small{color:#7f8b90;font-size:9px;line-height:1.4}
.stale-alert{margin-bottom:11px}
.binding-details summary { display:flex;justify-content:space-between;color:#405057;font-size:10px;font-weight:600;cursor:pointer }.binding-details summary span{color:#168ba5}
.binding-proposal { display:grid;gap:5px;margin-top:10px;padding-top:10px;border-top:1px solid #edf1f2 }
.binding-proposal strong { display:grid;gap:4px;overflow-wrap:anywhere;font-size:10px }.binding-proposal strong span{width:max-content;padding:2px 5px;border-radius:3px;background:#eafcff;color:#137c95;font-size:9px}
.binding-proposal small,.binding-proposal p{margin:0;color:#758288;font-size:9px;line-height:1.45;overflow-wrap:anywhere}.candidate-ids{font-family:Consolas,monospace}
.review-inspector footer { display:grid;grid-template-columns:1fr 1.2fr;gap:8px;padding:11px;border-top:1px solid #e1e8ea;background:#fff }
.decision-button { position:relative;min-height:40px;border:1px solid #dce4e7;border-radius:8px;background:#fff;color:#354148;cursor:pointer;font-weight:600;transition:transform 180ms ease,box-shadow 220ms ease,border-color 180ms ease }
.decision-button.is-primary{border-color:#090c0e;background:#090c0e;color:#fff}.decision-button span{margin-left:7px;color:#18c9ef}
.decision-button:hover:not(:disabled),.decision-button:focus-visible:not(:disabled){border-color:rgb(24 201 239 / 20%);box-shadow:7px 7px 14px -9px rgb(24 201 239 / 75%),13px 12px 24px -17px rgb(24 201 239 / 62%),0 10px 18px -13px rgb(24 201 239 / 58%);outline:none;transform:translateY(-2px)}
.decision-button:disabled{cursor:not-allowed;opacity:.48}
</style>
