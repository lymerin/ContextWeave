<template>
  <aside class="anchor-rail" aria-label="代码与文档关联轨道">
    <template v-if="links.length > 0">
      <span class="rail-line" aria-hidden="true" />
      <button
        v-for="(link, index) in links"
        :key="link.id"
        type="button"
        class="rail-node"
        :class="nodeClass(link)"
        :style="{ top: `${railPosition(index)}%` }"
        :title="nodeTitle(link)"
        :aria-label="nodeTitle(link)"
        :aria-current="link.id === activeLinkId ? 'true' : undefined"
        @click="emit('activate', link.id)"
      >{{ index + 1 }}</button>
    </template>
    <span v-else class="rail-empty" aria-label="当前文件暂无有效正式关联">
      暂无<br>关联
    </span>
  </aside>
</template>

<script setup lang="ts">
import type { CodeAnchor, CodeDocumentLink, EngineeringIssue } from '@/types/linkedWorkbench';

const props = defineProps<{
  links: CodeDocumentLink[];
  anchors: CodeAnchor[];
  issues: EngineeringIssue[];
  activeLinkId: string | null;
}>();
const emit = defineEmits<{ activate: [linkId: string] }>();

function anchorFor(link: CodeDocumentLink) {
  return props.anchors.find(anchor => anchor.id === link.codeAnchorId);
}
function nodeClass(link: CodeDocumentLink) {
  const anchor = anchorFor(link);
  return {
    'is-active': link.id === props.activeLinkId,
    'has-issue': props.issues.some(issue => issue.linkId === link.id),
    'is-drifted': anchor?.status !== 'VALID' || link.relationType === 'CONFLICTS_WITH',
  };
}
function nodeTitle(link: CodeDocumentLink) {
  const anchor = anchorFor(link);
  if (link.bindingDisplayState === 'weak') {
    if (anchor?.anchorKind === 'SYMBOL') return '符号关联（无行范围）';
    if (link.blockId) return '文件 → 段落';
    return '文件 → 文档';
  }
  const codeTarget = anchor?.anchorKind === 'SYMBOL'
    ? anchor.symbolName || '符号关联'
    : anchor?.startLine !== null && anchor?.startLine !== undefined
      ? `第 ${anchor.startLine}–${anchor.endLine} 行`
      : '整个文件';
  const documentTarget = link.blockId ? `Block ${link.blockId.slice(0, 8)}` : '整篇文档';
  return `${codeTarget} ↔ ${documentTarget}，${anchor?.status || '未知状态'}`;
}
function railPosition(index: number) {
  if (props.links.length <= 1) return 50;
  return 18 + (index * 64) / (props.links.length - 1);
}
</script>

<style scoped>
.anchor-rail { position: relative; min-height: 0; border-inline: 1px solid #e0e7f0; background: linear-gradient(90deg, #f8faff, #fff, #f8faff); }
.rail-line { position: absolute; top: 10%; bottom: 10%; left: 50%; width: 2px; transform: translateX(-50%); background: #c9d6ec; }
.rail-empty { position: absolute; top: 50%; left: 50%; width: 42px; transform: translate(-50%, -50%); color: #98a2b3; font-size: 10px; line-height: 1.5; text-align: center; }
.rail-node { position: absolute; z-index: 1; left: 50%; display: grid; width: 32px; height: 32px; place-items: center; transform: translate(-50%, -50%); border: 2px solid #8aa9e8; border-radius: 999px; background: #fff; color: #3562ad; font-size: 11px; font-weight: 700; cursor: pointer; transition: .16s ease; }
.rail-node:hover { transform: translate(-50%, -50%) scale(1.08); }
.rail-node:focus-visible { outline: 2px solid #18d1ff; outline-offset: 3px; }
.rail-node.is-active { border-color: #155eef; background: #155eef; color: #fff; box-shadow: 0 0 0 5px #dfeaff; }
.rail-node.is-drifted { border-color: #e69b24; }
.rail-node.has-issue::after { content: ''; position: absolute; top: -4px; right: -4px; width: 8px; height: 8px; border: 2px solid #fff; border-radius: 50%; background: #d92d20; }
</style>
