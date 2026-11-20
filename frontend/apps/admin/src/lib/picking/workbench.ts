import { ApiError, unwrap } from '@cf/api-client';
import {
  accessDeniedOf,
  FORBIDDEN_INLINE,
  LOAD_FAILED,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
} from '@/lib/api-errors';
import type { OrderDetail } from '@/lib/order/types';
import {
  COUNT_MAX,
  pickingListPath,
  type BulkCheckResponse,
  type CheckRequest,
  type PickingBatch,
  type PickingCheck,
} from './types';

/**
 * 출고 작업대(#364)의 화면 밖 규칙. 컴포넌트는 이 함수들의 결과로만 그린다.
 * 재고 · 출고 가능 여부는 브라우저에서 정하지 않는다 — 대상 판정은 묶음 생성 응답, 성공 여부는 검수 응답이 정본이다.
 */

export const COUNT_INVALID = `0~${COUNT_MAX.toLocaleString('ko-KR')} 사이의 정수로 입력해 주세요.`;
export const NOTHING_COUNTED = '센 수량을 하나 이상 입력해 주세요.';

/** 수량 칸 → 값. 비거나 0 이면 "넣지 않음"(null) — 서버는 빠진 옵션을 부족으로 기록한다 */
export function parseCount(text: string): { ok: true; value: number | null } | { ok: false } {
  const trimmed = text.trim();
  if (trimmed === '') return { ok: true, value: null };
  if (!/^\d{1,5}$/.test(trimmed)) return { ok: false };
  const value = Number(trimmed);
  if (value > COUNT_MAX) return { ok: false };
  return { ok: true, value: value === 0 ? null : value };
}

/**
 * 주문 하나의 수량 입력(옵션 ID → 칸 글자) → 검수 본문. 칸 오류는 옵션 ID 로 돌려준다.
 * 아무것도 세지 않은 주문은 보내지 않는다 — 서버는 빈 맵도 받지만(부족) 실수로 누른 경우가 대부분이다.
 */
export function buildCheck(
  counts: Readonly<Record<string, string>>,
): { ok: true; request: CheckRequest } | { ok: false; errors: Record<string, string> } {
  const quantities: Record<string, number> = {};
  const errors: Record<string, string> = {};
  for (const [skuId, text] of Object.entries(counts)) {
    const parsed = parseCount(text);
    if (!parsed.ok) errors[skuId] = COUNT_INVALID;
    else if (parsed.value !== null) quantities[skuId] = parsed.value;
  }
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  if (Object.keys(quantities).length === 0) return { ok: false, errors: { form: NOTHING_COUNTED } };
  return { ok: true, request: { quantities } };
}

/** 이 주문의 가장 최근 검수 기록. 없으면 null */
export function latestCheckOf(batch: PickingBatch, orderId: string): PickingCheck | null {
  for (let index = batch.checks.length - 1; index >= 0; index -= 1) {
    const check = batch.checks[index];
    if (check?.orderId === orderId) return check;
  }
  return null;
}

/** 주문 검수 카드 제목의 id — 단건 검수 성공 뒤 포커스를 옮긴다 */
export const orderHeadingId = (orderId: string) => `picking-order-${orderId}`;

export type OrderCheckState = 'checked' | 'failed' | 'pending';

/**
 * 주문 줄의 상태. 성공은 서버의 `checked` 가 정본이다 — 다른 수량으로 다시 확인해 실패해도 이전 성공은 남는다(§2-20).
 * 성공 기록이 없고 마지막 기록이 실패면 실패, 기록이 없으면 미검수다.
 */
export function orderCheckState(batch: PickingBatch, orderId: string): OrderCheckState {
  const order = batch.orders.find((item) => item.orderId === orderId);
  if (order?.checked) return 'checked';
  return latestCheckOf(batch, orderId) ? 'failed' : 'pending';
}

export function checkSummary(batch: PickingBatch): Record<OrderCheckState, number> {
  const summary: Record<OrderCheckState, number> = { checked: 0, failed: 0, pending: 0 };
  for (const order of batch.orders) summary[orderCheckState(batch, order.orderId)] += 1;
  return summary;
}

