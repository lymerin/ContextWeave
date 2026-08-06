import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock('./http', () => ({
  http: { get: mocks.get },
}));

import {
  getLandingSummary,
  listLandingWorkspaces,
  listRecentLandingWorkspaces,
} from './landing';

describe('landing api reservations', () => {
  beforeEach(() => {
    mocks.get.mockReset();
    mocks.get.mockResolvedValue({ data: [] });
  });

  it('reserves the homepage summary endpoint', async () => {
    mocks.get.mockResolvedValueOnce({
      data: {
        documentCount: 28,
        onlineMemberCount: 4,
        pendingReviewCount: 7,
      },
    });

    await getLandingSummary();

    expect(mocks.get).toHaveBeenCalledWith('/landing/summary');
  });

  it('reserves the recent workspace endpoint with a card limit', async () => {
    await listRecentLandingWorkspaces(3);

    expect(mocks.get).toHaveBeenCalledWith('/landing/workspaces/recent', {
      params: { limit: 3 },
    });
  });

  it('reserves the paginated view-all endpoint', async () => {
    mocks.get.mockResolvedValueOnce({
      data: { items: [], page: 1, size: 12, totalElements: 0, totalPages: 0 },
    });

    await listLandingWorkspaces({ page: 1, size: 12 });

    expect(mocks.get).toHaveBeenCalledWith('/landing/workspaces', {
      params: { page: 1, size: 12 },
    });
  });
});
