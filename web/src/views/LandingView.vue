<template>
  <main
    class="landing-page"
    data-ark-theme="ark"
    data-ark-depth="moderate"
  >
    <header class="landing-topbar">
      <button
        class="landing-brand"
        type="button"
        aria-label="返回 ContextWeave 总览"
        @click="scrollToTop"
      >
        <span class="landing-logo" aria-hidden="true"><span /></span>
        <span class="landing-brand-name">ContextWeave</span>
        <span class="landing-beta">BETA</span>
      </button>

      <nav class="landing-navigation" aria-label="主导航">
        <button
          v-for="item in navigationItems"
          :key="item.label"
          class="landing-nav-item"
          :class="{ 'is-active': item.target === null }"
          type="button"
          :aria-current="item.target === null ? 'page' : undefined"
          @click="item.target ? enterProtected(item.target) : scrollToTop()"
        >
          {{ item.label }}
          <span v-if="item.target === null" aria-hidden="true" />
        </button>
      </nav>

      <div class="landing-account">
        <span
          class="landing-online"
          title="演示数据，待接入实时在线接口"
        >
          <span aria-hidden="true" />
          {{ metrics[1].value }} 在线
        </span>
        <button
          class="landing-avatar"
          type="button"
          aria-label="登录或打开账户"
          @click="enterProtected('/workspaces?panel=profile')"
        >
          林
        </button>
      </div>
    </header>

    <div class="landing-content">
      <section class="landing-hero-layout" aria-label="产品总览">
        <article class="landing-hero-panel">
          <div class="landing-hero-copy">
            <span class="landing-eyebrow">
              <span aria-hidden="true" />
              KNOWLEDGE × AGENT COLLABORATION
            </span>
            <h1>先沉淀团队知识，<br>再让智能体参与协作</h1>
            <p>
              在一个工作区内编辑文档、管理权限、实时协作，<br>
              并查看 Agent 审阅过程与运行记录
            </p>
            <div class="landing-actions">
              <button
                class="landing-primary-action"
                type="button"
                @click="enterProtected('/workspaces?action=create')"
              >
                创建工作区
                <span aria-hidden="true">↗</span>
              </button>
            </div>
          </div>
        </article>

        <aside class="landing-metrics" aria-label="关键数据">
          <button
            v-for="metric in metrics"
            :key="metric.label"
            class="landing-metric"
            type="button"
            :title="metric.icon === 'reviews' && authStore.isAuthenticated
              ? '待处理审阅来自现有工作区'
              : `${metric.label}为演示数据，登录后查看实际状态`"
            @click="enterProtected(metric.target)"
          >
            <span
              class="landing-metric-icon"
              :class="metric.tone"
              aria-hidden="true"
            >
              <svg
                v-if="metric.icon === 'documents'"
                viewBox="0 0 24 24"
                fill="none"
              >
                <path d="M8.5 6.5V5.2A2.2 2.2 0 0 1 10.7 3h5.8L21 7.5v8.8a2.2 2.2 0 0 1-2.2 2.2h-1.3" />
                <path d="M16.5 3v4.5H21M5.2 6.5h7.6A2.2 2.2 0 0 1 15 8.7v10.1a2.2 2.2 0 0 1-2.2 2.2H5.2A2.2 2.2 0 0 1 3 18.8V8.7a2.2 2.2 0 0 1 2.2-2.2Z" />
              </svg>
              <svg
                v-else-if="metric.icon === 'members'"
                viewBox="0 0 24 24"
                fill="none"
              >
                <circle cx="9" cy="8" r="3" />
                <path d="M3.5 19v-1.4A4.6 4.6 0 0 1 8.1 13h1.8a4.6 4.6 0 0 1 4.6 4.6V19M15 5.6a3 3 0 0 1 0 5.8M16.5 13.4a4.6 4.6 0 0 1 4 4.6v1" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none">
                <path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3" />
                <circle cx="11" cy="11" r="3.4" />
                <path d="m13.7 13.7 3.1 3.1" />
              </svg>
            </span>
            <span class="landing-metric-copy">
              <small>{{ metric.label }} / {{ metric.microLabel }}</small>
              <strong>{{ metric.value }}</strong>
            </span>
            <span class="landing-chevron" aria-hidden="true">›</span>
          </button>
        </aside>
      </section>

      <section class="landing-flow-panel" aria-labelledby="landing-flow-title">
        <div class="landing-section-heading">
          <div>
            <span class="landing-section-kicker">PROCESS / 02</span>
            <h2 id="landing-flow-title">协作流程</h2>
          </div>
          <p>按下面顺序开始，协作路径始终清晰</p>
        </div>

        <div class="landing-flow-grid">
          <article
            v-for="step in workflowSteps"
            :key="step.number"
            class="landing-step"
          >
            <div class="landing-step-number">
              <span>{{ step.number }}</span><i aria-hidden="true" />
            </div>
            <h3>{{ step.title }}</h3>
            <p>{{ step.description }}</p>
          </article>
        </div>
      </section>

      <section
        class="landing-workspaces"
        aria-labelledby="landing-workspaces-title"
      >
        <div class="landing-workspaces-header">
          <div>
            <span class="landing-section-kicker">WORKSPACE / 03</span>
            <h2 id="landing-workspaces-title">最近工作区</h2>
          </div>
          <button
            class="landing-text-action"
            type="button"
            @click="enterProtected('/workspaces?section=collaboration')"
          >
            查看全部 <span aria-hidden="true">→</span>
          </button>
        </div>

        <div v-if="workspaceLoading" class="landing-workspace-state">
          <span class="landing-workspace-loading" aria-hidden="true" />
          <p>正在读取工作区</p>
        </div>

        <div v-else-if="workspacePreviews.length" class="landing-workspace-list">
          <article
            v-for="(workspace, index) in workspacePreviews"
            :key="workspace.id"
            class="landing-workspace-item"
            :class="{ 'is-selected': index === 0 }"
          >
            <div class="landing-workspace-info">
              <div class="landing-workspace-titleline">
                <h3>{{ workspace.name }}</h3>
                <span>{{ workspace.role }}</span>
              </div>
              <p>{{ workspace.summary }}</p>
            </div>
            <button
              type="button"
              :aria-label="`进入 ${workspace.name} 工作区`"
              @click="enterProtected(`/workspaces/${workspace.id}`)"
            >
              进入 <span aria-hidden="true">↗</span>
            </button>
          </article>
        </div>

        <div v-else class="landing-workspace-state is-empty">
          <div>
            <strong>{{ workspaceLoadFailed ? '工作区暂时无法读取' : '还没有工作区' }}</strong>
            <p>{{ authStore.isAuthenticated ? '创建一个工作区开始协作' : '登录后创建并管理你的工作区' }}</p>
          </div>
          <button
            class="landing-primary-action landing-empty-create"
            type="button"
            @click="enterProtected('/workspaces?action=create')"
          >
            创建工作区
            <span aria-hidden="true">↗</span>
          </button>
        </div>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { listDocumentTree, type DocumentTreeNode } from '@/api/document';
