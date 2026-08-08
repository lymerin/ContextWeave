<template>
  <section ref="editorRoot" class="block-editor">
    <header v-if="!compactReading" class="block-editor-header">
      <div>
        <p class="eyebrow">Tiptap Blocks</p>
        <h3>内容编辑区</h3>
        <p class="section-hint">
          每个业务 Block 使用独立 Tiptap 编辑内核，并继续通过稳定 Block ID 和 version 自动保存、检测冲突。
        </p>
      </div>

      <el-dropdown
        split-button
        type="primary"
        :loading="creating"
        :disabled="readonly"
        @click="handleCreate('PARAGRAPH')"
        @command="handleCreate"
      >
        <el-icon><Plus /></el-icon>
        新增段落
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="HEADING">新增标题</el-dropdown-item>
            <el-dropdown-item command="CODE">新增代码块</el-dropdown-item>
            <el-dropdown-item command="TODO">新增待办</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </header>

    <el-alert
      v-if="readonly"
      class="block-alert"
      title="当前文档处于只读状态，不能编辑内容。"
      type="info"
      show-icon
      :closable="false"
    />

    <el-alert
      v-if="conflictMessage"
      class="block-alert"
      :title="conflictMessage"
      type="warning"
      show-icon
      closable
      @close="conflictMessage = ''"
    >
      <template #default>
        <el-button text type="primary" @click="loadBlocks">刷新内容</el-button>
      </template>
    </el-alert>

    <el-alert
      v-if="errorMessage"
      class="block-alert"
      :title="errorMessage"
      type="error"
      show-icon
      :closable="false"
    >
      <template #default>
        <el-button text type="primary" @click="loadBlocks">重新加载</el-button>
      </template>
    </el-alert>

    <el-skeleton v-if="loading" :rows="6" animated />

    <el-empty
      v-else-if="blocks.length === 0"
      description="这篇文档还没有内容块"
    >
      <el-button
        type="primary"
        :icon="Plus"
        :disabled="readonly"
        @click="handleCreate('PARAGRAPH')"
      >
        创建第一个段落
      </el-button>
    </el-empty>

    <div v-else class="block-list">
      <TiptapBlock
        v-for="(block, index) in blocks"
        :key="block.id"
        :ref="component => bindBlockComponent(block.id, component)"
        :block="block"
        :is-first="index === 0"
        :is-last="index === blocks.length - 1"
        :busy="busyBlockId === block.id"
        :readonly="readonly"
        :compact-reading="compactReading"
        :editing="editingBlockId === block.id"
        :save-error="editingBlockId === block.id ? editingSaveError : ''"
        :editing-users="editingUsersByBlock(block.id)"
        :class="{
          'is-focused': focusedBlockId === block.id,
          'is-linked-active': activeBlockId === block.id,
        }"
        :data-block-id="block.id"
        @save="handleSave"
        @delete="handleDelete"
        @move-up="handleMove(block, index - 1)"
        @move-down="handleMove(block, index + 1)"
        @editing-start="emit('editing-start', block.id)"
        @editing-stop="emit('editing-stop', block.id)"
        @select="emit('select-block', block.id)"
        @edit-request="requestEditBlock(block.id)"
        @save-intent="handleSaveIntent(block.id, $event)"
        @cancel-request="requestCancelEditing(block.id)"
        @dirty-change="handleDirtyChange"
      />
    </div>
  </section>
</template>

<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
  type ComponentPublicInstance,
} from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import {
  createBlock,
  deleteBlock,
  listBlocks,
  moveBlock,
  updateBlock,
  type DocumentBlock,
  type DocumentBlockContent,
  type DocumentBlockType,
} from '@/api/block';
import TiptapBlock from '@/components/editor/TiptapBlock.vue';
import {
  CollaborationOperationError,
  type EditingState,
} from '@/composables/useDocumentCollaboration';
import { isConflictError, readableError } from '@/utils/error';

