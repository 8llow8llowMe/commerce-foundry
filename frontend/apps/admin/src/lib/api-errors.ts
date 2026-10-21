import { ApiError } from '@cf/api-client';

/**
 * 어드민 화면 공통 오류 처리. 어드민 로그인(#29 · BE #6) 전에는 모든 어드민 API 가 401 이다
 * — 화면은 그것을 오류가 아니라 "로그인 필요" 상태로 그린다(`AccessNotice`).
 */
export type AccessDenied = 'unauthorized' | 'forbidden';

/** 401 · 403 이면 그 종류, 아니면 null */
export function accessDeniedOf(cause: unknown): AccessDenied | null {
  if (!(cause instanceof ApiError)) return null;
  return cause.kind === 'unauthorized' || cause.kind === 'forbidden' ? cause.kind : null;
}

export const LOAD_FAILED = '불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const SAVE_FAILED = '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/**
 * 서버 fieldErrors 의 `field`(요청 본문 경로)를 폼 칸 키로.
 * `remoteAreas[0].surcharge.amount` → `remoteAreas.0.surcharge`(금액 칸은 amount · currency 를 하나로),
 * `zones[0].countryCodes[2]` → `zones.0.countryCodes`(목록 원소는 그 목록 칸으로).
 */
export function fieldKey(path: string): string {
  const parts = path
    .replace(/\[(\d+)\]/g, '.$1')
    .split('.')
    .filter((part) => part !== '');
  const last = parts.at(-1);
  if (last === 'amount' || last === 'currency') parts.pop();
  else if (last !== undefined && /^\d+$/.test(last) && parts.length > 1) {
    // 목록 원소 오류(countryCodes[2])는 목록 칸에 — 줄(remoteAreas[0]) 전체 오류도 같은 규칙으로 목록 칸에 붙는다
    parts.pop();
  }
  return parts.join('.');
}