import { getPendingDocumentChangeCount } from '@/api/documentChange';
import { listWorkspaces, type Workspace } from '@/api/workspace';
import { useAuthStore } from '@/stores/auth';
import { orderWorkspacesByRecent, recordWorkspaceVisit } from '@/utils/workspaceRecent';

interface NavigationItem {
  label: string;
  target: string | null;
}

interface LandingMetric {
  label: string;
  microLabel: string;
  value: string;
  icon: 'documents' | 'members' | 'reviews';
  tone: 'is-blue' | 'is-violet' | 'is-mint';
  target: string;
}

interface LandingWorkspaceCard {
  id: string;
  name: string;
  role: string;
  summary: string;
}

const authStore = useAuthStore();
const router = useRouter();
const workspaceLoading = ref(false);
const workspaceLoadFailed = ref(false);

const navigationItems: NavigationItem[] = [
  { label: '总览', target: null },
  { label: '协作空间', target: '/workspaces' },
  { label: '审阅中心', target: '/workspaces?section=reviews' },
  { label: '工作流', target: '/workspaces?section=workflows' },
  { label: '运行记录', target: '/workspaces?section=runs' },
];

const metrics = ref<LandingMetric[]>([
  {
    label: '文档',
    microLabel: 'DOCS',
    value: '28',
    icon: 'documents',
    tone: 'is-blue',
    target: '/workspaces?section=documents',
  },
  {
    label: '在线成员',
    microLabel: 'LIVE',
    value: '4',
    icon: 'members',
    tone: 'is-violet',
    target: '/workspaces?section=collaboration',
  },
  {
    label: '待处理审阅',
    microLabel: 'REVIEW',
    value: '7',
    icon: 'reviews',
    tone: 'is-mint',
    target: '/workspaces?section=reviews',
  },
]);

