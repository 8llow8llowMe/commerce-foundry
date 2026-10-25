import { ApiError, type PageResponse } from '@cf/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MemberListItem } from '@/lib/member/query';
import {
  ADMIN_DIRECTORY_PATH,
  adminOptionLabel,
  loadAdminDirectory,
  toAdminDirectory,
} from './admins';
import { loadAuditLogs } from './load';
import { ADMIN_A, auditLog } from './test-fixtures';

const { apiFetch } = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock('@cf/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@cf/api-client')>()),
  apiFetch,
}));

const PATH = '/api/v1/admin/audit-logs?page=0&size=20';

function page<T>(contents: T[], totalElements = contents.length): PageResponse<T> {
  return { contents, page: 0, size: 20, totalElements, totalPages: 1 };
}

function member(memberId: string, name: string): MemberListItem {
  return {
    memberId,
    email: `${memberId}***@e***.com`,
    name,
    phone: null,
    role: 'ADMIN',
    createdAt: '2026-01-01T00:00:00Z',
  };
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

describe('관리자 이름표', () => {
  it('관리자 역할 첫 페이지(100명)를 한 번 읽는다', async () => {
    apiFetch.mockResolvedValueOnce(page([member('101', '김*수')]));
    const directory = await loadAdminDirectory();
    expect(apiFetch).toHaveBeenCalledTimes(1);
    expect(apiFetch).toHaveBeenCalledWith(
      '/api/v1/admin/members?role=ADMIN&page=0&size=100',
      expect.anything(),
    );
    expect(ADMIN_DIRECTORY_PATH).toBe('/api/v1/admin/members?role=ADMIN&page=0&size=100');
    expect(directory?.byId.get('101')).toEqual({
      memberId: '101',
      name: '김*수',
      email: '101***@e***.com',
    });
    expect(directory?.complete).toBe(true);
  });

  it('100명을 넘으면 다 담기지 않았다고 표시한다', () => {
    expect(toAdminDirectory(page([member('1', 'a')], 101)).complete).toBe(false);
  });

  it('못 읽으면 null 이다 — 감사 로그 화면을 막지 않는다', async () => {
    apiFetch.mockRejectedValueOnce(new ApiError('forbidden', 403, 'SECURITY_005', null));
    await expect(loadAdminDirectory()).resolves.toBeNull();
    apiFetch.mockRejectedValueOnce(new ApiError('network', null, null, null));
    await expect(loadAdminDirectory()).resolves.toBeNull();
  });

  it('취소되면 그대로 던진다', async () => {
    const controller = new AbortController();
    controller.abort();
    const reason = new DOMException('aborted', 'AbortError');
    apiFetch.mockRejectedValueOnce(reason);
    await expect(loadAdminDirectory(controller.signal)).rejects.toBe(reason);
  });

  it('고르기 이름에는 마스킹한 이메일을 같이 보인다(마스킹한 이름이 겹칠 수 있다)', () => {
    expect(adminOptionLabel(ADMIN_A)).toBe('김*수 (ad***@e***.com)');
  });
});
