import { ApiError, type Money } from '@cf/api-client';
import type { Locale } from '@cf/ui';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { withLocalePrefix } from '@/lib/i18n/routing';

/**
 * 결제(#27 · BE #14). 결제 준비 · 승인 · 조회는 payment-service 고객 API 다 — 정본은 `backend/docs/payment-guide.md` §3-4 · §4.
 *
 * 흐름: 주문 생성 → `/checkout/payment?orderId=` 에서 결제 준비(`POST /api/v1/payments`) → 토스 결제위젯 →
 * 성공 `/checkout/payment/success`(승인 `POST /api/v1/payments/confirm`) · 실패 `/checkout/payment/fail`.
 * 금액은 서버 값만 쓴다 — 결제위젯에도 준비 응답의 금액을 그대로 넘긴다.
 */
export const PAYMENTS_PATH = '/api/v1/payments';
export const PAYMENT_CONFIRM_PATH = '/api/v1/payments/confirm';

/** 결제 화면 앱 경로(언어 접두어 없이) */
export const PAYMENT_PAGE_PATH = '/checkout/payment';
export const PAYMENT_SUCCESS_PATH = '/checkout/payment/success';
export const PAYMENT_FAIL_PATH = '/checkout/payment/fail';

type CodeName = { code: string; name: string; description: string };

/** 결제 승인 · 상태 조회 응답(`PaymentResponse`). 결제키는 내려오지 않는다 */
export type PaymentResponse = {
  paymentId: string;
  orderId: string;
  /** 결제창에 넘긴 주문번호(토스 orderId) */
  pgOrderId: string;
  status: CodeName;
  amount: Money;
  orderName: string;
  method: CodeName | null;
  easyPayProvider: string | null;
  approvedAt: string | null;
  /** FAILED 일 때만 */
  failure: { code: string; message: string } | null;
};

/** 결제 준비 응답(`PaymentPrepareResponse`) — 위 칸 + 주문의 결제 기한(UTC) */
export type PaymentPrepared = PaymentResponse & { paymentDueAt: string };

/** 결제 승인 본문 — 금액은 successUrl 로 돌아온 값이고 서버가 결제 시도 금액과 대조한다(다르면 400 `PAYMENT_008`) */
export type PaymentConfirmRequest = {
  paymentKey: string;
  pgOrderId: string;
  amount: Money;
};

const ORDER_ID = /^[1-9]\d{0,18}$/;
const PG_ORDER_ID = /^[A-Za-z0-9_-]{6,64}$/;
const AMOUNT = /^[1-9]\d{0,14}$/;
/** 결제위젯 클라이언트 키(`test_gck_` · `live_gck_`) — API 개별 연동 키 · 시크릿 키는 받지 않는다 */
const WIDGET_CLIENT_KEY = /^(test|live)_gck_[A-Za-z0-9_]+$/;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/** 주문 아이디 쿼리(양의 정수 문자열). 아니면 null */
export function readOrderId(value: string | string[] | undefined): string | null {
  const id = first(value);
  return id && ORDER_ID.test(id) ? id : null;
}

/**
 * 결제위젯 클라이언트 키. 비었거나 결제위젯 키 형식이 아니면 null — 결제위젯을 띄우지 않고 "지금은 결제를 받을 수 없음" 이다.
 * 시크릿 키를 잘못 넣어도 브라우저로 보내지 않는다(값을 로그에 남기지 않는다).
 */
export function widgetClientKey(raw: string | undefined): string | null {
  const key = raw?.trim();
  return key && WIDGET_CLIENT_KEY.test(key) ? key : null;
}

/** 결제 화면 앱 경로 */
export function paymentPageHref(orderId: string): string {
  return `${PAYMENT_PAGE_PATH}?orderId=${encodeURIComponent(orderId)}`;
}

/**
 * 결제위젯 성공 · 실패 복귀 주소(절대 주소). 지금 origin + 지금 언어의 경로 접두어 + `?order=<주문 아이디>`.
 * 토스가 이 뒤에 결과 쿼리(성공 `paymentKey` · `orderId` · `amount`, 실패 `code` · `message` · `orderId`)를 붙인다.
 * 언어 호스트 방식이면 접두어가 비고 origin 이 언어 호스트다 — `Link` 와 같은 규칙이다.
 */
