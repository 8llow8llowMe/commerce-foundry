import { ApiError } from '@cf/api-client';
import { accessDeniedOf } from '@/lib/api-errors';
import {
  adminClaimCompletionPath,
  adminClaimRefundConfirmationPath,
  type ClaimDetail,
} from './types';

/**
 * 결제 후 취소의 운영자 결정(#30 · BE #17 A3 · PR #696) — 수동 환불 확정 · 재고 복원 없이 종결.
 * 둘 다 **결제사(PG)는 부르지 않는다.** 확정은 커밋 뒤 주문 취소 완료와 재고 복원(원격)으로 이어지고, 종결만 원격 호출이 없다. 본문은 `{expectedVersion, reason}` 이고
 * 멱등 키는 계약에 없다 — 같은 화면 버전으로 두 번 보내면 두 번째는 409 `ORDER_037` 이라 겹쳐도 한 번만 반영된다.
 * 할 수 있는지의 정본은 서버 플래그(`refundConfirmable` · `completable`)와 권한(`SETTLEMENT_MANAGE`, 대표만)이다.
 */
export type DecisionKind = 'refundConfirmation' | 'completion';

/** 서버 `ClaimDecisionRequest.reason` `@Size(max = 200)` — Java `String#length` 와 같은 UTF-16 길이다 */
export const DECISION_REASON_MAX = 200;

export const DECISION_LABEL: Record<DecisionKind, string> = {
  refundConfirmation: '환불 완료로 확정',
  completion: '재고 복원 없이 종결',
};

export const decisionPath = (kind: DecisionKind, claimId: string) =>
  kind === 'refundConfirmation'
    ? adminClaimRefundConfirmationPath(claimId)
    : adminClaimCompletionPath(claimId);

/**
 * 지금 보일 결정 버튼. 결제 후 취소 접수이고 서버 플래그가 true 일 때만이다.
 * `canSettle` 이 false(대표가 아님)면 하나도 보이지 않는다. 역할을 모르면(null) 숨기지 않는다 — 서버 403 안내에 맡긴다.
 */
export function decisionActions(claim: ClaimDetail, canSettle: boolean | null): DecisionKind[] {
  if (claim.type.code !== 'CANCELLATION' || canSettle === false) return [];
  const actions: DecisionKind[] = [];
  if (claim.recovery.refundConfirmable) actions.push('refundConfirmation');
  if (claim.recovery.completable) actions.push('completion');
  return actions;
}

/**
 * 서버는 할 수 있다고 보지만 이 운영자에게는 권한이 없는가 — 버튼 대신 "대표 관리자만" 안내를 둔다.
 * 역할을 모르면(null) 버튼을 그대로 보이므로 false 다.
 */
export function decisionNeedsOwner(claim: ClaimDetail, canSettle: boolean | null): boolean {
  return (
    canSettle === false &&
    claim.type.code === 'CANCELLATION' &&
    (claim.recovery.refundConfirmable || claim.recovery.completable)
  );
}

export const DECISION_OWNER_ONLY =
  '환불 완료 확정 · 재고 복원 없이 종결은 대표 관리자만 할 수 있습니다. 필요하면 대표 관리자에게 요청해 주세요.';

/** 400 `ORDER_170` 과 같은 뜻 — 브라우저 검사는 미리 알려 줄 뿐이고 정본은 서버다 */
export const REASON_REQUIRED = '처리 사유를 적어 주세요.';
export const REASON_TOO_LONG = `처리 사유는 ${DECISION_REASON_MAX}자 이하로 적어 주세요.`;
export const REASON_INVALID_CHARACTER =
  '처리 사유에는 줄바꿈 · 탭 같은 제어 문자나 보이지 않는 서식 문자를 쓸 수 없습니다.';

/** 서버 `ClaimDecisionRequest` 의 금지 문자(Cc · Cs · Cf · Zl · Zp) — 400 `ORDER_151` */
const FORBIDDEN_CHARACTER = /[\p{Cc}\p{Cs}\p{Cf}\p{Zl}\p{Zp}]/u;

/**
 * 보낼 사유. 앞뒤 공백을 뺀다 — JS `trim` 은 전각 공백(U+3000) · 줄 구분자처럼 서버가 "비었음"(`ORDER_170`)으로 보는 유니코드 공백을
 * 모두 빼므로 공백만인 사유는 여기서 빈 값이 된다(NBSP 처럼 서버가 받는 문자도 빼지만, 그 문자만인 사유는 근거가 아니라 막아도 된다).
 */
export const normalizeDecisionReason = (reason: string) => reason.trim();