const props = defineProps<{
  documentId: string;
  focusBlockId?: string | null;
  activeBlockId?: string | null;
  readonly?: boolean;
  remoteBlock?: DocumentBlock | null;
  editingStates?: EditingState[];
  saveViaCollaboration?: (
    block: DocumentBlock,
    content: DocumentBlockContent,
  ) => Promise<DocumentBlock>;
  compactReading?: boolean;
}>();

const emit = defineEmits<{
  'editing-start': [blockId: string];
  'editing-stop': [blockId: string];
  'select-block': [blockId: string];
  'blocks-loaded': [blocks: DocumentBlock[]];
  'block-deleted': [blockId: string];
}>();

const blocks = ref<DocumentBlock[]>([]);
const editorRoot = ref<HTMLElement | null>(null);
const loading = ref(false);
const creating = ref(false);
const busyBlockId = ref<string | null>(null);
const focusedBlockId = ref<string | null>(null);
const editingBlockId = ref<string | null>(null);
const dirtyByBlock = ref<Record<string, boolean>>({});
const editingSaveError = ref('');
const conflictMessage = ref('');
const errorMessage = ref('');
const blockComponents = new Map<string, InstanceType<typeof TiptapBlock>>();

const initialText: Record<DocumentBlockType, string> = {
  PARAGRAPH: '新的段落',
  HEADING: '新的标题',
  CODE: '// 输入代码',
  TODO: '待办事项',
};

function initialContent(type: DocumentBlockType): DocumentBlockContent {
  const text = initialText[type];
  return {
    text,
    schemaVersion: 1,
    document: initialDocument(type, text),
  };
}

function initialDocument(type: DocumentBlockType, text: string) {
  const inlineContent = [{ type: 'text', text }];
  switch (type) {
    case 'HEADING':
      return {
        type: 'doc',
        content: [{
          type: 'heading',
          attrs: { level: 2 },
          content: inlineContent,
        }],
      };
    case 'CODE':
      return {
        type: 'doc',
        content: [{ type: 'codeBlock', content: inlineContent }],
      };
    case 'TODO':
      return {
        type: 'doc',
        content: [{
          type: 'taskList',
          content: [{
            type: 'taskItem',
            attrs: { checked: false },
            content: [{ type: 'paragraph', content: inlineContent }],
          }],
        }],
      };
    default:
      return {
        type: 'doc',
        content: [{ type: 'paragraph', content: inlineContent }],
      };
  }
}

onMounted(() => {
  window.addEventListener('beforeunload', handleBeforeUnload);
  void loadBlocks();
});

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload);
});

onBeforeRouteLeave(async () => confirmLeave());

watch(
  () => props.documentId,
  () => {
    editingBlockId.value = null;
    dirtyByBlock.value = {};
    editingSaveError.value = '';
    void loadBlocks();
  },
);

watch(
  () => props.focusBlockId,
  () => {
    void focusRequestedBlock();
  },
);

watch(
  () => props.activeBlockId,
  (blockId) => {
    if (blockId) void focusBlock(blockId);
  },
);

watch(blocks, value => emit('blocks-loaded', [...value]), { deep: true });

watch(
  () => props.remoteBlock,
  (block) => {
    if (block?.documentId === props.documentId) {
      replaceBlock(block);
    }
  },
);

async function loadBlocks(silent = false) {
  if (!silent) {
    loading.value = true;
  }
  conflictMessage.value = '';
  errorMessage.value = '';
  try {
    blocks.value = await listBlocks(props.documentId);
    await focusRequestedBlock();
  } catch (error) {
    errorMessage.value = readableError(error, '内容块加载失败');
  } finally {
    loading.value = false;
  }
}

async function focusRequestedBlock() {
  if (!props.focusBlockId) {
    focusedBlockId.value = null;
    return;
  }
  await nextTick();
  const target = editorRoot.value?.querySelector<HTMLElement>(
    `[data-block-id="${props.focusBlockId}"]`,
  );
  if (!target) {
    return;
  }
  focusedBlockId.value = props.focusBlockId;
  target.scrollIntoView({ behavior: 'smooth', block: 'center' });
  window.setTimeout(() => {
    if (focusedBlockId.value === props.focusBlockId) {
      focusedBlockId.value = null;
    }
  }, 2200);
}

