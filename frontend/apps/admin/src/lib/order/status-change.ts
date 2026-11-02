import { ApiError, type ApiFetchInit } from '@cf/api-client';
import {
  accessDeniedOf,
  LOAD_FAILED,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  adminOrderPath,
  adminOrderStatusPath,
  REASON_MAX,
  ADMIN_ORDER_STATUS_CHANGES_PATH,
  type BulkStatusChangeRequest,
  type BulkStatusChangeResponse,
  type OrderDetail,
  type StatusChangeRequest,
} from './types';

/**
 * 어드민 주문 상태 변경(#17 PR-1) — 단건 `PATCH .../{orderId}/status`, 일괄 `POST .../status-changes`.
 * 전이 가능 여부는 서버가 정한다. 화면은 상세의 `nextStatuses` 만 버튼으로 그리고, 거절 · 경합은 서버 응답으로 안다.
 * 상태 변경은 멱등 키가 없지만 "이미 그 상태면 200(일괄은 UNCHANGED)" 이라 같은 요청을 다시 보내도 두 번 바뀌지 않는다.
 */

export const REASON_TOO_LONG = `사유는 ${REASON_MAX}자 이하로 입력해 주세요.`;

/** 사유 입력 → 본문 값. 앞뒤 공백을 빼고 비면 null(서버 선택 칸) */
export function normalizeReason(text: string): { ok: true; reason: string | null } | { ok: false } {
  const reason = text.trim();
  if (reason.length > REASON_MAX) return { ok: false };
  return { ok: true, reason: reason === '' ? null : reason };
}

/** 그사이 다른 곳(고객 취소 · 결제 · 다른 운영자)에서 주문이 바뀌어 409 로 거절된 경우. 최신 상세를 다시 읽는다 */
const STALE_CODES = new Set(['ORDER_020', 'ORDER_023', 'ORDER_024', 'ORDER_025']);
/** 읽은 뒤 다른 곳이 먼저 커밋한 경합(`@Version`). 서버 문구가 "새로고침하라" 라서 다시 읽은 화면에는 화면 문구를 쓴다 */
const CONCURRENTLY_CHANGED = 'ORDER_025';
/** 다시 보내면 결과가 달라질 수 있는 실패 — 경합 · 예상 못 한 저장 오류. 그 밖의 거절은 다시 보내도 같다 */
const RETRYABLE_CODES = new Set([CONCURRENTLY_CHANGED, 'ORDER_026']);
/** 배송 완료 사유 없음(400) — 사유 칸 오류다 */
export const DELIVERED_REASON_REQUIRED = 'ORDER_027';

export const STATUS_STALE_NOTICE =
  '그사이 주문 상태가 바뀌어 최신 상태를 다시 불러왔습니다. 확인한 뒤 다시 진행해 주세요.';
export const STATUS_UNKNOWN_NOTICE =
  '상태 변경 결과를 확인하지 못해 최신 상태를 다시 불러왔습니다. 바뀌었는지 확인해 주세요.';

/**
 * 실행 직전 다시 읽은 상세로 보면 지금 누른 변경이 아직 유효한지(이슈 #30 "실행 직전 서버 상태를 재확인").
 * 화면에서 본 상태와 지금 상태가 다르거나, 목표가 지금의 `nextStatuses` 에 없으면 보내지 않는다.
 * 확인과 저장 사이의 경합은 서버 `@Version` 비교(409 `ORDER_025`)가 막는다.
 */
export function stillApplicable(shown: OrderDetail, fresh: OrderDetail, target: string): boolean {
  return (
    shown.status.code === fresh.status.code &&
    fresh.nextStatuses.some((status) => status.code === target)
  );
}

export type StatusChangeFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'notFound' }
  /** 거절 · 경합 — 최신 상세를 다시 읽고 서버 문구를 안내한다 */
  | { kind: 'stale'; message: string }
  /** 502 · 504 · 연결 끊김 — 바뀌었을 수 있다. 최신 상세를 다시 읽는다(자동 재시도 금지) */
  | { kind: 'unknownOutcome' }
  | { kind: 'errors'; errors: FormErrors };

/** 단건 상태 변경 실패를 화면 처리로 가른다. 칸은 사유(`reason`)와 폼 전체(`form`)뿐이다 */
export function statusChangeFailure(cause: unknown): StatusChangeFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === CONCURRENTLY_CHANGED) return { kind: 'stale', message: STATUS_STALE_NOTICE };
  if (cause.code && STALE_CODES.has(cause.code)) return { kind: 'stale', message: cause.message };
  if (cause.kind === 'notFound') return { kind: 'notFound' };
  if (cause.kind === 'unknownOutcome' || cause.kind === 'network')
    return { kind: 'unknownOutcome' };
  if (cause.code === DELIVERED_REASON_REQUIRED)
    return { kind: 'errors', errors: { reason: [cause.message] } };
  if (cause.fieldErrors.length > 0) return { kind: 'errors', errors: fieldErrorsOf(cause) };
  if (cause.status !== null && cause.status < 500 && cause.code)
    return { kind: 'errors', errors: { form: [cause.message] } };
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

/** `apiFetch` 와 같은 모양 — 테스트가 갈아 끼운다 */
export type Call = <T>(path: string, init?: ApiFetchInit) => Promise<T>;