/** 보내기 전 확인. 문제가 없으면 null — 서버가 다시 검사한다 */
export function decisionReasonError(reason: string): string | null {
  const value = normalizeDecisionReason(reason);
  if (value === '') return REASON_REQUIRED;
  if (value.length > DECISION_REASON_MAX) return REASON_TOO_LONG;
  if (FORBIDDEN_CHARACTER.test(value)) return REASON_INVALID_CHARACTER;
  return null;
}

export const DECISION_DONE: Record<DecisionKind, string> = {
  refundConfirmation:
    '환불 완료로 기록했습니다. 재고 복원은 이어서 진행되니 아래 상태 · 처리 상황을 확인해 주세요.',
  completion: '재고 복원 없이 완료로 닫았습니다.',
};
export const DECISION_FAILED = '처리하지 못했습니다. 처리 상황을 다시 불러왔습니다.';
export const DECISION_UNKNOWN = '처리 결과를 확인하지 못했습니다. 처리 상황을 다시 불러왔습니다.';
/**
 * 쓰기 401 — 입력한 사유를 지키려고 로그인 화면으로 보내지 않는다(`SESSION_EXPIRED_ON_SAVE` 와 같은 규칙).
 * 401 은 서버가 처리하지 않은 요청이라 다시 로그인한 뒤 다시 눌러도 안전하다.
 */
export const DECISION_SESSION_EXPIRED =
  '로그인이 만료되어 처리하지 못했습니다. 입력한 사유는 그대로 두었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 눌러 주세요.';

/** 사유 칸에 붙이는 서버 거절 — 확인 단계를 열어 둔 채 고쳐 다시 보내게 한다 */
const REASON_CODES = new Set([
  /** 사유 없음 · 유니코드 공백만 */
  'ORDER_170',
  /** 200자 초과 */
  'ORDER_148',
  /** 제어 · 서식 문자 */
  'ORDER_151',
]);

/** 서버 문구를 그대로 두고 상세를 다시 읽는 거절 — 서버 상태가 화면과 다를 수 있다 */
const RELOAD_CODES = new Set([
  /** 버전 불일치 · 동시 변경 · 더블클릭 — 다시 읽은 상태 · 마지막 결정으로 이미 처리됐는지 본다 */
  'ORDER_037',
  /** 지금 상태에서 할 수 없음(주문 상태 · 다시 시도 기한 안 등) — 다시 읽어 다시 시도할 수 있으면 그 버튼이 보인다 */
  'ORDER_041',
  /** expectedVersion 형식 — 화면 버전이 낡았다 */
  'ORDER_169',
  /** 없는 접수 */
  'ORDER_032',
  /** 자동 처리(스케줄러) 꺼짐 503 — 시스템 설정 문제지만 확정이 반영됐는지 다시 읽어 보인다 */
  'ORDER_040',
]);

export type DecisionFailure =
  /** 403 — 공통 403 안내(`AccessNotice`). 확인 단계는 닫는다 */
  | { kind: 'access'; denied: 'forbidden' }
  /** 사유 칸 오류 — 확인 단계와 입력을 그대로 두고 칸 아래에 보인다 */
  | { kind: 'reason'; message: string }
  /** 쓰기 401 — 확인 단계와 입력을 그대로 두고 확인 단계 전체 오류로 보인다(사유가 틀린 것이 아니다) */
  | { kind: 'form'; message: string }
  /** 칸 밖에 붙이는 결과 문구. 확인 단계는 닫는다. `reload` 면 상세를 다시 읽는다 */
  | { kind: 'inline'; message: string; reload: boolean };

/** 확정 · 종결 실패 → 화면 처리. 결과 모름(502 · 504)과 알 수 없는 실패는 상세를 다시 읽어 실제 상태를 보인다 */
export function decisionFailure(cause: unknown): DecisionFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'form', message: DECISION_SESSION_EXPIRED };
  if (denied === 'forbidden') return { kind: 'access', denied };
  if (!(cause instanceof ApiError))
    return { kind: 'inline', message: DECISION_FAILED, reload: true };
  // 연결 끊김도 서버가 처리했는지 모른다 — 결과 모름과 같이 다시 읽어 보인다
  if (cause.kind === 'unknownOutcome' || cause.kind === 'network')
    return { kind: 'inline', message: DECISION_UNKNOWN, reload: true };
  if (cause.code && REASON_CODES.has(cause.code)) return { kind: 'reason', message: cause.message };
  if (cause.code && RELOAD_CODES.has(cause.code))
    return { kind: 'inline', message: cause.message, reload: true };
  if (cause.status !== null && cause.status >= 400 && cause.status < 500 && cause.code)
    return { kind: 'inline', message: cause.message, reload: true };
  return { kind: 'inline', message: DECISION_FAILED, reload: true };
}