export function paymentReturnUrls(
  origin: string,
  prefix: string,
  orderId: string,
): { successUrl: string; failUrl: string } {
  const query = `?order=${encodeURIComponent(orderId)}`;
  return {
    successUrl: `${origin}${withLocalePrefix(prefix, `${PAYMENT_SUCCESS_PATH}${query}`)}`,
    failUrl: `${origin}${withLocalePrefix(prefix, `${PAYMENT_FAIL_PATH}${query}`)}`,
  };
}

type Query = Record<string, string | string[] | undefined>;

/** 성공 복귀 쿼리 — 승인 본문 + 주문 아이디(있으면). 형식이 어긋나면(변조 · 잘린 주소) null 이고 승인을 보내지 않는다 */
export type SuccessReturn = { confirm: PaymentConfirmRequest; orderId: string | null };

export function readSuccessReturn(query: Query): SuccessReturn | null {
  const paymentKey = first(query.paymentKey);
  const pgOrderId = first(query.orderId);
  const amount = first(query.amount);
  if (!paymentKey || paymentKey.length > 200) return null;
  if (!pgOrderId || !PG_ORDER_ID.test(pgOrderId)) return null;
  if (!amount || !AMOUNT.test(amount)) return null;
  const value = Number(amount);
  if (!Number.isSafeInteger(value)) return null;
  return {
    // 일반결제는 KRW 만이다(USD 는 #31) — 다르면 서버가 대조해 거절한다
    confirm: { paymentKey, pgOrderId, amount: { amount: value, currency: 'KRW' } },
    orderId: readOrderId(query.order),
  };
}

/** 토스 실패 코드 — 사용자가 결제창을 닫았다 */
export const TOSS_USER_CANCELED = 'PAY_PROCESS_CANCELED';
/** 토스 SDK 오류 코드 — 결제창 띄우기 전에 사용자가 취소했다 */
export const TOSS_SDK_USER_CANCEL = 'USER_CANCEL';
const TOSS_CODE = /^[A-Z0-9_]{1,100}$/;
/** 토스 메시지를 보조로 보일 때의 최대 길이 */
const TOSS_MESSAGE_MAX = 200;

/**
 * 실패 복귀 쿼리. 코드는 형식이 맞을 때만 읽는다.
 * 쿼리의 `message` 는 읽지 않는다 — 누구나 링크로 지어낼 수 있어 우리 도메인에 임의 문구(피싱 안내)를 띄우는 통로가 된다.
 */
export type FailReturn = { code: string | null; orderId: string | null };

export function readFailReturn(query: Query): FailReturn {
  const code = first(query.code);
  return {
    code: code && TOSS_CODE.test(code) ? code : null,
    orderId: readOrderId(query.order),
  };
}

/** 토스 메시지 — 제어 문자를 지우고 자른다. 빈 값이면 null */
export function tossMessage(raw: string | undefined | null): string | null {
  const text = raw?.replace(/[\u0000-\u001f\u007f]/g, ' ').trim();
  return text ? text.slice(0, TOSS_MESSAGE_MAX) : null;
}

/** 실패 화면 문구 — 사용자 취소는 취소 문구, 그 밖은 일반 실패 문구(사전 문구만) */
export function failText(failure: FailReturn, t: Dictionary['payment']): string {
  return failure.code === TOSS_USER_CANCELED ? t.failCanceled : t.failGeneric;
}

/** 결제위젯 SDK 가 던진 오류의 코드(있으면) */
export function sdkErrorCode(cause: unknown): string | null {
  if (typeof cause !== 'object' || cause === null || !('code' in cause)) return null;
  const { code } = cause as { code: unknown };
  return typeof code === 'string' ? code : null;
}

/** 결제위젯 요청(requestPayment) 실패 문구. 사용자 취소는 실패가 아니라 안내다. SDK 메시지는 국문 스토어에서만 보조로 */
export function requestFailure(
  cause: unknown,
  t: Dictionary['payment'],
  locale: Locale,
): { canceled: boolean; message: string } {
  if (sdkErrorCode(cause) === TOSS_SDK_USER_CANCEL) return { canceled: true, message: t.canceled };
  const detail = locale === 'ko' && cause instanceof Error ? tossMessage(cause.message) : null;
  return { canceled: false, message: detail ?? t.requestFailed };
}

/** 결제 상태 코드로 화면을 가른다 */
export type PaymentOutcome = 'ready' | 'done' | 'failed' | 'pending' | 'other';

