<template>
  <main
    class="workspace-selection-page"
    data-ark-theme="ark"
    data-ark-depth="moderate"
  >
    <header class="workspace-selection-topbar">
      <button
        class="workspace-selection-brand"
        type="button"
        aria-label="返回 ContextWeave 总览"
        @click="navigatePrimary('/')"
      >
        <span class="workspace-selection-logo" aria-hidden="true"><span /></span>
        <span class="workspace-selection-brand-name">ContextWeave</span>
        <span class="workspace-selection-beta">BETA</span>
      </button>

      <nav class="workspace-selection-navigation" aria-label="主导航">
        <button
          v-for="item in navigationItems"
          :key="item.label"
          class="workspace-selection-nav-item"
          :class="{ 'is-active': item.key === 'spaces' }"
          type="button"
          :aria-current="item.key === 'spaces' ? 'page' : undefined"
          @click="navigatePrimary(item.target)"
        >
          {{ item.label }}
          <span v-if="item.key === 'spaces'" aria-hidden="true" />
        </button>
      </nav>

      <div class="workspace-selection-account">
        <span class="workspace-selection-online" title="演示数据，待接入实时在线接口">
          <span aria-hidden="true" />
          4 在线
        </span>
        <el-dropdown trigger="click" @command="handleAccountCommand">
          <button
            class="workspace-selection-avatar"
            type="button"
            :aria-label="`${currentUserName}，打开账户菜单`"
          >
            {{ currentUserInitial }}
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item disabled>{{ currentUserName }}</el-dropdown-item>
              <el-dropdown-item command="logout" divided :disabled="loggingOut">
                {{ loggingOut ? '正在退出' : '退出登录' }}
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <section class="workspace-selection-content" aria-labelledby="workspace-selection-title">
      <div class="workspace-selection-panel">
        <div class="workspace-selection-panel-header">
          <div class="workspace-selection-title-area">
            <span class="workspace-selection-kicker">WORKSPACE / SELECT</span>
            <h1 id="workspace-selection-title">工作区选择</h1>
            <p>管理并访问你参与的协作环境</p>
          </div>
          <button
            class="workspace-selection-create"
            type="button"
            @click="dialogVisible = true"
          >
            创建工作区 <span aria-hidden="true">↗</span>
          </button>
        </div>

        <div v-if="errorMessage" class="workspace-selection-error" role="alert">
          <span>{{ errorMessage }}</span>
          <button type="button" @click="loadWorkspaces">重新加载</button>
        </div>

        <div v-if="loading" class="workspace-selection-loading" aria-label="正在读取工作区">
          <span v-for="index in 3" :key="index" />
        </div>

        <div v-else-if="workspaces.length === 0" class="workspace-selection-empty">
          <div>
            <strong>还没有工作区</strong>
            <p>创建第一个工作区，开始沉淀项目知识并邀请成员协作</p>
          </div>
          <button
            class="workspace-selection-create"
            type="button"
            @click="dialogVisible = true"
          >
            创建工作区 <span aria-hidden="true">↗</span>
          </button>
        </div>

        <div v-else class="workspace-grid">
          <article
            v-for="workspaceItem in workspaces"
            :key="workspaceItem.id"
            class="workspace-card"
            @click="openWorkspace(workspaceItem.id)"
          >
            <div class="workspace-card-header">
              <h2>{{ workspaceItem.name }}</h2>
              <span class="workspace-role" :title="roleText(workspaceItem.currentUserRole)">
                {{ roleText(workspaceItem.currentUserRole) }}
              </span>
            </div>

            <div class="workspace-card-meta">
              <div class="workspace-card-stats">
                <span v-if="statsLoading" class="workspace-stat-muted">统计中…</span>
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
              <p>最近更新：{{ formatTime(workspaceItem.updatedAt) }}</p>
            </div>

            <div class="workspace-card-bottom">
              <button
                class="workspace-enter-button"
                type="button"
                @click.stop="openWorkspace(workspaceItem.id)"
              >
                进入工作区 <span aria-hidden="true">↗</span>
              </button>

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
                    <el-dropdown-item command="rename" @click.stop>
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
      </div>
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
import { ElMessage } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
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