export type StatusChangeOutcome =
  | { kind: 'changed'; order: OrderDetail }
  /** 보내지 않았거나 거절 · 경합 · 결과 모름 — 최신 상세(`fresh`, 없으면 다시 읽기)와 안내 */
  | { kind: 'stale'; notice: string; fresh: OrderDetail | null }
  | Exclude<StatusChangeFailure, { kind: 'stale' } | { kind: 'unknownOutcome' }>;

/**
 * 단건 상태 변경 한 번. 먼저 상세를 다시 읽어(`stillApplicable`) 화면에서 본 그대로일 때만 PATCH 를 보낸다.
 * 다시 읽기가 실패하면 PATCH 를 보내지 않았으므로 "결과 모름" 이 아니라 불러오기 실패다. PATCH 는 한 번만 보낸다(자동 재시도 없음).
 */
export async function runStatusChange(
  call: Call,
  shown: OrderDetail,
  target: string,
  reason: string | null,
): Promise<StatusChangeOutcome> {
  let fresh: OrderDetail;
  try {
    fresh = await call<OrderDetail>(adminOrderPath(shown.orderId));
  } catch (cause) {
    const denied = accessDeniedOf(cause);
    if (denied) return { kind: 'access', denied };
    if (cause instanceof ApiError && cause.kind === 'notFound') return { kind: 'notFound' };
    return { kind: 'errors', errors: { form: [LOAD_FAILED] } };
  }
  if (!stillApplicable(shown, fresh, target))
    return { kind: 'stale', notice: STATUS_STALE_NOTICE, fresh };
  const request: StatusChangeRequest = { status: target, reason };
  try {
    const order = await call<OrderDetail>(adminOrderStatusPath(shown.orderId), {
      method: 'PATCH',
      json: request,
    });
    return { kind: 'changed', order };
  } catch (cause) {
    const failure = statusChangeFailure(cause);
    if (failure.kind === 'stale') return { kind: 'stale', notice: failure.message, fresh: null };
    if (failure.kind === 'unknownOutcome')
      return { kind: 'stale', notice: STATUS_UNKNOWN_NOTICE, fresh: null };
    return failure;
  }
}

function fieldErrorsOf(cause: ApiError): FormErrors {
  const errors: FormErrors = {};
  for (const item of cause.fieldErrors) {
    const key = item.field === 'reason' ? 'reason' : 'form';
    errors[key] = [...(errors[key] ?? []), item.message];
  }
  return errors;
}

export const BULK_UNKNOWN_NOTICE =
  '일괄 변경 결과를 확인하지 못했습니다. 목록을 다시 불러왔으니 상태를 확인해 주세요. 같은 주문을 다시 보내도 이미 바뀐 주문은 그대로 둡니다.';

export type BulkFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'unknownOutcome' }
  | { kind: 'errors'; errors: FormErrors };

/**
 * 일괄 요청 자체의 실패(행별 실패가 아니다). 행별 실패는 200 응답의 `FAILED` 결과로 온다.
 * 400(`ORDER_149` · `150` · `153` 등)은 요청 전체가 처리되지 않았다는 뜻이라 문구를 그대로 보인다.
 */
export function bulkFailure(cause: unknown): BulkFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.kind === 'unknownOutcome' || cause.kind === 'network')
    return { kind: 'unknownOutcome' };
  if (cause.fieldErrors.length > 0) return { kind: 'errors', errors: fieldErrorsOf(cause) };
  if (cause.status !== null && cause.status < 500 && cause.code)
    return { kind: 'errors', errors: { form: [cause.message] } };
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

/** 일괄 요청 한 번(자동 재시도 없음). 행별 실패는 `done` 응답 안의 `FAILED` 다 */
export async function runBulkChange(
  call: Call,
  request: BulkStatusChangeRequest,
): Promise<{ kind: 'done'; response: BulkStatusChangeResponse } | BulkFailure> {
  try {
    const response = await call<BulkStatusChangeResponse>(ADMIN_ORDER_STATUS_CHANGES_PATH, {
      method: 'POST',
      json: request,
    });
    return { kind: 'done', response };
  } catch (cause) {
    return bulkFailure(cause);
  }
}

/**
 * 다시 보낼 만한 실패 주문만 — 경합(`ORDER_025`) · 예상 못 한 저장 오류(`ORDER_026`).
 * 지금 상태에서 할 수 없는 전이(`ORDER_020` · `023` · `024`)는 다시 보내도 같아서 뺀다. "실패만 다시 시도" 가 같은 상태 · 사유로 보낸다.
 */
export function retryableOrderIds(response: BulkStatusChangeResponse): string[] {
  return response.results
    .filter((row) => row.result === 'FAILED' && row.errorCode && RETRYABLE_CODES.has(row.errorCode))
    .map((row) => row.orderId);
}

/** 결과 요약 한 줄. 일부라도 실패하면 성공처럼 말하지 않는다 */
export function bulkSummary(response: BulkStatusChangeResponse): string {
  const parts = [`바꿈 ${response.changedCount}건`];
  if (response.unchangedCount > 0) parts.push(`이미 그 상태 ${response.unchangedCount}건`);
  parts.push(`실패 ${response.failedCount}건`);
  const counts = parts.join(' · ');
  if (response.failedCount === 0) return `모두 처리했습니다 — ${counts}`;
  return response.changedCount + response.unchangedCount === 0
    ? `바꾸지 못했습니다 — ${counts}`
    : `일부 주문을 바꾸지 못했습니다 — ${counts}`;
}
