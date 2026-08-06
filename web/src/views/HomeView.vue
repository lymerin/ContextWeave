<template>
  <main
    class="app-shell"
    :class="{ 'is-sidebar-collapsed': sidebarCollapsed }"
  >
    <AppSidebar v-model="sidebarCollapsed" active="home" />

    <section class="workspace">
      <header class="topbar">
        <div>
          <p class="eyebrow">Knowledge Core</p>
          <h1>工作区</h1>
        </div>

        <div class="topbar-actions">
          <NotificationCenter />

          <span class="current-user">
            {{
              authStore.currentUser?.displayName
                || authStore.currentUser?.username
            }}
          </span>

          <el-button
            type="primary"
            :icon="Plus"
            @click="dialogVisible = true"
          >
            创建工作区
          </el-button>

          <el-button
            :icon="SwitchButton"
            :loading="loggingOut"
            @click="handleLogout"
          >
            退出
          </el-button>
        </div>
      </header>

      <section class="content-panel">
        <div class="panel-header">
          <div>
            <h2>我的工作区</h2>
            <p>
              工作区是文档树、Block 编辑和协作权限的业务入口。
              先创建工作区，再在里面维护项目知识。
            </p>
          </div>

          <el-tag type="success" effect="light">
            {{ workspaces.length }} 个工作区
          </el-tag>
        </div>

        <el-alert
          v-if="errorMessage"
          class="workspace-alert"
          :title="errorMessage"
          type="error"
          show-icon
          :closable="false"
        >
          <template #default>
            <el-button
              text
              type="primary"
              @click="loadWorkspaces"
            >
              重新加载
            </el-button>
          </template>
        </el-alert>

        <div v-if="loading" class="workspace-loading">
          <el-skeleton :rows="4" animated />
        </div>

        <el-empty
          v-if="workspaces.length === 0"
          description="还没有工作区"
        >
          <el-button
            type="primary"
            :icon="Plus"
            @click="dialogVisible = true"
          >
            创建第一个工作区
          </el-button>
        </el-empty>

        <div v-else class="workspace-grid">
          <article
            v-for="workspaceItem in workspaces"
            :key="workspaceItem.id"
            class="workspace-card"
            @click="openWorkspace(workspaceItem.id)"
          >
            <div class="workspace-card-header">
              <h3>{{ workspaceItem.name }}</h3>

              <el-tag size="small" effect="light">
                {{ roleText(workspaceItem.currentUserRole) }}
              </el-tag>
            </div>

            <div class="workspace-card-stats">
              <template v-if="statsLoading">
                <span class="workspace-stat-muted">统计中…</span>
              </template>
              <template v-else>
                <span>{{ documentCounts[workspaceItem.id] ?? 0 }} 篇文档</span>
                <span
                  class="workspace-pending-stat"
                  :class="{ 'has-pending': (pendingCounts[workspaceItem.id] ?? 0) > 0 }"
                >
                  {{ pendingCounts[workspaceItem.id] ?? 0 }} 项待审批
                </span>
              </template>
            </div>

            <p>
              创建时间：{{ formatTime(workspaceItem.createdAt) }}
            </p>

            <p>
              更新时间：{{ formatTime(workspaceItem.updatedAt) }}
            </p>

            <div class="workspace-card-bottom">
              <span class="card-link">
                进入工作区 →
              </span>

              <el-dropdown
                trigger="click"
                @click.stop
                @command="handleWorkspaceCommand($event, workspaceItem)"
              >
                <button
                  class="workspace-card-menu"
                  type="button"
                  aria-label="工作区更多操作"
                  @click.stop
                >
                  ⋯
                </button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item
                      command="rename"
                      @click.stop
                    >
                      重命名工作区
                    </el-dropdown-item>
                    <el-dropdown-item
                      command="delete"
                      class="workspace-delete-menu-item"
                      @click.stop
                    >
                      删除工作区
                    </el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </article>
        </div>
      </section>
    </section>

    <WorkspaceCreateDialog
      v-model="dialogVisible"
      @create="handleCreateWorkspace"
    />

    <WorkspaceRenameDialog
      v-model="renameDialogVisible"
      :workspace-name="workspacePendingRename?.name || ''"
      :loading="renamingWorkspace"
      @confirm="handleRenameWorkspace"
      @closed="workspacePendingRename = null"
    />

    <WorkspaceDeleteDialog
      v-model="deleteDialogVisible"
      :workspace-name="workspacePendingDelete?.name || ''"
      :loading="deletingWorkspace"
      @confirm="handleDeleteWorkspace"
    />
  </main>
