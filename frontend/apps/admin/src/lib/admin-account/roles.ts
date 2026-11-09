import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import type { OperatorRole } from '@/lib/auth/permissions';

/**
 * 운영자 역할 변경 · 사용 여부(#237, BE #236 #647 — `PATCH /admins/{id}/role` · `/status`).
 * 누가 어떤 역할을 줄 수 있는지 · 마지막 대표 관리자 보호의 정본은 서버다(`Member.canAssignRole` · `isLastActiveOwner`).
 * 화면은 본인 줄에만 버튼을 두지 않고(서버 `AUTH_047`), 나머지 거절은 서버 문구로 그 줄에 보인다.
 */

/** 줄 수 있는 역할 — BE `AdminRequestPatterns.ASSIGNABLE_ROLE`. 이행용 `ADMIN` 으로는 되돌리지 않는다 */
export const ASSIGNABLE_ROLES = ['OWNER', 'MANAGER', 'STAFF'] as const;
export type AssignableRole = (typeof ASSIGNABLE_ROLES)[number];

export const ROLE_LABEL: Record<OperatorRole, string> = {
  ADMIN: '관리자(기존)',
  OWNER: '대표 관리자',
  MANAGER: '매니저',
  STAFF: '스태프',
};

/** 역할 고르기에 붙이는 한 줄 설명 — 권한 표의 정본은 BE `AdminPermission.grantedTo` 다 */
export const ROLE_HINT: Record<AssignableRole, string> = {
  OWNER: '모든 메뉴(직원 관리 · 정산 포함)',
  MANAGER: '상품 · 재고 · 주문 · 배송 · CS · 리포트',
  STAFF: '상품 · 주문 · 배송 · CS',
};

export function isAssignableRole(value: unknown): value is AssignableRole {
  return (ASSIGNABLE_ROLES as readonly unknown[]).includes(value);
}

export const CHANGE_FAILED = '바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const CHANGE_UNKNOWN = '결과를 확인하지 못했습니다. 목록을 다시 불러왔습니다.';
/** 404 `AUTH_033` — 없거나 고객이거나(역할 변경이면) 꺼진 계정이다. 목록이 낡았으니 다시 읽는다 */
export const CHANGE_GONE = '찾을 수 없거나 꺼진 계정입니다. 목록을 다시 불러왔습니다.';
/**
 * 503 `AUTH_013` — BE 는 역할 · 상태를 저장(커밋)한 뒤 옛 토큰 컷오프(Redis)를 한다. 바뀐 값은 남았고 기존 로그인만 못 끊었다.
 * 같은 요청은 같은 상태라 200 이고 컷오프를 다시 하므로 한 번 더 하게 한다. 서버 문구("요청을 처리할 수 없습니다")는 이 상황과 맞지 않는다.
 */
export const CHANGED_SESSION_LIVE =
  '바꿨지만 이 계정의 기존 로그인을 아직 끊지 못했습니다. 같은 작업을 한 번 더 해 주세요.';

/** 서버 문구를 그 줄에 그대로 두는 거절 — 본인 · 대표 관리자 계정 · 줄 수 없는 역할 · 마지막 대표 관리자 */
const ROW_CODES = new Set(['AUTH_031', 'AUTH_046', 'AUTH_047', 'AUTH_048']);

export type ChangeFailure =
  | { kind: 'access'; denied: AccessDenied }
  /**
   * 줄에 붙이는 문구. `reload` 면 목록을 다시 읽는다(서버 상태가 화면과 다를 수 있다).
   * `roleSplitOff` 면 서버의 역할 나누기가 꺼져 있다(409 `AUTH_049`) — 이번 방문에서는 역할 고르기를 거둔다.
   */
  | { kind: 'row'; message: string; reload: boolean; roleSplitOff?: true };

/**
 * 역할 변경 · 사용 여부 변경 실패. 403 `AUTH_046` 은 화면 권한이 아니라 그 계정에 대한 거절이라 권한 안내로 바꾸지 않고 줄에 둔다.
 * 그 밖의 401 · 403 은 화면 상태다.
 */
export function changeFailure(cause: unknown): ChangeFailure {
  if (cause instanceof ApiError) {
    if (cause.code === 'AUTH_049') {
      return { kind: 'row', message: cause.message, reload: false, roleSplitOff: true };
    }
    if (cause.code && ROW_CODES.has(cause.code)) {
      return { kind: 'row', message: cause.message, reload: false };
    }
  }
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'row', message: CHANGE_FAILED, reload: true };
  if (cause.kind === 'unknownOutcome')
    return { kind: 'row', message: CHANGE_UNKNOWN, reload: true };
  if (cause.code === 'AUTH_033') return { kind: 'row', message: CHANGE_GONE, reload: true };
  if (cause.code === 'AUTH_013')
    return { kind: 'row', message: CHANGED_SESSION_LIVE, reload: true };
  if (cause.status !== null && cause.status >= 400 && cause.status < 500 && cause.code) {
    return { kind: 'row', message: cause.message, reload: false };
  }
  return { kind: 'row', message: CHANGE_FAILED, reload: true };
}