interface WorkspaceNavigationItem {
  key: 'overview' | 'spaces' | 'reviews' | 'workflows' | 'runs';
  label: string;
  target: string;
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

const navigationItems: WorkspaceNavigationItem[] = [
  { key: 'overview', label: '总览', target: '/' },
  { key: 'spaces', label: '协作空间', target: '/workspaces' },
  { key: 'reviews', label: '审阅中心', target: '/workspaces?section=reviews' },
  { key: 'workflows', label: '工作流', target: '/workspaces?section=workflows' },
  { key: 'runs', label: '运行记录', target: '/workspaces?section=runs' },
];

const currentUserName = computed(() => (
  authStore.currentUser?.displayName
  || authStore.currentUser?.username
  || '用户'
));

const currentUserInitial = computed(() => (
  currentUserName.value.trim().slice(0, 1) || '用'
));

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

async function navigatePrimary(target: string) {
  await router.push(target);
}

function handleAccountCommand(command: string | number | object) {
  if (command === 'logout') {
    void handleLogout();
  }
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

<style scoped>
.workspace-selection-page {
  --selection-ink: #090c0e;
  --selection-paper: #ffffff;
  --selection-canvas: #e8edef;
  --selection-muted: #69757c;
  --selection-cyan: #18c9ef;
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  overflow: hidden;
  color: var(--selection-ink);
  background:
    radial-gradient(ellipse 42% 34% at 8% 12%, rgb(24 201 239 / 15%), transparent 70%),
    radial-gradient(ellipse 34% 32% at 91% 17%, rgb(121 137 255 / 10%), transparent 72%),
    radial-gradient(ellipse 38% 30% at 54% 98%, rgb(54 212 195 / 9%), transparent 72%),
    linear-gradient(180deg, #f2f5f7 0%, var(--selection-canvas) 100%);
  font-family:
    "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", system-ui,
    sans-serif;
}

.workspace-selection-page::before,
.workspace-selection-page::after {
  position: absolute;
  z-index: -1;
  content: "";
  pointer-events: none;
}

.workspace-selection-page::before {
  inset: 72px -44px -44px;
  opacity: 0.32;
  background-image: radial-gradient(circle, rgb(45 67 77 / 36%) 0.75px, transparent 0.9px);
  background-size: 18px 18px;
  mask-image: linear-gradient(to bottom, black 0%, black 58%, transparent 96%);
  animation: selection-dot-drift 32s linear infinite;
}

.workspace-selection-page::after {
  inset: 72px -60px -60px;
  opacity: 0.22;
  background-image:
    radial-gradient(circle, rgb(24 201 239 / 62%) 1px, transparent 1.25px),
    linear-gradient(90deg, transparent 49.8%, rgb(73 104 116 / 12%) 50%, transparent 50.2%);
  background-position: 12px 12px, 0 0;
  background-size: 72px 72px, 216px 100%;
  mask-image: linear-gradient(100deg, transparent 0%, black 15%, black 83%, transparent 100%);
  animation: selection-sparse-drift 46s ease-in-out infinite alternate;
}

@keyframes selection-dot-drift {
  to { transform: translate3d(36px, 18px, 0); }
}

@keyframes selection-sparse-drift {
  0% { transform: translate3d(-10px, -8px, 0); }
  55% { transform: translate3d(20px, 10px, 0); }
  100% { transform: translate3d(36px, -4px, 0); }
}

.workspace-selection-topbar {
  position: relative;
  z-index: 5;
  display: grid;
  grid-template-columns: minmax(250px, 1fr) auto minmax(190px, 1fr);
  min-height: 72px;
  align-items: center;
  gap: 24px;
  padding: 0 36px;
  border-bottom: 1px solid rgb(15 26 31 / 8%);
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 10px 32px rgb(38 56 67 / 6%);
}

.workspace-selection-brand,
.workspace-selection-navigation,
.workspace-selection-nav-item,
.workspace-selection-account,
.workspace-selection-online,
.workspace-selection-panel-header,
.workspace-card-header,
.workspace-card-stats,
.workspace-card-bottom,
.workspace-selection-create,
.workspace-enter-button,
.workspace-selection-error,
.workspace-selection-empty {
  display: flex;
  align-items: center;
}

.workspace-selection-brand {
  width: max-content;
  gap: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.workspace-selection-logo {
  position: relative;
  width: 38px;
  height: 38px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--selection-ink);
}

.workspace-selection-logo::before,
.workspace-selection-logo::after,
.workspace-selection-logo span {
  position: absolute;
  left: 9px;
  width: 20px;
  height: 2px;
  content: "";
  background: var(--selection-cyan);
  transform: skewX(-28deg);
}

.workspace-selection-logo::before { top: 11px; }
.workspace-selection-logo span { top: 18px; width: 13px; }
.workspace-selection-logo::after { top: 25px; }

.workspace-selection-brand-name {
  font-weight: 700;
  letter-spacing: -0.03em;
}

.workspace-selection-beta,
.workspace-selection-kicker,
.workspace-role {
  font-family: "IBM Plex Mono", Consolas, monospace;
  letter-spacing: 0.11em;
}

.workspace-selection-beta {
  padding: 3px 7px;
  border: 1px solid #e2e8eb;
  border-radius: 4px;
  background: #f0f3f5;
  color: #566066;
  font-size: 10px;
}

.workspace-selection-navigation {
  gap: 4px;
  padding: 5px;
  border: 1px solid #e4e9ec;
  border-radius: 999px;
  background: #eef1f3;
}

.workspace-selection-nav-item {
  min-height: 36px;
  gap: 7px;
  padding: 0 16px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #6d777c;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
  transition: color 180ms ease, background 180ms ease, transform 180ms ease;
}

.workspace-selection-nav-item:hover { color: var(--selection-ink); }
.workspace-selection-nav-item:active { transform: translateY(1px); }

.workspace-selection-nav-item.is-active {
  background: var(--selection-ink);
  box-shadow: 0 5px 16px rgb(9 12 14 / 20%);
  color: #fff;
}

.workspace-selection-nav-item.is-active span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--selection-cyan);
}

.workspace-selection-account {
  justify-content: flex-end;
  gap: 12px;
}

.workspace-selection-online {
  gap: 7px;
  color: #4b565b;
  font-size: 12px;
  white-space: nowrap;
}

.workspace-selection-online > span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #15c783;
  box-shadow: 0 0 0 4px rgb(21 199 131 / 11%);
}