</template>

<script setup lang="ts">
import { Plus, SwitchButton } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { listDocumentTree, type DocumentTreeNode } from '@/api/document';
import { getPendingDocumentChangeCount } from '@/api/documentChange';
import { registerGitRepository } from '@/api/git';
import {
  createWorkspace,
  deleteWorkspace,
  listWorkspaces,
  renameWorkspace,
  type Workspace,
  type WorkspaceRole,
} from '@/api/workspace';
import { orderWorkspacesByRecent, recordWorkspaceVisit } from '@/utils/workspaceRecent';
import AppSidebar from '@/components/layout/AppSidebar.vue';
import NotificationCenter from '@/components/notification/NotificationCenter.vue';
import WorkspaceCreateDialog from '@/components/workspace/WorkspaceCreateDialog.vue';
import WorkspaceDeleteDialog from '@/components/workspace/WorkspaceDeleteDialog.vue';
import WorkspaceRenameDialog from '@/components/workspace/WorkspaceRenameDialog.vue';
import { useAuthStore } from '@/stores/auth';
import { readableError } from '@/utils/error';

interface WorkspaceCreatePayload {
  name: string;
  repositoryUrl: string;
  branch: string;
}

const authStore = useAuthStore();
const route = useRoute();
const router = useRouter();

const workspaces = ref<Workspace[]>([]);
const documentCounts = ref<Record<string, number>>({});
const pendingCounts = ref<Record<string, number>>({});
const statsLoading = ref(false);
const loading = ref(false);
const loggingOut = ref(false);
const dialogVisible = ref(false);
const renameDialogVisible = ref(false);
const renamingWorkspace = ref(false);
const workspacePendingRename = ref<Workspace | null>(null);
const deleteDialogVisible = ref(false);
const deletingWorkspace = ref(false);
const workspacePendingDelete = ref<Workspace | null>(null);
const errorMessage = ref('');

const sidebarCollapsed = ref(
  localStorage.getItem('devcollab.sidebar.collapsed') === 'true',
);

onMounted(() => {
  if (route.query.action === 'create') {
    dialogVisible.value = true;
  }
  void loadWorkspaces();
});

async function loadWorkspaces() {
  loading.value = true;
  errorMessage.value = '';

  try {
    const loaded = await listWorkspaces();
    // Recently visited workspaces first, then the rest in API order.
    workspaces.value = orderWorkspacesByRecent(loaded.map(item => item.id))
      .map(id => loaded.find(item => item.id === id))
      .filter((item): item is Workspace => item !== undefined);
    void loadWorkspaceStats(loaded);
  } catch (error) {
    errorMessage.value = readableError(
      error,
      '工作区加载失败',
    );
  } finally {
    loading.value = false;
  }
}

async function loadWorkspaceStats(workspacesList: Workspace[]) {
  statsLoading.value = true;
  try {
    const [treeResults, pendingResults] = await Promise.all([
      Promise.allSettled(workspacesList.map(item => listDocumentTree(item.id))),
      Promise.allSettled(workspacesList.map(item => getPendingDocumentChangeCount(item.id))),
    ]);
    const nextDocumentCounts: Record<string, number> = {};
    const nextPendingCounts: Record<string, number> = {};
    workspacesList.forEach((item, index) => {
      const tree = treeResults[index];
      nextDocumentCounts[item.id] = tree.status === 'fulfilled'
        ? countDocumentNodes(tree.value)
        : 0;
      const pending = pendingResults[index];
      nextPendingCounts[item.id] = pending.status === 'fulfilled' ? pending.value : 0;
    });
    documentCounts.value = nextDocumentCounts;
    pendingCounts.value = nextPendingCounts;
  } finally {
    statsLoading.value = false;
  }
}

