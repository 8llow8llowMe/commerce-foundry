import { apiFetch, type PageResponse } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import type { AuditLogItem } from './types';

export type AuditLogsLoad =
  | { kind: 'ok'; data: PageResponse<AuditLogItem> }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'failed' };

/**
 * 감사 로그 한 페이지를 읽는다. 401 · 403 은 상태(`AccessNotice`), 그 밖의 실패(연결 · 400 · 5xx)는 "다시 불러오기" 다.
 * 주소는 `parseAuditLogQuery` 를 거친 값이라 400 은 서버 계약이 바뀐 경우뿐이다 — 서버 문구(영문 파라미터 이름)는 보이지 않는다.
 * 취소(화면 이탈)는 그대로 던진다.
 */
export async function loadAuditLogs(path: string, signal?: AbortSignal): Promise<AuditLogsLoad> {
  try {
    return { kind: 'ok', data: await apiFetch<PageResponse<AuditLogItem>>(path, { signal }) };
  } catch (cause) {
    if (signal?.aborted) throw cause;
    const denied = accessDeniedOf(cause);
    return denied ? { kind: 'denied', denied } : { kind: 'failed' };
  }
}