.workspace-selection-avatar {
  width: 38px;
  height: 38px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: var(--selection-ink);
  color: #fff;
  cursor: pointer;
}

.workspace-selection-content {
  width: min(1380px, calc(100% - 48px));
  margin: 0 auto;
  padding: 34px 0 56px;
}

.workspace-selection-panel {
  min-height: 520px;
  padding: 42px;
  border: 1px solid rgb(255 255 255 / 82%);
  border-radius: 22px;
  background: rgb(255 255 255 / 68%);
  box-shadow: 0 18px 52px rgb(39 59 70 / 5%);
  backdrop-filter: blur(20px);
}

.workspace-selection-panel-header {
  justify-content: space-between;
  align-items: flex-start;
  gap: 24px;
  margin-bottom: 34px;
}

.workspace-selection-kicker {
  display: block;
  margin-bottom: 8px;
  color: #789099;
  font-size: 9px;
}

.workspace-selection-title-area h1 {
  margin: 0;
  font-size: clamp(32px, 4vw, 46px);
  letter-spacing: -0.055em;
  line-height: 1;
}

.workspace-selection-title-area p {
  margin: 13px 0 0;
  color: var(--selection-muted);
  font-size: 14px;
}

.workspace-selection-create,
.workspace-enter-button {
  justify-content: center;
  border: 1px solid var(--selection-ink);
  border-radius: 8px;
  background: var(--selection-ink);
  color: #fff;
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 220ms ease;
}