const workflowSteps = [
  {
    number: '01',
    title: '创建工作区',
    description: '创建项目并邀请协作者，绑定仓库同步知识',
  },
  {
    number: '02',
    title: '编辑与协作',
    description: '多人编辑文档，同步状态与选中的工作上下文',
  },
  {
    number: '03',
    title: 'Agent 审阅',
    description: '提交审阅并查看建议、证据与完整运行轨迹',
  },
];

const workspacePreviews = ref<LandingWorkspaceCard[]>([]);

onMounted(async () => {
  if (!authStore.isAuthenticated) {
    return;
  }

  await loadLandingWorkspaces();
});

async function enterProtected(target: string) {
  if (authStore.isAuthenticated) {
    const workspaceId = target.match(/^\/workspaces\/([^/?]+)/)?.[1];
    if (workspaceId) {
      recordWorkspaceVisit(workspaceId);
    }
    await router.push(target);
    return;
  }

  await router.push({
    name: 'login',
    query: { redirect: '/' },
  });
}

async function loadLandingWorkspaces() {
  workspaceLoading.value = true;
  workspaceLoadFailed.value = false;

  try {
    const loaded = await listWorkspaces();
    const ordered = orderWorkspacesByRecent(loaded.map(workspace => workspace.id))
      .map(id => loaded.find(workspace => workspace.id === id))
      .filter((workspace): workspace is Workspace => workspace !== undefined);
    const recent = ordered.slice(0, 3);
    const [documentResults, pendingResults] = await Promise.all([
      Promise.allSettled(recent.map(workspace => listDocumentTree(workspace.id))),
      Promise.allSettled(ordered.map(workspace => getPendingDocumentChangeCount(workspace.id))),
    ]);
    const pendingByWorkspace = new Map<string, number | null>();

    ordered.forEach((workspace, index) => {
      const result = pendingResults[index];
      pendingByWorkspace.set(
        workspace.id,
        result.status === 'fulfilled' ? result.value : null,
      );
    });

    workspacePreviews.value = recent.map((workspace, index) => {
      const documentResult = documentResults[index];
      return toWorkspaceCard(
        workspace,
        documentResult.status === 'fulfilled'
          ? countDocumentNodes(documentResult.value)
          : null,
        pendingByWorkspace.get(workspace.id) ?? null,
      );
    });

    metrics.value[2].value = pendingResults.every(result => result.status === 'fulfilled')
      ? String(pendingResults.reduce(
        (total, result) => total + (result.status === 'fulfilled' ? result.value : 0),
        0,
      ))
      : '—';
  } catch {
    workspaceLoadFailed.value = true;
    workspacePreviews.value = [];
    metrics.value[2].value = '—';
  } finally {
    workspaceLoading.value = false;
  }
}

