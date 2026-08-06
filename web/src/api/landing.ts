import { http } from './http';

export interface LandingSummary {
  documentCount: number;
  onlineMemberCount: number;
  pendingReviewCount: number;
}

export type LandingWorkspaceRole = 'ADMIN' | 'MEMBER';
export type LandingWorkspaceSyncStatus = 'SYNCED' | 'SYNCING' | 'ERROR';

export interface LandingWorkspacePreview {
  id: string;
  name: string;
  role: LandingWorkspaceRole;
  documentCount: number;
  pendingReviewCount: number;
  updatedAt: string;
  syncStatus: LandingWorkspaceSyncStatus;
}

export interface LandingWorkspacePage {
  items: LandingWorkspacePreview[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface LandingWorkspacePageParams {
  page?: number;
  size?: number;
}

/** Homepage counters: documents, online members, and pending reviews. */
export async function getLandingSummary(): Promise<LandingSummary> {
  const { data } = await http.get<LandingSummary>('/landing/summary');
  return data;
}

/** Recent workspace cards displayed on the public homepage. */
export async function listRecentLandingWorkspaces(
  limit = 3,
): Promise<LandingWorkspacePreview[]> {
  const { data } = await http.get<LandingWorkspacePreview[]>(
    '/landing/workspaces/recent',
    { params: { limit } },
  );
  return data;
}

/** Reserved paginated endpoint for a future homepage "view all" experience. */
export async function listLandingWorkspaces(
  params: LandingWorkspacePageParams = {},
): Promise<LandingWorkspacePage> {
  const { data } = await http.get<LandingWorkspacePage>('/landing/workspaces', {
    params: {
      page: params.page ?? 0,
      size: params.size ?? 20,
    },
  });
  return data;
}