async function focusBlock(blockId: string) {
  await nextTick();
  const target = editorRoot.value?.querySelector<HTMLElement>(
    `[data-block-id="${blockId}"]`,
  );
  if (!target) {
    focusedBlockId.value = null;
    return false;
  }
  focusedBlockId.value = blockId;
  target.scrollIntoView({ behavior: 'smooth', block: 'center' });
  return true;
}

function clearBlockFocus() {
  focusedBlockId.value = null;
}

async function handleCreate(type: DocumentBlockType) {
  if (props.readonly) {
    return;
  }
  creating.value = true;
  try {
    const block = await createBlock(props.documentId, {
      type,
      content: initialContent(type),
    });
    blocks.value = [...blocks.value, block];
    await nextTick();
    editorRoot.value
      ?.querySelector<HTMLElement>(`[data-block-id="${block.id}"] .tiptap-content`)
      ?.focus();
    ElMessage.success('内容块已创建');
  } catch (error) {
    ElMessage.error(readableError(error, '内容块创建失败'));
  } finally {
    creating.value = false;
  }
}

async function handleSave(block: DocumentBlock, content: DocumentBlockContent) {
  if (props.readonly) {
    return false;
  }
  busyBlockId.value = block.id;
  editingSaveError.value = '';
  conflictMessage.value = '';
  try {
    const updated = props.saveViaCollaboration
      ? await props.saveViaCollaboration(block, content)
      : await updateBlock(props.documentId, block.id, {
          content,
          expectedVersion: block.version,
        });
    replaceBlock(updated);
    dirtyByBlock.value = { ...dirtyByBlock.value, [block.id]: false };
    return true;
  } catch (error) {
    editingSaveError.value = readableError(error, '内容块保存失败');
    if (
      isConflictError(error)
      || (error instanceof CollaborationOperationError && error.status === 'CONFLICT')
    ) {
      conflictMessage.value = '当前内容块已被其他成员修改，请刷新内容后再继续编辑。';
    }
    ElMessage.error(readableError(error, '内容块保存失败'));
    return false;
  } finally {
    busyBlockId.value = null;
  }
}

function bindBlockComponent(
  blockId: string,
  component: Element | ComponentPublicInstance | null,
) {
  if (component) {
    blockComponents.set(blockId, component as InstanceType<typeof TiptapBlock>);
  } else {
    blockComponents.delete(blockId);
  }
}

function handleDirtyChange(blockId: string, value: boolean) {
  dirtyByBlock.value = { ...dirtyByBlock.value, [blockId]: value };
  if (editingBlockId.value === blockId && value) editingSaveError.value = '';
}

async function requestEditBlock(blockId: string) {
  if (props.readonly || !props.compactReading || editingBlockId.value === blockId) return;
  const current = editingBlockId.value;
  if (!current || !dirtyByBlock.value[current]) {
    activateEditingBlock(blockId);
    return;
  }
  try {
    await ElMessageBox.confirm(
      '当前 Block 有未保存修改。请选择保存后切换，或放弃修改后切换。',
      '切换编辑 Block',
      {
        confirmButtonText: '保存并编辑新 Block',
        cancelButtonText: '放弃并编辑新 Block',
        distinguishCancelAndClose: true,
        type: 'warning',
      },
    );
    if (await saveEditingBlock(current, 'stay')) activateEditingBlock(blockId);
  } catch (action) {
    if (action === 'cancel') {
      discardEditingBlock(current);
      activateEditingBlock(blockId);
    }
  }
}

function activateEditingBlock(blockId: string) {
  const previous = editingBlockId.value;
  if (previous && previous !== blockId) emit('editing-stop', previous);
  editingBlockId.value = blockId;
  editingSaveError.value = '';
  emit('editing-start', blockId);
  void nextTick(() => blockComponents.get(blockId)?.focusEditor());
}