function toWorkspaceCard(
  workspace: Workspace,
  documentCount: number | null,
  pendingReviewCount: number | null,
): LandingWorkspaceCard {
  const documentText = documentCount === null
    ? '文档数据暂不可用'
    : `${documentCount} 个文档`;
  const pendingReviewText = pendingReviewCount === null
    ? '待审批数据暂不可用'
    : pendingReviewCount
      ? `${pendingReviewCount} 个待审批`
      : '无待审批';

  return {
    id: workspace.id,
    name: workspace.name,
    role: workspace.currentUserRole === 'ADMIN' ? '管理员' : '成员',
    summary: `${documentText} · ${pendingReviewText} · 最近更新 ${formatUpdatedAt(workspace.updatedAt)}`,
  };
}

function countDocumentNodes(nodes: DocumentTreeNode[]): number {
  return nodes.reduce(
    (total, node) => total + 1 + countDocumentNodes(node.children),
    0,
  );
}

function formatUpdatedAt(updatedAt: string) {
  const date = new Date(updatedAt);
  if (Number.isNaN(date.getTime())) {
    return updatedAt;
  }

  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
</script>

<style scoped>
.landing-page {
  --landing-ink: #090c0e;
  --landing-paper: #ffffff;
  --landing-canvas: #e8edef;
  --landing-line: #dfe6ea;
  --landing-muted: #69757c;
  --landing-cyan: #18c9ef;
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  overflow: hidden;
  color: var(--landing-ink);
  background:
    radial-gradient(ellipse 42% 34% at 8% 12%, rgb(24 201 239 / 15%), transparent 70%),
    radial-gradient(ellipse 34% 32% at 91% 17%, rgb(121 137 255 / 10%), transparent 72%),
    radial-gradient(ellipse 38% 30% at 54% 98%, rgb(54 212 195 / 9%), transparent 72%),
    linear-gradient(180deg, #f2f5f7 0%, var(--landing-canvas) 100%);
  font-family:
    "Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei", system-ui,
    sans-serif;
}

.landing-page::before,
.landing-page::after {
  position: absolute;
  z-index: -1;
  content: "";
  pointer-events: none;
}

.landing-page::before {
  inset: 72px -44px -44px;
  opacity: 0.32;
  background-image: radial-gradient(circle, rgb(45 67 77 / 36%) 0.75px, transparent 0.9px);
  background-size: 18px 18px;
  mask-image: linear-gradient(to bottom, black 0%, black 58%, transparent 96%);
  animation: landing-dot-drift 32s linear infinite;
}

.landing-page::after {
  inset: 72px -60px -60px;
  opacity: 0.22;
  background-image:
    radial-gradient(circle, rgb(24 201 239 / 62%) 1px, transparent 1.25px),
    linear-gradient(90deg, transparent 49.8%, rgb(73 104 116 / 12%) 50%, transparent 50.2%);
  background-position: 12px 12px, 0 0;
  background-size: 72px 72px, 216px 100%;
  mask-image: linear-gradient(100deg, transparent 0%, black 15%, black 83%, transparent 100%);
  animation: landing-sparse-drift 46s ease-in-out infinite alternate;
}

@keyframes landing-dot-drift {
  to { transform: translate3d(36px, 18px, 0); }
}

@keyframes landing-sparse-drift {
  0% { transform: translate3d(-10px, -8px, 0); }
  55% { transform: translate3d(20px, 10px, 0); }
  100% { transform: translate3d(36px, -4px, 0); }
}

.landing-topbar {
  position: relative;
  z-index: 2;
  display: grid;
  grid-template-columns: minmax(250px, 1fr) auto minmax(190px, 1fr);
  align-items: center;
  min-height: 72px;
  gap: 24px;
  padding: 0 36px;
  border-bottom: 1px solid rgb(15 26 31 / 8%);
  background: rgb(255 255 255 / 96%);
  box-shadow: 0 10px 32px rgb(38 56 67 / 6%);
}

.landing-brand,
.landing-account,
.landing-navigation,
.landing-nav-item,
.landing-online,
.landing-actions,
.landing-metric,
.landing-section-heading,
.landing-workspaces-header,
.landing-workspace-titleline,
.landing-workspace-item {
  display: flex;
  align-items: center;
}

.landing-brand {
  width: max-content;
  gap: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}

.landing-logo {
  position: relative;
  width: 38px;
  height: 38px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--landing-ink);
}

.landing-logo::before,
.landing-logo::after,
.landing-logo span {
  position: absolute;
  left: 9px;
  width: 20px;
  height: 2px;
  content: "";
  background: var(--landing-cyan);
  transform: skewX(-28deg);
}

.landing-logo::before { top: 11px; }
.landing-logo span { top: 18px; width: 13px; }
.landing-logo::after { top: 25px; }

.landing-brand-name {
  font-weight: 700;
  letter-spacing: -0.03em;
}

.landing-beta,
.landing-eyebrow,
.landing-section-kicker {
  font-family: "IBM Plex Mono", Consolas, monospace;
  letter-spacing: 0.11em;
}

.landing-beta {
  padding: 3px 7px;
  border: 1px solid #e2e8eb;
  border-radius: 4px;
  background: #f0f3f5;
  color: #566066;
  font-size: 10px;
}

.landing-navigation {
  gap: 4px;
  padding: 5px;
  border: 1px solid #e4e9ec;
  border-radius: 999px;
  background: #eef1f3;
}

.landing-nav-item {
  min-height: 36px;
  gap: 7px;
  padding: 0 16px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: #6d777c;
  cursor: pointer;
  white-space: nowrap;
  font-size: 13px;
  transition: color 180ms ease, background 180ms ease, transform 180ms ease;
}

.landing-nav-item:hover { color: var(--landing-ink); }
.landing-nav-item:active { transform: translateY(1px); }

.landing-nav-item.is-active {
  background: var(--landing-ink);
  box-shadow: 0 5px 16px rgb(9 12 14 / 20%);
  color: #fff;
}

.landing-nav-item.is-active span {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--landing-cyan);
}