/**
 * 묶음 상세에는 옵션 ID 만 있다. 대상 주문 상세(ORDER_READ)의 항목에서 "상품 · 옵션" 이름을 모은다.
 * 상세를 못 읽은 주문의 옵션은 이름 없이 ID 로 보인다(피킹은 ID 로도 할 수 있다).
 */
export function skuLabelsFrom(details: readonly OrderDetail[]): Record<string, string> {
  const labels: Record<string, string> = {};
  for (const detail of details) {
    for (const item of detail.items) {
      labels[item.skuId] ??= item.optionName
        ? `${item.productName} · ${item.optionName}`
        : item.productName;
    }
  }
  return labels;
}

/** 일괄 검수에서 저장 충돌(`DELIVERY_037`)로 끝나 상세를 다시 읽고 같은 수량으로 다시 보낼 주문 */
export function retryableOrderIds(response: BulkCheckResponse): string[] {
  return response.results
    .filter((result) => !result.successful && result.retryable)
    .map((result) => result.orderId);
}

const BATCH_NOT_FOUND = 'DELIVERY_034';
const ALL_EXCLUDED = 'DELIVERY_033';
const SNAPSHOT_UNAVAILABLE = 'DELIVERY_036';
const CHECK_CONFLICT = 'DELIVERY_037';

export const BATCH_NOT_FOUND_NOTICE =
  '출고 묶음을 찾을 수 없습니다. 주문 목록에서 다시 만들어 주세요.';
export const ALL_EXCLUDED_NOTICE =
  '고른 주문 중 출고 묶음에 담을 수 있는 주문이 없습니다. 상품 준비중인 국내 일반 주문만 담을 수 있습니다.';
export const SNAPSHOT_UNAVAILABLE_NOTICE =
  '주문 정보를 확인하지 못해 처리하지 않았습니다. 잠시 후 다시 시도해 주세요.';
export const CREATE_UNKNOWN_NOTICE =
  '출고 묶음이 만들어졌는지 확인하지 못했습니다. 같은 주문으로 다시 만들면 새 묶음이 생기니, 잠시 뒤 주문 목록을 확인한 다음 진행해 주세요.';
export const CHECK_CONFLICT_NOTICE =
  '다른 화면에서 같은 묶음을 먼저 검수해 최신 결과를 다시 불러왔습니다. 확인한 뒤 다시 검수해 주세요.';
export const CHECK_UNKNOWN_NOTICE =
  '검수 결과를 확인하지 못해 최신 결과를 다시 불러왔습니다. 같은 수량으로 다시 보내도 검수가 두 번 기록되지 않습니다.';

export type PickingFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 묶음이 없다 — 작업대 전체를 안내로 바꾼다 */
  | { kind: 'notFound' }
  /** 결과를 모르거나 그사이 바뀌었다 — 상세를 다시 읽고 안내한다(자동 재시도 금지) */
  | { kind: 'reload'; message: string }
  | { kind: 'message'; message: string };

/** 묶음 생성 실패. 생성은 멱등이 아니라(다시 보내면 새 묶음) 결과를 모르는 실패를 자동으로 다시 보내지 않는다 */
export function createFailure(cause: unknown): PickingFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'message', message: SESSION_EXPIRED_ON_SAVE };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: SAVE_FAILED };
  if (cause.code === ALL_EXCLUDED) return { kind: 'message', message: ALL_EXCLUDED_NOTICE };
  if (cause.code === SNAPSHOT_UNAVAILABLE)
    return { kind: 'message', message: SNAPSHOT_UNAVAILABLE_NOTICE };
  const rejected = rejectedMessage(cause);
  if (rejected) return { kind: 'message', message: rejected };
  if (cause.kind === 'network' || cause.kind === 'unknownOutcome' || cause.kind === 'server')
    return { kind: 'message', message: CREATE_UNKNOWN_NOTICE };
  return { kind: 'message', message: SAVE_FAILED };
}

export const RATE_LIMITED_NOTICE = '요청이 많아 처리하지 않았습니다. 잠시 후 다시 시도해 주세요.';

/**
 * 서버가 처리하지 않았다고 확실히 말한 거절의 문구. 400 은 fieldErrors 가 없으면 kind 가 `server` 라(`classifyFailure`)
 * kind 대신 상태 코드로 본다(`DELIVERY_032` · `035` · `038` 등). 429 도 처리 전 거절이다. 아니면 null
 */