async function handleSaveIntent(blockId: string, intent: 'stay' | 'finish') {
  if (editingBlockId.value !== blockId) return;
  await saveEditingBlock(blockId, intent);
}

async function saveEditingBlock(blockId: string, intent: 'stay' | 'finish') {
  const payload = blockComponents.get(blockId)?.getSavePayload();
  if (!payload) {
    if (intent === 'finish') exitEditingBlock(blockId);
    return true;
  }
  const saved = await handleSave(payload.block, payload.content);
  if (saved && intent === 'finish') exitEditingBlock(blockId);
  return saved;
}

async function requestCancelEditing(blockId: string) {
  if (editingBlockId.value !== blockId) return;
  if (!dirtyByBlock.value[blockId]) {
    exitEditingBlock(blockId);
    return;
  }
  try {
    await ElMessageBox.confirm(
      '当前 Block 有未保存修改。',
      '放弃修改',
      {
        confirmButtonText: '放弃修改',
        cancelButtonText: '继续编辑',
        type: 'warning',
      },
    );
    discardEditingBlock(blockId);
    exitEditingBlock(blockId);
  } catch {
    // Keep the explicit editing state and draft.
  }
}

function discardEditingBlock(blockId: string) {
  blockComponents.get(blockId)?.discardDraft();
  dirtyByBlock.value = { ...dirtyByBlock.value, [blockId]: false };
  editingSaveError.value = '';
}

function exitEditingBlock(blockId: string) {
  if (editingBlockId.value !== blockId) return;
  editingBlockId.value = null;
  editingSaveError.value = '';
  emit('editing-stop', blockId);
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!hasUnsavedChanges()) return;
  event.preventDefault();
  event.returnValue = '';
}

function hasUnsavedChanges() {
  const blockId = editingBlockId.value;
  return Boolean(blockId && dirtyByBlock.value[blockId]);
}

async function confirmLeave() {
  const blockId = editingBlockId.value;
  if (!blockId || !dirtyByBlock.value[blockId]) return true;
  try {
    await ElMessageBox.confirm(
      '当前 Block 有未保存修改，离开后这些修改会丢失。',
      '离开联动对照',
      {
        confirmButtonText: '放弃修改并离开',
        cancelButtonText: '继续编辑',
        type: 'warning',
      },
    );
    discardEditingBlock(blockId);
    exitEditingBlock(blockId);
    return true;
  } catch {
    return false;
  }
}

async function handleDelete(block: DocumentBlock) {
  if (props.readonly) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      '删除后该内容块会从当前文档移除，此操作暂不支持撤销。',
      '删除内容块',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    );
  } catch {
    return;
  }

  busyBlockId.value = block.id;
  try {
    await deleteBlock(props.documentId, block.id);
    // 后端删除时已对剩余块重排 sort_order（被重排块 version + 1），
    // 这里静默重新拉取权威列表：序号、version 与后端完全同步，
    // 避免本地 map 造成乐观锁版本错位和序号漂移。
    await loadBlocks(true);
    ElMessage.success('内容块已删除');
    emit('block-deleted', block.id);
  } catch (error) {
    ElMessage.error(readableError(error, '内容块删除失败'));
  } finally {
    busyBlockId.value = null;
  }
}

async function handleMove(block: DocumentBlock, targetIndex: number) {
  if (props.readonly || targetIndex < 0 || targetIndex >= blocks.value.length) {
    return;
  }
  busyBlockId.value = block.id;
  try {
    blocks.value = await moveBlock(props.documentId, block.id, targetIndex);
  } catch (error) {
    ElMessage.error(readableError(error, '内容块排序失败'));
  } finally {
    busyBlockId.value = null;
  }
}

function replaceBlock(block: DocumentBlock) {
  blocks.value = blocks.value.map((item) => (
    item.id === block.id ? block : item
  ));
}

function editingUsersByBlock(blockId: string) {
  return (props.editingStates ?? []).filter((state) => state.blockId === blockId);
}

defineExpose({
  focusBlock,
  clearBlockFocus,
  confirmLeave,
  editingBlockId,
});
</script>