export function paymentOutcome(payment: Pick<PaymentResponse, 'status'>): PaymentOutcome {
  switch (payment.status.code) {
    case 'READY':
      return 'ready';
    case 'DONE':
      return 'done';
    case 'FAILED':
      return 'failed';
    // 승인 중 · 결과 미확정 — 상태 조회로 확인한다(§4). 다시 결제하면 이중 결제 위험이 있다
    case 'IN_PROGRESS':
    case 'UNKNOWN':
      return 'pending';
    default:
      return 'other';
  }
}

/** 같은 키로 다시 보내도 되는 결과 — 반영 여부를 모르거나 서비스에 닿지 않았다. `server` 는 5xx 일 때만이다 */
const RETRY_KINDS = new Set<ApiError['kind']>([
  'network',
  'invalidResponse',
  'unknownOutcome',
  'unavailable',
  'rateLimited',
]);
const retryable = (cause: ApiError) =>
  RETRY_KINDS.has(cause.kind) || (cause.kind === 'server' && (cause.status ?? 500) >= 500);

/**
 * 결제 준비 실패를 화면 처리로 가른다.
 * - signedOut: 401
 * - retry: 잠시 뒤 다시(버튼) — 요청 겹침 `PAYMENT_012` · 주문 정보 일시 장애 `PAYMENT_011` · 5xx · 연결 실패 · 429(쿨다운)
 * - final: 이 주문으로는 결제할 수 없다(주문 없음 · 결제 대기 아님 · 기한 임박 · 통화 · 결제 게이트 닫힘) — 장바구니로 안내
 */
export type PrepareFailure =
  { kind: 'signedOut' } | { kind: 'retry'; message: string } | { kind: 'final'; message: string };

export function prepareFailure(cause: unknown, t: Dictionary['payment']): PrepareFailure {
  if (!(cause instanceof ApiError)) return { kind: 'retry', message: t.prepareFailed };
  if (cause.kind === 'unauthorized') return { kind: 'signedOut' };
  const code = cause.code ?? '';
  if (cause.kind === 'rateLimited') {
    return { kind: 'retry', message: t.prepareErrors.GATEWAY_006 ?? t.prepareFailed };
  }
  // 게이트가 닫힘(503) — 다시 눌러도 같다
  if (code === 'PAYMENT_015') return { kind: 'final', message: t.unavailable };
  if (code === 'PAYMENT_012' || retryable(cause)) {
    return { kind: 'retry', message: t.prepareErrors[code] ?? t.prepareFailed };
  }
  return { kind: 'final', message: t.prepareErrors[code] ?? t.invalidOrder };
}

/**
 * 결제 승인 실패를 화면 처리로 가른다. 같은 `Idempotency-Key` 로 다시 확인할지가 핵심이다(`payment-guide.md` §3-4).
 * - signedOut: 401 — 로그인 뒤 같은 주소로 돌아오면 같은 키로 다시 확인한다
 * - recheck: 같은 키로 다시 확인(버튼). 결제사 연결 실패 `PAYMENT_010`(청구 없음, `retryAfterSec`) · 처리 중 `PAYMENT_007` ·
 *   기록 실패 `PAYMENT_013` · 주문 정보 일시 장애 `PAYMENT_011` · 5xx · 502/504 · 연결 실패 · 429. 자동으로 다시 보내지 않는다
 * - repay: 이미 끝난 결제 시도 `PAYMENT_006` — 결제 준비부터 다시
 * - mismatch: 금액 · 결제키 · 키 재사용 불일치(`PAYMENT_008` · `009` · `014`) — 변조 · 불일치 신호라 다시 결제를 두지 않는다
 * - final: 결제 정보 없음 · 주문 상태(`PAYMENT_001` · `002` · `003` · `004` · `012`) · 결제 게이트 닫힘 · 그 밖의 4xx
 */
export type ConfirmFailure =
  | { kind: 'signedOut' }
  | { kind: 'recheck'; message: string; retryAfterSec: number | null }
  | { kind: 'repay'; message: string }
  | { kind: 'mismatch'; message: string }
  | { kind: 'final'; message: string; cartLink: boolean };

