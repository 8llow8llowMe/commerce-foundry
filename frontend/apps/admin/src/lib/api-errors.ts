import { ApiError } from '@cf/api-client';

/**
 * 어드민 화면 공통 오류 처리. 401 · 403 은 오류가 아니라 상태다 — 화면은 `AccessNotice` 를 그리고,
 * 401(세션 끝)이면 `AccessNotice` 가 로그인 화면으로 보낸다(#204).
 */
export type AccessDenied = 'unauthorized' | 'forbidden';

/** 칸 이름 → 오류 문구. 키는 `remoteAreas.0.postalCodeFrom` 처럼 점으로 잇는다(`fieldKey`), 폼 전체는 `form` */
export type FormErrors = Record<string, string[]>;

/** 401 · 403 이면 그 종류, 아니면 null */
export function accessDeniedOf(cause: unknown): AccessDenied | null {
  if (!(cause instanceof ApiError)) return null;
  return cause.kind === 'unauthorized' || cause.kind === 'forbidden' ? cause.kind : null;
}

export const LOAD_FAILED = '불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const SAVE_FAILED = '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.';
/**
 * 저장(쓰기) 중 401. 세션은 refresh 없이 access 수명(30분)이 전부라 폼을 오래 쓰면 생긴다(#204).
 * 읽기 401 처럼 로그인 화면으로 보내면 입력이 사라지므로 폼을 그대로 두고 이 문구를 폼 전체 오류로 둔다.
 * 401 은 서버가 처리하지 않은 요청이라 다시 로그인한 뒤 같은 폼에서 다시 저장하면 된다.
 */
export const SESSION_EXPIRED_ON_SAVE =
  '로그인이 만료되어 저장하지 못했습니다. 입력한 값은 그대로 두었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 저장해 주세요.';

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
