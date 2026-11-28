import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import type { ClaimDetail } from './types';

/**
 * 결제 후 취소 · 승인된 반품의 "다시 시도"(#30 · BE #17 A2b · B2b, `POST /api/v1/admin/claims/{claimId}/retries`, ORDER_MANAGE).
 * 본문은 상세의 `recovery.version` 을 `expectedVersion` 으로 보낸다 — 멱등 키는 계약에 없고, 버전이 겹친 요청 · 동시 변경을 409 `ORDER_037` 로 막는다.
 * 200 이어도 결제사가 다시 거절했을 수 있어 응답(현재 상세)의 상태 · 처리 상황을 그대로 다시 그린다.
 */

/**
 * 결제 취소 키 유효 기간 — BE `ClaimAdminRecoveryProcessor.REFUND_KEY_VALIDITY`(14일, 지나면 payment `PAYMENT_018`).
 * 기준 시각은 유형마다 다르다 — 결제 후 취소는 접수 시각, 반품은 **승인 시각**이다(api-design-guide §2-16 "반품 수습 (#17 B2b)").
 */
export const REFUND_KEY_VALIDITY_MS = 14 * 24 * 60 * 60 * 1000;

/**
 * 이 처리 상황에서만 결제 취소 기한을 보인다 — 재고 복원 단계는 기한이 없고, 거절 · 자동 처리 중에는 운영자가 할 일이 아니다.
 * 반품의 `REFUND_WAITING`(같은 결제의 다른 환불에 막힘)은 결제사 거절이 아니라 다시 시도가 열린 환불 단계라 기한을 보인다.
 */
const REFUND_DEADLINE_SITUATIONS = new Set(['REFUND_UNCONFIRMED', 'REFUND_WAITING']);

export type RefundDeadline = {
  at: Date;
  remaining: string | null;
  /** 기한의 기준 — 화면에 "(접수 + 14일)" · "(승인 + 14일)" 로 보인다 */
  basis: '접수' | '승인';
};

/** 결제 취소 키 기한의 기준 시각(UTC ISO). 반품은 승인 기록이 없으면 null 이다 */
function deadlineBasisOf(
  claim: ClaimDetail,
): { at: string; basis: RefundDeadline['basis'] } | null {
  if (claim.type.code === 'CANCELLATION') return { at: claim.requestedAt, basis: '접수' };
  if (claim.type.code === 'RETURN' && claim.returnApproval)
    return { at: claim.returnApproval.approvedAt, basis: '승인' };
  return null;
}

/**
 * 결제 취소 기한(참고). 정본 판정은 서버 `retryable` 이다 — 화면은 남은 시간만 알려 준다.
 * `remaining` 이 null 이면 이미 지났다. 일 단위, 하루 미만이면 시간 단위(1시간 미만은 "1시간 미만")로 보인다.
 */
export function refundDeadlineOf(claim: ClaimDetail, now: number): RefundDeadline | null {
  if (!REFUND_DEADLINE_SITUATIONS.has(claim.recovery.situation.code)) return null;
  const basis = deadlineBasisOf(claim);
  if (!basis) return null;
  const start = Date.parse(basis.at);
  if (Number.isNaN(start)) return null;
  const at = start + REFUND_KEY_VALIDITY_MS;
  return { at: new Date(at), remaining: remainingLabel(at - now), basis: basis.basis };
}

const HOUR = 60 * 60 * 1000;
const DAY = 24 * HOUR;

function remainingLabel(ms: number): string | null {
  if (ms <= 0) return null;
  if (ms >= DAY) {
    const days = Math.floor(ms / DAY);
    const hours = Math.floor((ms % DAY) / HOUR);
    return hours > 0 ? `${days}일 ${hours}시간 남음` : `${days}일 남음`;
  }
  if (ms >= HOUR) return `${Math.floor(ms / HOUR)}시간 남음`;
  return '1시간 미만 남음';
}

export const RETRY_SENT =
  '다시 보냈습니다. 결제사가 다시 거절했을 수 있으니 아래 처리 상황을 확인해 주세요.';
export const RETRY_FAILED = '다시 시도하지 못했습니다. 처리 상황을 다시 불러왔습니다.';
export const RETRY_UNKNOWN = '다시 시도 결과를 확인하지 못했습니다. 처리 상황을 다시 불러왔습니다.';
/** 다시 시도는 ORDER_MANAGE — STAFF 에게는 버튼 대신 이 안내를 둔다 */
export const RETRY_MANAGER_ONLY =
  '다시 시도는 주문 관리 권한이 있는 운영자(대표 관리자 · 매니저)만 할 수 있습니다. 필요하면 권한이 있는 운영자에게 요청해 주세요.';

/** 서버 문구를 그 자리에 두는 거절. 모두 서버 상태가 화면과 다를 수 있어 상세를 다시 읽는다 */
const RELOAD_CODES = new Set([
  /** 버전 불일치 · 동시 변경 */
  'ORDER_037',
  /** 다시 시도할 수 없는 접수 — 결제사 거절 환불 · 14일 기한 · 이미 끝난 접수 */
  'ORDER_038',
  /** 자동 처리 중 */
  'ORDER_039',
  /** 결제 취소 결과 미반영 — 환불 실패 뜻이 아니다. 접수 상태를 다시 확인한다 */
  'ORDER_036',
  /** 없는 접수 — 다시 읽으면 "없는 클레임" 안내로 바뀐다 */
  'ORDER_032',
]);

export type RetryFailure =
  /** 401 · 403 — `AccessNotice` 로 안내한다(401 은 로그인 화면으로 보낸다. 입력이 없어 잃을 것이 없다) */
  | { kind: 'access'; denied: AccessDenied }
  /** 그 자리에 붙이는 문구. `reload` 면 상세를 다시 읽는다 */
  | { kind: 'inline'; message: string; reload: boolean };

/** 다시 시도 실패 → 화면 처리. 결과 모름(502 · 504)과 알 수 없는 실패는 상세를 다시 읽어 실제 상태를 보인다 */
export function retryFailure(cause: unknown): RetryFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'inline', message: RETRY_FAILED, reload: true };
  if (cause.kind === 'unknownOutcome')
    return { kind: 'inline', message: RETRY_UNKNOWN, reload: true };
  if (cause.code && RELOAD_CODES.has(cause.code))
    return { kind: 'inline', message: cause.message, reload: true };
  /** 자동 처리 꺼짐 503 `ORDER_040` — 시스템 설정 문제라 다시 읽어도 같다 */
  if (cause.code === 'ORDER_040') return { kind: 'inline', message: cause.message, reload: false };
  if (cause.status !== null && cause.status >= 400 && cause.status < 500 && cause.code)
    return { kind: 'inline', message: cause.message, reload: true };
  return { kind: 'inline', message: RETRY_FAILED, reload: true };
}