.landing-brand:focus-visible,
.landing-nav-item:focus-visible,
.landing-avatar:focus-visible,
.landing-primary-action:focus-visible,
.landing-metric:focus-visible,
.landing-text-action:focus-visible,
.landing-workspace-item > button:focus-visible {
  outline: 2px solid var(--landing-cyan);
  outline-offset: 3px;
}

.landing-account {
  justify-content: flex-end;
  gap: 12px;
}

.landing-online {
  gap: 7px;
  color: #4b565b;
  font-size: 12px;
  white-space: nowrap;
}

.landing-online > span {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #15c783;
  box-shadow: 0 0 0 4px rgb(21 199 131 / 11%);
}

.landing-avatar {
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 50%;
  background: var(--landing-ink);
  color: #fff;
  cursor: pointer;
}

.landing-content {
  width: min(1240px, calc(100% - 48px));
  margin: 0 auto;
  padding: 28px 0 44px;
}

.landing-hero-layout {
  display: grid;
  grid-template-columns: minmax(0, 2.2fr) minmax(260px, 0.72fr);
  gap: 18px;
}

.landing-hero-panel,
.landing-metric,
.landing-flow-panel,
.landing-workspaces {
  border: 1px solid rgb(28 49 59 / 7.5%);
  background: var(--landing-paper);
  box-shadow: 0 16px 45px rgb(39 59 70 / 6.5%);
}

.landing-hero-panel {
  position: relative;
  min-height: 330px;
  overflow: hidden;
  padding: 45px 48px;
  border-radius: 18px;
  background: linear-gradient(102deg, #fff 0%, rgb(255 255 255 / 98%) 55%, rgb(235 247 255 / 76%) 100%);
}

.landing-hero-panel::before {
  position: absolute;
  top: 47px;
  left: 0;
  width: 4px;
  height: 58px;
  content: "";
  background: var(--landing-cyan);
}

.landing-hero-panel::after {
  position: absolute;
  right: -7%;
  bottom: -40%;
  width: 54%;
  height: 94%;
  content: "";
  background: radial-gradient(ellipse, rgb(74 177 255 / 17%), rgb(74 177 255 / 0%) 68%);
  filter: blur(15px);
  pointer-events: none;
}

.landing-hero-copy { position: relative; z-index: 1; }

.landing-eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #24798c;
  font-size: 10px;
}