/** `PAYMENT_010` 의 `Retry-After`(BE 고정값). 503 의 Retry-After 는 `ApiError` 에 실리지 않아 계약 값을 쓴다 */
export const PAYMENT_RETRY_AFTER_SEC = 5;
const MISMATCH_CODES = new Set(['PAYMENT_008', 'PAYMENT_009', 'PAYMENT_014']);
/** 승인 직전 주문 재확인 실패 — 청구 없음. 주문을 다시 만들어야 한다 */
const ORDER_CODES = new Set(['PAYMENT_002', 'PAYMENT_003', 'PAYMENT_004', 'PAYMENT_012']);

export function confirmFailure(cause: unknown, t: Dictionary['payment']): ConfirmFailure {
  if (!(cause instanceof ApiError)) {
    return { kind: 'recheck', message: t.confirmRetry, retryAfterSec: null };
  }
  if (cause.kind === 'unauthorized') return { kind: 'signedOut' };
  const code = cause.code ?? '';
  const text = (fallback: string) => t.confirmErrors[code] ?? fallback;
  if (code === 'PAYMENT_015') return { kind: 'final', message: t.unavailable, cartLink: false };
  if (code === 'PAYMENT_010') {
    return {
      kind: 'recheck',
      message: text(t.confirmRetry),
      retryAfterSec: PAYMENT_RETRY_AFTER_SEC,
    };
  }
  if (cause.kind === 'rateLimited') {
    return {
      kind: 'recheck',
      message: t.prepareErrors.GATEWAY_006 ?? t.confirmRetry,
      retryAfterSec: cause.retryAfterSec,
    };
  }
  if (code === 'PAYMENT_007' || retryable(cause)) {
    return { kind: 'recheck', message: text(t.confirmRetry), retryAfterSec: null };
  }
  if (code === 'PAYMENT_006') return { kind: 'repay', message: text(t.failed) };
  if (MISMATCH_CODES.has(code)) return { kind: 'mismatch', message: t.mismatch };
  if (ORDER_CODES.has(code))
    return { kind: 'final', message: text(t.invalidOrder), cartLink: true };
  return { kind: 'final', message: text(t.invalidReturn), cartLink: false };
}

/** 미확정(UNKNOWN · IN_PROGRESS) 결제의 상태 조회 간격(초) — 합 50초. 다 돌면 "다시 확인" 버튼으로 바꾼다 */
export const POLL_DELAYS_SEC = [3, 5, 8, 13, 21] as const;

/** sessionStorage 처럼 쓰는 저장소 */
export type KeyStorage = Pick<Storage, 'getItem' | 'setItem'>;

const CONFIRM_KEY_PREFIX = 'cf.pay.confirm.';
/** 저장소를 쓸 수 없을 때(사파리 개인 모드 · 차단) — 이 탭이 살아 있는 동안만 같은 키다 */
const memoryKeys = new Map<string, string>();
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 이 탭의 sessionStorage. 쓸 수 없으면 null */
export function sessionKeyStorage(): KeyStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

/**
 * 결제 승인 멱등 키 — 결제 시도(pgOrderId)마다 하나를 이 탭에 두고 다시 쓴다. 새로 고쳐도 같은 키라 서버가 처음 결과(현재 상태)를 돌려준다.
 * 저장하는 값은 키(UUID) 하나다 — 결제키는 담지 않는다. 저장소가 없거나 실패하면 메모리에 둔다.
 */
export function confirmKeyFor(
  pgOrderId: string,
  storage: KeyStorage | null,
  createKey: () => string,
): string {
  const name = `${CONFIRM_KEY_PREFIX}${pgOrderId}`;
  try {
    const saved = storage?.getItem(name);
    if (saved && UUID.test(saved)) return saved;
  } catch {
    // 읽지 못하면 메모리 값
  }
  const remembered = memoryKeys.get(name);
  if (remembered) return remembered;
  const key = createKey();
  memoryKeys.set(name, key);
  try {
    storage?.setItem(name, key);
  } catch {
    // 저장하지 못해도 이 탭에서는 메모리 값으로 같은 키다
  }
  return key;
}

/** 결제수단 표시 — 간편결제면 제공자 이름을 붙인다(서버 값 그대로) */
export function methodLabel(payment: Pick<PaymentResponse, 'method' | 'easyPayProvider'>) {
  if (!payment.method) return null;
  return payment.easyPayProvider
    ? `${payment.method.name} (${payment.easyPayProvider})`
    : payment.method.name;
}