.workspace-selection-create {
  min-height: 44px;
  padding: 0 18px;
  font-size: 14px;
  font-weight: 600;
}

.workspace-selection-create span,
.workspace-enter-button span {
  display: inline-block;
  margin-left: 13px;
  color: var(--selection-cyan);
  transition: transform 180ms ease;
}

.workspace-selection-create:hover,
.workspace-enter-button:hover {
  box-shadow:
    7px 7px 14px -9px rgb(24 201 239 / 68%),
    13px 12px 24px -17px rgb(24 201 239 / 58%),
    0 10px 18px -13px rgb(24 201 239 / 54%);
  transform: translateY(-2px);
}

.workspace-selection-create:hover span,
.workspace-enter-button:hover span { transform: translateX(2px); }

.workspace-selection-create:active,
.workspace-enter-button:active {
  box-shadow:
    4px 4px 9px -8px rgb(24 201 239 / 62%),
    0 5px 10px -9px rgb(24 201 239 / 48%);
  transform: translateY(0);
  transition-duration: 80ms;
}

.workspace-selection-error {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding: 12px 14px;
  border: 1px solid rgb(184 68 68 / 18%);
  border-radius: 10px;
  background: rgb(255 246 246 / 88%);
  color: #8c3d3d;
  font-size: 13px;
}

.workspace-selection-error button {
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-weight: 600;
}

