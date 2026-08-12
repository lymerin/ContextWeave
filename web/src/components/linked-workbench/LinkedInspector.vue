<template>
  <aside class="linked-inspector" aria-label="关联检查器">
    <header>
      <div><p class="eyebrow">Inspector</p><strong>关联上下文</strong></div>
      <button type="button" aria-label="关闭检查器" @click="emit('close')">×</button>
    </header>
    <el-tabs v-model="activeTab" class="inspector-tabs">
      <el-tab-pane label="评论" name="comments">
        <el-empty description="当前关联暂无评论" :image-size="70" />
      </el-tab-pane>
      <el-tab-pane label="问题" name="issues">
        <article v-if="activeIssue" class="inspector-card issue-card">
          <div><el-tag size="small" type="danger">{{ activeIssue.severity }}</el-tag><span>{{ activeIssue.status }}</span></div>
          <strong>{{ activeIssue.title }}</strong>
          <p>{{ activeIssue.description }}</p>
          <small>{{ activeIssue.sourceType }} · {{ activeIssue.sourceKey }}</small>
        </article>
        <el-empty v-else description="当前关联暂无问题" :image-size="70" />
      </el-tab-pane>
      <el-tab-pane label="证据" name="evidence">
        <button
          v-for="item in activeEvidence"
          :key="item.id"
          type="button"
          class="evidence-item"
          @click="emit('activate', item.linkId)"
        >
          <el-tag size="small" effect="plain">{{ item.kind }}</el-tag>
          <strong>{{ item.title }}</strong>
          <span>{{ item.summary }}</span>
          <code v-if="item.commitSha">{{ item.commitSha.slice(0, 10) }}</code>
        </button>
        <el-empty v-if="activeEvidence.length === 0" description="当前关联暂无证据" :image-size="70" />
      </el-tab-pane>
      <el-tab-pane label="关系" name="relations">
        <dl v-if="activeLink" class="relation-list">
          <dt>关系类型</dt><dd>{{ activeLink.relationType }}</dd>
          <dt>代码锚点</dt><dd>{{ activeAnchor?.symbolName || activeLink.codeAnchorId }}</dd>
          <dt>文档 Block</dt><dd>{{ activeBlock?.id.slice(0, 12) }}</dd>
          <dt>锚点状态</dt><dd>{{ activeAnchor?.status }}</dd>
        </dl>
        <el-empty v-else description="请选择一个关联" :image-size="70" />
      </el-tab-pane>
      <el-tab-pane label="版本" name="versions">
        <div v-if="versions.length" class="version-list">
          <article v-for="version in versions" :key="version.id">
            <strong>V{{ version.versionNo }} · {{ version.title }}</strong>
            <span>{{ version.status }} · {{ formatTime(version.publishedAt) }}</span>
          </article>
        </div>
        <el-empty v-else description="暂无发布版本" :image-size="70" />
      </el-tab-pane>
    </el-tabs>
  </aside>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import type { DocumentBlock } from '@/api/block';
import type { DocumentVersion } from '@/api/document';
import type {
  CodeAnchor,
  CodeDocumentLink,
  EngineeringIssue,
  LinkedEvidence,
  WorkbenchMode,
} from '@/types/linkedWorkbench';

const props = defineProps<{
  mode: WorkbenchMode;
  activeLink: CodeDocumentLink | null;
  activeAnchor: CodeAnchor | null;
  activeBlock: DocumentBlock | null;
  activeIssue: EngineeringIssue | null;
  activeEvidence: LinkedEvidence[];
  versions: DocumentVersion[];
}>();
const emit = defineEmits<{ close: []; activate: [linkId: string] }>();
const activeTab = ref('relations');
watch(() => props.activeIssue, issue => { activeTab.value = issue ? 'issues' : 'relations'; });
watch(() => props.mode, mode => { if (mode === 'DRIFT_REVIEW') activeTab.value = 'issues'; });
function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium' }).format(new Date(value));
}
</script>

<style scoped>
.linked-inspector { display: grid; min-width: 0; min-height: 0; grid-template-rows: auto minmax(0, 1fr); border-left: 1px solid #dfe6f0; background: #fff; }
.linked-inspector > header { display: flex; min-height: 58px; align-items: center; justify-content: space-between; padding: 9px 13px; border-bottom: 1px solid #e4e9f1; }
.linked-inspector header div { display: grid; gap: 2px; }
.linked-inspector header button { display: grid; width: 36px; height: 36px; place-items: center; border: 0; border-radius: 8px; background: #f2f4f7; color: #475467; font-size: 20px; cursor: pointer; }
.linked-inspector header button:focus-visible, .evidence-item:focus-visible { outline: 2px solid #18d1ff; outline-offset: 2px; }
.inspector-tabs { display: flex; min-height: 0; flex-direction: column; }
.inspector-tabs :deep(.el-tabs__header) { flex: 0 0 auto; margin: 0; padding-inline: 10px; }
.inspector-tabs :deep(.el-tabs__nav-wrap) { overflow-x: auto; scrollbar-width: thin; }
.inspector-tabs :deep(.el-tabs__nav-scroll) { min-width: max-content; overflow: visible; }
.inspector-tabs :deep(.el-tabs__item) { flex: 0 0 auto; padding-inline: 14px; }
.inspector-tabs :deep(.el-tabs__content) { min-height: 0; flex: 1; overflow: auto; padding: 12px; }
.inspector-card, .version-list article { display: grid; gap: 8px; border: 1px solid #e4e9f1; border-radius: 8px; padding: 12px; }
.issue-card { border-color: #f2b8c0; background: #fffafb; }
.issue-card > div { display: flex; align-items: center; justify-content: space-between; color: #667085; font-size: 11px; }
.issue-card p { margin: 0; color: #475467; font-size: 12px; line-height: 1.6; }
.evidence-item { display: grid; width: 100%; gap: 6px; margin-bottom: 8px; border: 1px solid #e4e9f1; border-radius: 8px; padding: 10px; background: #fff; color: #344054; text-align: left; cursor: pointer; }
.evidence-item:hover { border-color: #84adff; background: #f5f8ff; }
.evidence-item span, .evidence-item code, .version-list span { color: #667085; font-size: 11px; }
.relation-list { display: grid; grid-template-columns: 86px minmax(0, 1fr); gap: 8px; margin: 0; font-size: 12px; }
.relation-list dt { color: #667085; }.relation-list dd { overflow-wrap: anywhere; margin: 0; color: #101828; }
.version-list { display: grid; gap: 8px; }.version-list article { gap: 5px; }
</style>