.landing-eyebrow > span {
  width: 22px;
  height: 1px;
  background: var(--landing-cyan);
}

.landing-hero-panel h1 {
  max-width: 680px;
  margin: 20px 0 15px;
  font-size: clamp(34px, 3.1vw, 48px);
  font-weight: 700;
  letter-spacing: -0.06em;
  line-height: 1.12;
}

.landing-hero-panel p {
  margin: 0;
  color: var(--landing-muted);
  font-size: 14px;
  line-height: 1.8;
}

.landing-actions { gap: 10px; margin-top: 25px; }

.landing-primary-action {
  min-height: 42px;
  padding: 0 18px;
  border-radius: 8px;
  cursor: pointer;
  transition:
    transform 180ms ease,
    box-shadow 220ms ease;
}

.landing-primary-action {
  border: 1px solid var(--landing-ink);
  background: var(--landing-ink);
  color: #fff;
}

.landing-primary-action span {
  display: inline-block;
  margin-left: 14px;
  color: var(--landing-cyan);
  transition: transform 180ms ease;
}

.landing-primary-action:hover {
  box-shadow:
    7px 7px 14px -9px rgb(24 201 239 / 68%),
    13px 12px 24px -17px rgb(24 201 239 / 58%),
    0 10px 18px -13px rgb(24 201 239 / 54%);
  transform: translateY(-2px);
}

.landing-primary-action:hover span { transform: translateX(2px); }

.landing-primary-action:active {
  box-shadow:
    4px 4px 9px -8px rgb(24 201 239 / 62%),
    0 5px 10px -9px rgb(24 201 239 / 48%);
  transform: translateY(0);
  transition-duration: 80ms;
}

.landing-metrics { display: grid; gap: 14px; }

.landing-metric {
  min-height: 100px;
  width: 100%;
  gap: 14px;
  padding: 18px 20px;
  border-radius: 16px;
  color: inherit;
  cursor: pointer;
  text-align: left;
  transition:
    transform 180ms ease,
    border-color 180ms ease,
    box-shadow 220ms ease;
}

.landing-metric:hover {
  border-color: #d9e6ea;
  box-shadow:
    9px 10px 20px -12px rgb(24 201 239 / 58%),
    18px 17px 34px -23px rgb(24 201 239 / 52%),
    0 15px 28px -19px rgb(24 201 239 / 48%);
  transform: translateY(-2px);
}

.landing-metric:active {
  box-shadow:
    5px 5px 11px -9px rgb(24 201 239 / 54%),
    0 7px 13px -11px rgb(24 201 239 / 42%);
  transform: translateY(0);
  transition-duration: 80ms;
}

.landing-metric-icon {
  display: grid;
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 11px;
}