function countDocumentNodes(nodes: DocumentTreeNode[]): number {
  return nodes.reduce(
    (total, node) => total + 1 + countDocumentNodes(node.children),
    0,
  );
}

async function handleCreateWorkspace(
  payload: WorkspaceCreatePayload,
) {
  let workspace: Workspace | null = null;

  try {
    // 第一步：创建工作区
    workspace = await createWorkspace({
      name: payload.name,
    });

    workspaces.value = [
      workspace,
      ...workspaces.value,
    ];

    // 第二步：把 GitHub 仓库登记到该工作区
    await registerGitRepository(workspace.id, {
      name: repositoryNameFromUrl(payload.repositoryUrl),
      provider: 'GITHUB',
      remoteUrl: payload.repositoryUrl,
      defaultBranch: payload.branch,
    });

    dialogVisible.value = false;

    ElMessage.success(
      '工作区创建成功，仓库正在同步',
    );

    // 第三步：进入现有代码工作台
    await openWorkspace(workspace.id);
  } catch (error) {
    if (workspace !== null) {
      dialogVisible.value = false;

      ElMessage.error(
        readableError(
          error,
          '工作区已创建，但 GitHub 仓库导入失败',
        ),
      );

      return;
    }

    ElMessage.error(
      readableError(error, '工作区创建失败'),
    );
  }
}

async function openWorkspace(workspaceId: string) {
  recordWorkspaceVisit(workspaceId);
  await router.push({
    name: 'workspace-code',
    params: {
      workspaceId,
    },
  });
}

function handleWorkspaceCommand(
  command: string | number | object,
  workspace: Workspace,
) {
  if (command === 'rename') {
    workspacePendingRename.value = workspace;
    renameDialogVisible.value = true;
    return;
  }
  if (command === 'delete') {
    openDeleteDialog(workspace);
  }
}

async function handleRenameWorkspace(name: string) {
  const workspace = workspacePendingRename.value;
  if (workspace === null || renamingWorkspace.value) {
    return;
  }

  renamingWorkspace.value = true;
  try {
    const renamed = await renameWorkspace(workspace.id, { name });
    workspaces.value = workspaces.value.map(
      item => item.id === renamed.id ? renamed : item,
    );
    renameDialogVisible.value = false;
    workspacePendingRename.value = null;
    ElMessage.success('工作区名称已更新');
  } catch (error) {
    ElMessage.error(
      readableError(error, '工作区重命名失败'),
    );
  } finally {
    renamingWorkspace.value = false;
  }
}

function openDeleteDialog(workspace: Workspace) {
  workspacePendingDelete.value = workspace;
  deleteDialogVisible.value = true;
}

async function handleDeleteWorkspace() {
  const workspace = workspacePendingDelete.value;
  if (workspace === null || deletingWorkspace.value) {
    return;
  }

  deletingWorkspace.value = true;
  try {
    await deleteWorkspace(workspace.id);
    workspaces.value = workspaces.value.filter(
      item => item.id !== workspace.id,
    );
    deleteDialogVisible.value = false;
    workspacePendingDelete.value = null;
    ElMessage.success('工作区已删除');
  } catch (error) {
    ElMessage.error(
      readableError(error, '工作区删除失败'),
    );
  } finally {
    deletingWorkspace.value = false;
  }
}

async function handleLogout() {
  loggingOut.value = true;

  try {
    await authStore.logout();
    ElMessage.success('已退出登录');
    await router.push('/login');
  } finally {
    loggingOut.value = false;
  }
}

function repositoryNameFromUrl(
  repositoryUrl: string,
): string {
  const normalizedUrl = repositoryUrl
    .trim()
    .replace(/\/+$/, '')
    .replace(/\.git$/i, '');

  const lastSlashIndex = normalizedUrl.lastIndexOf('/');

  if (lastSlashIndex === -1) {
    return normalizedUrl;
  }

  return normalizedUrl.substring(lastSlashIndex + 1);
}

function roleText(role: WorkspaceRole) {
  return role === 'ADMIN'
    ? '管理员'
    : '普通用户';
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value));
}
</script>
