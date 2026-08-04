<template>
  <nav class="linked-workspace-navigation" aria-label="工程上下文导航">
    <button class="nav-item" type="button" title="切换空间" @click="emit('open-workspace')">
      <House class="nav-icon" />
      <span v-if="!collapsed" class="sidebar-label">切换空间</span>
    </button>
    <button
      class="nav-item"
      :class="{ 'is-active': activeItem === 'linked' }"
      type="button"
      title="联动对照"
      @click="emit('open-linked')"
    >
      <Connection class="nav-icon" />
      <span v-if="!collapsed" class="sidebar-label">联动对照</span>
      <span v-if="!collapsed" class="sidebar-nav-badge">{{ linkedCount }}</span>
    </button>
    <button
      class="nav-item"
      :class="{ 'is-active': activeItem === 'review' }"
      type="button"
      :title="reviewNavigationLabel"
      :aria-label="reviewNavigationLabel"
      @click="emit('open-review')"
    >
      <Check class="nav-icon" />
      <span v-if="!collapsed" class="sidebar-label">待我审批</span>
      <span
        v-if="reviewCount > 0"
        class="sidebar-nav-badge is-review-count"
        :class="{ 'is-collapsed': collapsed }"
        data-testid="pending-review-badge"
      >{{ formattedReviewCount }}</span>
    </button>
    <div v-if="!collapsed && activeItem === 'review'" class="review-status-navigation">
      <button
        v-for="item in reviewStatuses"
        :key="item.value"
        type="button"
        :class="{ 'is-active': reviewStatus === item.value }"
        @click="emit('open-review-status', item.value)"
      >
        <span>{{ item.label }}</span>
        <small>{{ reviewStatusCounts[item.value] || 0 }}</small>
      </button>
    </div>
    <button
      class="nav-item"
      :class="{ 'is-active': activeItem === 'drift' }"
      type="button"
      title="文档漂移"
      @click="emit('open-drift')"
    >
      <Warning class="nav-icon" />
      <span v-if="!collapsed" class="sidebar-label">文档漂移</span>
      <span v-if="!collapsed" class="sidebar-nav-badge">{{ driftCount }}</span>
    </button>
  </nav>
</template>

<script setup lang="ts">
import { Check, Connection, House, Warning } from '@element-plus/icons-vue';
import { computed } from 'vue';

const props = withDefaults(defineProps<{
  collapsed?: boolean;
  activeItem?: 'linked' | 'review' | 'drift';
  linkedCount?: number;
  reviewCount?: number;
  driftCount?: number;
  reviewStatus?: 'pending' | 'applied' | 'rejected' | 'stale';
  reviewStatusCounts?: Record<'pending' | 'applied' | 'rejected' | 'stale', number>;
}>(), {
  collapsed: false,
  activeItem: 'linked',
  linkedCount: 0,
  reviewCount: 0,
  driftCount: 0,
  reviewStatus: 'pending',
  reviewStatusCounts: () => ({
    pending: 0,
    applied: 0,
    rejected: 0,
    stale: 0,
  }),
});

const emit = defineEmits<{
  'open-workspace': [];
  'open-linked': [];
  'open-review': [];
  'open-review-status': [status: 'pending' | 'applied' | 'rejected' | 'stale'];
  'open-drift': [];
}>();

const reviewStatuses = [
  { value: 'pending' as const, label: '待处理' },
  { value: 'applied' as const, label: '已应用' },
  { value: 'rejected' as const, label: '已拒绝' },
  { value: 'stale' as const, label: '已失效' },
];

const formattedReviewCount = computed(() => (
  props.reviewCount >= 100 ? '99+' : String(props.reviewCount)
));
const reviewNavigationLabel = computed(() => (
  props.reviewCount > 0
    ? `待我审批，${props.reviewCount}项待处理`
    : '待我审批，暂无待处理项'
));
</script>

<style scoped>
.linked-workspace-navigation {
  display: grid;
  flex: 0 0 auto;
  gap: 3px;
  padding: 12px 8px 10px;
}

.linked-workspace-navigation .nav-item {
  position: relative;
}

.sidebar-nav-badge {
  display: grid;
  min-width: 20px;
  height: 20px;
  margin-left: auto;
  place-items: center;
  border-radius: 999px;
  background: #eef1f6;
  color: #667085;
  font-size: 11px;
  font-weight: 600;
}

.nav-item.is-active .sidebar-nav-badge {
  background: #fff;
  color: #315ee8;
}

.sidebar-nav-badge.is-review-count {
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  background: #e5484d;
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 18px;
}

.nav-item.is-active .sidebar-nav-badge.is-review-count {
  background: #d92d36;
  color: #fff;
}

.sidebar-nav-badge.is-review-count.is-collapsed {
  position: absolute;
  top: 5px;
  right: 4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  line-height: 16px;
}

.review-status-navigation {
  display: grid;
  gap: 2px;
  margin: 0 0 4px 34px;
}

.review-status-navigation button {
  display: flex;
  min-height: 30px;
  align-items: center;
  justify-content: space-between;
  border: 0;
  border-radius: 7px;
  padding: 0 9px;
  background: transparent;
  color: #667085;
  cursor: pointer;
  text-align: left;
}

.review-status-navigation button:hover,
.review-status-navigation button.is-active {
  background: #eef3ff;
  color: #2454d6;
  font-weight: 600;
}

.review-status-navigation small {
  color: #98a2b3;
  font-size: 11px;
}
</style>