.workspace-selection-loading,
.workspace-selection-page .workspace-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.workspace-selection-loading > span {
  min-height: 210px;
  border-radius: 16px;
  background: linear-gradient(100deg, #f4f7f8 20%, #fff 42%, #f4f7f8 64%);
  background-size: 220% 100%;
  animation: selection-loading 1.5s ease-in-out infinite;
}

@keyframes selection-loading {
  to { background-position: -120% 0; }
}

.workspace-selection-empty {
  min-height: 180px;
  justify-content: space-between;
  gap: 24px;
  padding: 28px;
  border: 1px solid rgb(28 49 59 / 7.5%);
  border-radius: 16px;
  background: #fff;
}

.workspace-selection-empty strong { font-size: 18px; }
.workspace-selection-empty p { margin: 8px 0 0; color: var(--selection-muted); font-size: 13px; }

.workspace-selection-page .workspace-card {
  position: relative;
  min-width: 0;
  min-height: 220px;
  overflow: hidden;
  padding: 24px;
  border: 1px solid rgb(28 49 59 / 7.5%);
  border-radius: 16px;
  background: var(--selection-paper);
  box-shadow: 0 12px 32px rgb(39 59 70 / 4%);
  cursor: pointer;
  transition: transform 180ms ease, box-shadow 220ms ease, border-color 180ms ease;
}

.workspace-selection-page .workspace-card::before {
  position: absolute;
  top: 20px;
  bottom: 20px;
  left: 7px;
  width: 3px;
  border-radius: 999px;
  content: "";
  background: linear-gradient(
    180deg,
    rgb(24 201 239 / 20%) 0%,
    var(--selection-cyan) 22%,
    var(--selection-cyan) 78%,
    rgb(24 201 239 / 20%) 100%
  );
  box-shadow: 0 0 7px rgb(24 201 239 / 20%);
  opacity: 0;
  pointer-events: none;
  transform: scaleY(0.55);
  transition: opacity 180ms ease, transform 220ms cubic-bezier(0.22, 0.8, 0.2, 1);
}

.workspace-selection-page .workspace-card:hover,
.workspace-selection-page .workspace-card:focus-within {
  border-color: rgb(28 49 59 / 10%);
  box-shadow:
    8px 10px 20px -16px rgb(24 201 239 / 72%),
    15px 16px 34px -24px rgb(24 201 239 / 58%),
    0 18px 42px rgb(39 59 70 / 7%);
  transform: translateY(-3px);
}

.workspace-selection-page .workspace-card:hover::before,
.workspace-selection-page .workspace-card:focus-within::before {
  opacity: 1;
  transform: scaleY(1);
}

.workspace-card-header {
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 22px;
}

.workspace-card-header h2 {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  font-size: 20px;
  letter-spacing: -0.02em;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.workspace-role {
  flex: 0 0 auto;
  padding: 4px 8px;
  border: 1px solid #e4e9ec;
  border-radius: 6px;
  background: #eef1f3;
  color: #566066;
  font-size: 9px;
  font-weight: 600;
}

.workspace-card-meta {
  display: grid;
  gap: 8px;
  color: var(--selection-muted);
  font-size: 13px;
}

.workspace-card-stats { gap: 14px; }
.workspace-card-meta p { margin: 0; }
.workspace-stat-muted { color: #8a9499; }
.workspace-pending-stat.has-pending { color: #117f99; font-weight: 600; }

.workspace-selection-page .workspace-card-bottom {
  justify-content: space-between;
  margin-top: 28px;
  padding-top: 18px;
  border-top: 1px solid #f0f3f5;
}

.workspace-enter-button {
  min-height: 38px;
  padding: 0 13px;
  border-color: #dfe6e8;
  background: #fff;
  color: #34464d;
  font-size: 13px;
  font-weight: 600;
}

.workspace-selection-page .workspace-card-menu {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  padding: 0;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #8a9499;
  cursor: pointer;
  font-size: 19px;
}

.workspace-selection-page .workspace-card-menu:hover,
.workspace-selection-page .workspace-card-menu:focus-visible {
  background: #f0f3f5;
  color: var(--selection-ink);
}

.workspace-selection-brand:focus-visible,
.workspace-selection-nav-item:focus-visible,
.workspace-selection-avatar:focus-visible,
.workspace-selection-create:focus-visible,
.workspace-enter-button:focus-visible,
.workspace-selection-error button:focus-visible,
.workspace-selection-page .workspace-card-menu:focus-visible {
  outline: 2px solid var(--selection-cyan);
  outline-offset: 3px;
}

@media (max-width: 1180px) {
  .workspace-selection-topbar {
    grid-template-columns: auto 1fr auto;
    gap: 16px;
    padding: 0 24px;
  }

  .workspace-selection-brand-name,
  .workspace-selection-beta { display: none; }

  .workspace-selection-navigation { justify-self: center; }
  .workspace-selection-page .workspace-grid,
  .workspace-selection-loading { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 820px), (orientation: portrait) {
  .workspace-selection-topbar {
    grid-template-columns: auto 1fr;
    grid-template-rows: 58px auto;
    min-height: 0;
    padding: 0 18px 10px;
  }

  .workspace-selection-navigation {
    grid-column: 1 / -1;
    grid-row: 2;
    width: 100%;
    justify-content: flex-start;
    overflow-x: auto;
  }

  .workspace-selection-account { grid-column: 2; grid-row: 1; }
  .workspace-selection-content { width: min(100% - 28px, 680px); padding-top: 20px; }
  .workspace-selection-panel { padding: 26px; border-radius: 18px; }
  .workspace-selection-page .workspace-grid,
  .workspace-selection-loading { grid-template-columns: 1fr; }
}

@media (max-width: 520px) {
  .workspace-selection-online { display: none; }
  .workspace-selection-panel-header,
  .workspace-selection-empty { align-items: stretch; flex-direction: column; }
  .workspace-selection-create { width: 100%; }
  .workspace-selection-panel { padding: 20px; }
  .workspace-selection-page .workspace-card { min-height: 206px; padding: 20px; }
}

@media (prefers-reduced-motion: reduce) {
  .workspace-selection-page::before,
  .workspace-selection-page::after,
  .workspace-selection-loading > span { animation: none; }

  .workspace-selection-nav-item,
  .workspace-selection-create,
  .workspace-selection-create span,
  .workspace-selection-page .workspace-card,
  .workspace-selection-page .workspace-card::before,
  .workspace-enter-button,
  .workspace-enter-button span { transition: none; }
}
</style>