function rejectedMessage(cause: ApiError): string | null {
  if (cause.status === 400 || cause.kind === 'validation') return cause.message;
  if (cause.kind === 'rateLimited') return RATE_LIMITED_NOTICE;
  return null;
}

/** 단건 · 일괄 검수 실패. 같은 주문 · 수량 재전송은 기존 기록을 재사용해서, 결과를 모르면 다시 읽고 다시 보내게 한다 */
export function checkFailure(cause: unknown): PickingFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'message', message: SESSION_EXPIRED_ON_SAVE };
  if (denied === 'forbidden') return { kind: 'message', message: FORBIDDEN_INLINE };
  if (!(cause instanceof ApiError)) return { kind: 'reload', message: CHECK_UNKNOWN_NOTICE };
  if (cause.code === BATCH_NOT_FOUND) return { kind: 'notFound' };
  if (cause.code === CHECK_CONFLICT) return { kind: 'reload', message: CHECK_CONFLICT_NOTICE };
  const rejected = rejectedMessage(cause);
  if (rejected) return { kind: 'message', message: rejected };
  return { kind: 'reload', message: CHECK_UNKNOWN_NOTICE };
}

export const DOWNLOAD_FAILED = '피킹 목록 파일을 받지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const DOWNLOAD_SESSION_EXPIRED =
  '로그인이 만료되어 파일을 받지 못했습니다. 입력한 수량은 그대로 두었습니다. 새 탭에서 다시 로그인한 뒤 다시 받아 주세요.';

/**
 * 피킹 리스트 다운로드 실패. 검수 수량을 입력 중일 수 있어 401 도 화면을 바꾸지 않고 문구로 안내한다(`SESSION_EXPIRED_ON_SAVE` 와 같은 원칙).
 * 없는 묶음 · 403 은 작업대 전체 안내로 바꾼다.
 */
export function downloadFailure(cause: unknown): PickingFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'message', message: DOWNLOAD_SESSION_EXPIRED };
  if (denied) return { kind: 'access', denied };
  if (cause instanceof ApiError && cause.code === BATCH_NOT_FOUND) return { kind: 'notFound' };
  return { kind: 'message', message: DOWNLOAD_FAILED };
}

/** 묶음 상세를 읽지 못함. 없는 묶음과 권한은 안내 화면으로 */
export function loadFailure(cause: unknown): PickingFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (cause instanceof ApiError && (cause.code === BATCH_NOT_FOUND || cause.kind === 'notFound'))
    return { kind: 'notFound' };
  return { kind: 'message', message: LOAD_FAILED };
}

/** `Content-Disposition` 의 filename. 없거나 이상하면 묶음 ID 로 만든다 */
export function pickingListFilename(disposition: string | null, batchId: string): string {
  const match = disposition?.match(/filename="?([\w.-]+)"?/);
  return match?.[1] ?? `picking-list-${batchId}.csv`;
}

/**
 * 피킹 리스트 CSV(#739). 누를 때마다 서버가 권한을 다시 보고 파일을 만든다(링크 발급 · 보관 없음).
 * 200 CSV 는 파일로, 그 밖은 JSON 봉투로 풀어 `ApiError` 를 던진다. 파일에는 받는 분 · 연락처 · 주소가 없다.
 */
export async function fetchPickingList(
  batchId: string,
  fetchImpl: typeof fetch = fetch,
): Promise<{ blob: Blob; filename: string }> {
  const response = await fetchImpl(pickingListPath(batchId), {
    headers: { accept: 'text/csv, application/json' },
    credentials: 'same-origin',
    cache: 'no-store',
  }).catch(() => {
    throw new ApiError('network', null, null, null);
  });
  const type = response.headers.get('content-type') ?? '';
  if (response.ok && type.startsWith('text/csv')) {
    return {
      blob: await response.blob(),
      filename: pickingListFilename(response.headers.get('content-disposition'), batchId),
    };
  }
  await unwrap<unknown>(response);
  // 200 인데 CSV 도 실패 봉투도 아니면 계약 밖 응답이다
  throw new ApiError('invalidResponse', response.status, null, null);
}