.landing-metric-icon svg {
  width: 19px;
  height: 19px;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.landing-metric-icon.is-blue { color: #197b93; background: #dff8ff; }
.landing-metric-icon.is-violet { color: #6856a8; background: #eeebff; }
.landing-metric-icon.is-mint { color: #247b5f; background: #e2f8ee; }

.landing-metric-copy { display: grid; gap: 2px; }
.landing-metric-copy small { color: var(--landing-muted); font-size: 9px; letter-spacing: 0.06em; }
.landing-metric-copy strong { font-size: 28px; font-weight: 700; line-height: 1; }
.landing-chevron {
  margin-left: auto;
  color: #a5afb4;
  font-size: 24px;
  transition: transform 180ms ease;
}

.landing-metric:hover .landing-chevron { transform: translateX(2px); }

.landing-flow-panel,
.landing-workspaces {
  margin-top: 18px;
  border-radius: 18px;
}

.landing-flow-panel { padding: 28px 30px 30px; }

.landing-section-heading,
.landing-workspaces-header {
  justify-content: space-between;
  gap: 24px;
}

.landing-section-heading h2,
.landing-workspaces-header h2 {
  margin: 3px 0 0;
  font-size: 18px;
}

.landing-section-kicker { color: #789099; font-size: 9px; }
.landing-section-heading > p { margin: 0; color: var(--landing-muted); font-size: 12px; }

.landing-flow-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  margin-top: 26px;
}

.landing-step { padding-right: 28px; }
.landing-step + .landing-step { padding-left: 28px; border-left: 1px solid #edf0f2; }

.landing-step-number { display: flex; align-items: center; gap: 14px; }
.landing-step-number span { color: #d6dfe3; font-size: 34px; font-weight: 700; letter-spacing: -0.05em; line-height: 1; }
.landing-step-number i { height: 1px; flex: 1; background: linear-gradient(to right, #dfe5e8, transparent); }
.landing-step h3 { margin: 17px 0 8px; font-size: 14px; }
.landing-step p { margin: 0; color: var(--landing-muted); font-size: 12px; line-height: 1.65; }

.landing-workspaces { padding: 22px 30px 24px; }

.landing-text-action {
  border: 0;
  background: transparent;
  color: #8a9499;
  cursor: pointer;
  font-size: 12px;
  transition: color 180ms ease;
}

.landing-text-action:hover,
.landing-text-action:focus-visible { color: var(--landing-ink); }

.landing-workspace-list { display: grid; gap: 9px; margin-top: 16px; }

.landing-workspace-item {
  position: relative;
  min-height: 64px;
  gap: 15px;
  padding: 11px 12px 11px 18px;
  border: 1px solid #edf1f2;
  border-radius: 12px;
  background: #f7f9fa;
}

.landing-workspace-item::before {
  position: absolute;
  top: 14px;
  bottom: 14px;
  left: 5px;
  width: 3px;
  border-radius: 999px;
  content: "";
  background: linear-gradient(
    180deg,
    rgb(24 201 239 / 20%) 0%,
    var(--landing-cyan) 22%,
    var(--landing-cyan) 78%,
    rgb(24 201 239 / 20%) 100%
  );
  box-shadow: 0 0 7px rgb(24 201 239 / 20%);
  opacity: 0;
  pointer-events: none;
  transform: scaleY(0.55);
  transition: opacity 180ms ease, transform 220ms cubic-bezier(0.22, 0.8, 0.2, 1);
}

.landing-workspace-item:hover::before,
.landing-workspace-item:focus-within::before {
  opacity: 1;
  transform: scaleY(1);
}

.landing-workspace-info { min-width: 0; flex: 1; }
.landing-workspace-titleline { gap: 8px; }
.landing-workspace-titleline h3 { margin: 0; font-size: 13px; }

.landing-workspace-titleline span {
  padding: 2px 6px;
  border: 1px solid #e4e9eb;
  border-radius: 4px;
  background: #fff;
  color: #65737a;
  font-size: 9px;
}

.landing-workspace-info p { margin: 5px 0 0; color: var(--landing-muted); font-size: 11px; }

.landing-workspace-state {
  display: flex;
  min-height: 82px;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 16px;
  border: 1px solid #edf1f2;
  border-radius: 12px;
  background: #f7f9fa;
  color: var(--landing-muted);
  text-align: center;
}

.landing-workspace-state p { margin: 0; font-size: 12px; }

.landing-workspace-state.is-empty {
  justify-content: space-between;
  padding: 18px 20px;
  text-align: left;
}

.landing-workspace-state.is-empty strong {
  display: block;
  margin-bottom: 5px;
  color: var(--landing-ink);
  font-size: 14px;
}

.landing-workspace-loading {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--landing-cyan);
  box-shadow: 0 0 0 5px rgb(24 201 239 / 12%);
  animation: landing-loading-pulse 1.4s ease-in-out infinite;
}

@keyframes landing-loading-pulse {
  50% { opacity: 0.45; transform: scale(0.78); }
}

.landing-empty-create { flex: 0 0 auto; }

.landing-workspace-item > button {
  min-height: 36px;
  padding: 0 13px;
  border: 1px solid #dfe6e8;
  border-radius: 7px;
  background: #fff;
  box-shadow: 0 1px 2px rgb(35 53 61 / 3%);
  color: #34464d;
  cursor: pointer;
  font-size: 11px;
  transition:
    transform 180ms ease,
    border-color 180ms ease,
    box-shadow 220ms ease;
}

.landing-workspace-item > button:hover {
  border-color: #d9e6ea;
  box-shadow:
    7px 7px 14px -9px rgb(24 201 239 / 68%),
    13px 12px 24px -17px rgb(24 201 239 / 58%),
    0 10px 18px -13px rgb(24 201 239 / 54%);
  transform: translateY(-2px);
}

.landing-workspace-item > button:active {
  box-shadow:
    4px 4px 9px -8px rgb(24 201 239 / 62%),
    0 5px 10px -9px rgb(24 201 239 / 48%);
  transform: translateY(0);
  transition-duration: 80ms;
}

.landing-workspace-item > button span {
  display: inline-block;
  margin-left: 7px;
  color: #259fbb;
  transition: transform 180ms ease;
}

.landing-workspace-item > button:hover span { transform: translateX(2px); }

@media (max-width: 1040px) {
  .landing-topbar { grid-template-columns: auto 1fr auto; padding: 0 20px; }
  .landing-brand-name,
  .landing-beta { display: none; }
  .landing-nav-item { padding: 0 10px; }
}

@media (max-width: 760px), (orientation: portrait) {
  .landing-topbar {
    grid-template-columns: auto auto;
    min-height: 68px;
    gap: 12px;
    padding: 12px 16px;
  }

  .landing-navigation {
    grid-column: 1 / -1;
    order: 3;
    justify-content: flex-start;
    overflow-x: auto;
    border-radius: 12px;
  }

  .landing-account { margin-left: auto; }
  .landing-online { display: none; }
  .landing-content { width: min(100% - 24px, 600px); padding-top: 14px; }
  .landing-hero-layout { grid-template-columns: 1fr; }
  .landing-hero-panel { min-height: 360px; padding: 34px 25px; }
  .landing-hero-panel p br { display: none; }
  .landing-metrics { grid-template-columns: repeat(3, 1fr); gap: 8px; }
  .landing-metric { min-height: 92px; align-items: flex-start; gap: 8px; padding: 13px; }
  .landing-metric-icon { width: 34px; height: 34px; }
  .landing-metric-copy small { max-width: 60px; }
  .landing-chevron { display: none; }
  .landing-flow-panel,
  .landing-workspaces { padding: 22px 18px; }
  .landing-section-heading { align-items: flex-start; flex-direction: column; gap: 8px; }
  .landing-flow-grid { grid-template-columns: 1fr; gap: 18px; }
  .landing-step,
  .landing-step + .landing-step { padding: 0; border-left: 0; }
  .landing-step + .landing-step { padding-top: 18px; border-top: 1px solid #edf0f2; }
  .landing-workspace-item { align-items: flex-start; flex-wrap: wrap; }
  .landing-workspace-item > button { margin-left: auto; }
  .landing-workspace-state.is-empty { align-items: flex-start; flex-direction: column; }
}

@media (max-width: 520px) {
  .landing-navigation { gap: 2px; }
  .landing-nav-item { min-height: 34px; padding: 0 9px; font-size: 12px; }
  .landing-hero-panel h1 { font-size: 32px; }
  .landing-metrics { grid-template-columns: 1fr; }
  .landing-metric { min-height: 78px; align-items: center; }
  .landing-metric-copy small { max-width: none; }
  .landing-workspaces-header { align-items: flex-end; }
}

@media (prefers-reduced-motion: reduce) {
  .landing-page::before,
  .landing-page::after,
  .landing-workspace-loading { animation: none; }
  .landing-nav-item,
  .landing-primary-action,
  .landing-primary-action span,
  .landing-metric,
  .landing-chevron,
  .landing-workspace-item > button,
  .landing-workspace-item > button span { transition: none; }
}
</style>
