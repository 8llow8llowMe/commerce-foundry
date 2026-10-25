import { ApiError, type PageResponse } from '@cf/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { loadAuditLogs } from './load';
import { auditLog } from './test-fixtures';

const { apiFetch } = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock('@cf/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@cf/api-client')>()),
  apiFetch,
}));

const PATH = '/api/v1/admin/audit-logs?page=0&size=20';

function page<T>(contents: T[]): PageResponse<T> {
  return { contents, page: 0, size: 20, totalElements: contents.length, totalPages: 1 };
}

beforeEach(() => {
  apiFetch.mockReset();
});

describe('loadAuditLogs', () => {
  it('성공이면 페이지를 그대로 준다', async () => {
    const data = page([auditLog()]);
    apiFetch.mockResolvedValueOnce(data);
    await expect(loadAuditLogs(PATH)).resolves.toEqual({ kind: 'ok', data });
    expect(apiFetch).toHaveBeenCalledWith(PATH, { signal: undefined });
  });

  it('401 · 403 은 상태다(AccessNotice)', async () => {
    apiFetch.mockRejectedValueOnce(new ApiError('unauthorized', 401, 'SECURITY_001', null));
    await expect(loadAuditLogs(PATH)).resolves.toEqual({ kind: 'denied', denied: 'unauthorized' });
    apiFetch.mockRejectedValueOnce(new ApiError('forbidden', 403, 'SECURITY_005', null));
    await expect(loadAuditLogs(PATH)).resolves.toEqual({ kind: 'denied', denied: 'forbidden' });
  });

  it('400 · 5xx · 연결 실패는 "다시 불러오기" 다 — 서버 문구를 넘기지 않는다', async () => {
    for (const error of [
      new ApiError('server', 400, 'AUTH_135', 'actorMemberId 는 양수여야 합니다.'),
      new ApiError('unavailable', 503, null, null),
      new ApiError('network', null, null, null),
      new Error('boom'),
    ]) {
      apiFetch.mockRejectedValueOnce(error);
      await expect(loadAuditLogs(PATH)).resolves.toEqual({ kind: 'failed' });
    }
  });

  it('취소되면 그대로 던진다(안내하지 않는다)', async () => {
    const controller = new AbortController();
    controller.abort();
    const reason = new DOMException('aborted', 'AbortError');
    apiFetch.mockRejectedValueOnce(reason);
    await expect(loadAuditLogs(PATH, controller.signal)).rejects.toBe(reason);
  });
});
