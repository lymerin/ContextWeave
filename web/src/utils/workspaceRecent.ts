const STORAGE_KEY = 'devcollab.workspace.recent';
const MAX_RECENT = 10;

export interface RecentWorkspaceEntry {
  workspaceId: string;
  visitedAt: number;
}

/** Record a workspace visit; returns the updated recent list (most recent first). */
export function recordWorkspaceVisit(workspaceId: string): RecentWorkspaceEntry[] {
  const entries = readRecentWorkspaces().filter(entry => entry.workspaceId !== workspaceId);
  entries.unshift({ workspaceId, visitedAt: Date.now() });
  const trimmed = entries.slice(0, MAX_RECENT);
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(trimmed));
  } catch {
    // Best effort; sorting silently falls back to API order.
  }
  return trimmed;
}

/** Read recent workspaces, most recent first. Empty when nothing recorded yet. */
export function readRecentWorkspaces(): RecentWorkspaceEntry[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((item): item is RecentWorkspaceEntry => Boolean(
        item
        && typeof item === 'object'
        && typeof (item as RecentWorkspaceEntry).workspaceId === 'string'
        && typeof (item as RecentWorkspaceEntry).visitedAt === 'number',
      ))
      .sort((a, b) => b.visitedAt - a.visitedAt)
      .slice(0, MAX_RECENT);
  } catch {
    return [];
  }
}

/** Order workspace ids: recently visited first, then the rest in given order. */
export function orderWorkspacesByRecent(
  workspaceIds: string[],
): string[] {
  const recent = readRecentWorkspaces();
  const recentIds = new Set(recent.map(entry => entry.workspaceId));
  const rest = workspaceIds.filter(id => !recentIds.has(id));
  return [...recent.map(entry => entry.workspaceId).filter(id => workspaceIds.includes(id)), ...rest];
}
