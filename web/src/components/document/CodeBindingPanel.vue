<template>
  <section class="workbench-tab-panel code-binding-panel">
    <div class="panel-title-row">
      <div>
        <p class="eyebrow">Code Binding</p>
        <h3>代码路径关联</h3>
      </div>
      <el-button size="small" type="primary" :disabled="repositories.length === 0" @click="dialogVisible = true">
        新增关联
      </el-button>
    </div>

    <el-alert
      v-if="repositories.length === 0 && !loading"
      title="请先在工作区绑定 Git 仓库"
      type="info"
      show-icon
      :closable="false"
    />
    <el-skeleton v-if="loading" :rows="4" animated />
    <el-empty v-else-if="bindings.length === 0" description="当前文档尚未关联代码路径" />
    <div v-else class="binding-list">
      <article v-for="binding in bindings" :key="binding.id" class="binding-card">
        <div>
          <strong>{{ repositoryName(binding.repositoryId) }}</strong>
          <code>{{ binding.pathPattern }}</code>
          <span>{{ binding.blockId ? `Block ${binding.blockId.slice(0, 8)}` : '整篇文档' }}</span>
        </div>
        <el-button text type="danger" @click="handleDelete(binding.id)">删除</el-button>
      </article>
    </div>

    <el-dialog v-model="dialogVisible" title="新增代码路径关联" width="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="仓库">
          <el-select v-model="form.repositoryId" class="full-width">
            <el-option v-for="repository in repositories" :key="repository.id" :label="repository.name" :value="repository.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="路径规则">
          <el-input v-model="form.pathPattern" placeholder="精确路径、src/** 或 **/*.java" />
        </el-form-item>
        <el-form-item label="Block ID（可选）">
          <el-input v-model="form.blockId" placeholder="留空表示关联整篇文档" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive, ref, watch } from 'vue';

import {
  createCodeBinding,
  deleteCodeBinding,
  listCodeBindings,
  listGitRepositories,
  type CodeDocumentBinding,
  type GitRepository,
} from '@/api/git';
import { readableError } from '@/utils/error';

const props = defineProps<{
  workspaceId: string;
  documentId: string;
  refreshToken?: number;
}>();
const repositories = ref<GitRepository[]>([]);
const bindings = ref<CodeDocumentBinding[]>([]);
const loading = ref(false);
const submitting = ref(false);
const dialogVisible = ref(false);
const form = reactive({ repositoryId: '', pathPattern: '', blockId: '' });

onMounted(() => void load());

// 内容块被删除会级联删除其绑定（DB ON DELETE CASCADE）：
// 父组件在块删除后 bump refreshToken，这里重新拉取，避免残留已删块的旧关联。
watch(
  () => props.refreshToken,
  () => {
    if (props.refreshToken) {
      void load();
    }
  },
);

async function load() {
  loading.value = true;
  try {
    [repositories.value, bindings.value] = await Promise.all([
      listGitRepositories(props.workspaceId),
      listCodeBindings(props.documentId),
    ]);
    form.repositoryId ||= repositories.value[0]?.id || '';
  } catch (error) {
    ElMessage.error(readableError(error, '代码关联加载失败'));
  } finally {
    loading.value = false;
  }
}

async function handleCreate() {
  if (!form.repositoryId || !form.pathPattern.trim()) {
    ElMessage.warning('请选择仓库并填写路径规则');
    return;
  }
  submitting.value = true;
  try {
    const binding = await createCodeBinding(props.documentId, {
      repositoryId: form.repositoryId,
      pathPattern: form.pathPattern,
      blockId: form.blockId.trim() || null,
    });
    bindings.value.unshift(binding);
    dialogVisible.value = false;
    form.pathPattern = '';
    form.blockId = '';
    ElMessage.success('代码路径已关联');
  } catch (error) {
    ElMessage.error(readableError(error, '代码路径关联失败'));
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(bindingId: string) {
  try {
    await ElMessageBox.confirm('确定删除这条代码路径关联吗？', '删除关联', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
    await deleteCodeBinding(bindingId);
    bindings.value = bindings.value.filter(item => item.id !== bindingId);
    ElMessage.success('代码路径关联已删除');
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(readableError(error, '删除关联失败'));
  }
}

function repositoryName(repositoryId: string) {
  return repositories.value.find(item => item.id === repositoryId)?.name || repositoryId.slice(0, 8);
}
</script>

<style scoped>
.code-binding-panel, .binding-list { display: grid; gap: 12px; }
.panel-title-row, .binding-card { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.binding-card { padding: 12px; border: 1px solid var(--border-color); border-radius: 10px; }
.binding-card > div { display: grid; min-width: 0; gap: 5px; }
.binding-card code { overflow-wrap: anywhere; }
.binding-card span { color: var(--text-secondary); font-size: 12px; }
.full-width { width: 100%; }
</style>
