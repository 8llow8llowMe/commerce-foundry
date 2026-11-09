import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  FORBIDDEN_INLINE,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
} from '@/lib/api-errors';
import type { Call } from './status-change';

/**
 * 어드민 송장 등록 · 출고(#18 PR-B1, delivery-service `ShipmentAdminController`, `backend/docs/api-design-guide.md` §2-19).
 * 단건도 1건짜리 목록으로 보낸다. 한 요청은 1~20건이고 일부가 실패해도 200 + 행별 결과(요청 순서)다.
 * 멱등 키는 없다 — 주문당 송장 1건이라 같은 본문을 다시 보내면 서버가 수렴한다(이미 반영된 건은 `ALREADY_REGISTERED`,
 * 반영 전으로 남은 건은 같은 송장 아이디로 다시 반영). 그래서 결과를 모르는 실패는 같은 택배사 · 송장 번호로 다시 보낸다.
 * 배송 상태 변경 · 송장 조회 · 고객 배송 조회는 PR-B2 라 아직 없다.
 */
export const ADMIN_DELIVERIES_PATH = '/api/v1/admin/deliveries';

/** 서버 `ShipmentRegisterRequest.MAX_ITEMS` — 게이트웨이 응답 제한(10초) 안에 끝나도록 둔 한 요청 상한 */
export const SHIPMENT_REQUEST_MAX = 20;
/** 화면 한 번에 올릴 수 있는 줄 — 20건씩 차례로 보내므로 요청 5번(최악 약 40초)까지로 둔다 */
export const SHIPMENT_ROWS_MAX = 100;
export const TRACKING_NUMBER_MAX = 50;
/** 서버 `Shipment.TRACKING_NUMBER_PATTERN` — 대소문자까지 그대로 저장한다 */
const TRACKING_NUMBER_PATTERN = /^[A-Za-z0-9-]{1,50}$/;
/** 서버 `ShipmentRegisterRequest.ORDER_ID_PATTERN` */
const ORDER_ID_PATTERN = /^[1-9][0-9]{0,18}$/;
const LONG_MAX = '9223372036854775807';

/**
 * 서버가 받는 주문 아이디인지 — 숫자 문자열이고 long 범위 안. 범위를 넘으면 서버가 요청 전체를 400 `DELIVERY_142` 로 거절해
 * 같은 요청의 다른 줄까지 막으므로 넣기 전에 거른다(같은 자릿수 문자열은 사전순 = 크기순이다).
 */
export function isOrderId(value: string): boolean {
  return ORDER_ID_PATTERN.test(value) && (value.length < 19 || value <= LONG_MAX);
}

/** 서버 `CarrierCode` — 목록에 없는 택배사는 `OTHER` */
export const CARRIERS = [
  'CJ_LOGISTICS',
  'KOREA_POST',
  'LOTTE',
  'HANJIN',
  'LOGEN',
  'EMS',
  'DHL',
  'FEDEX',
  'UPS',
  'OTHER',
] as const;
export type CarrierCode = (typeof CARRIERS)[number];

/** 서버 `CarrierCode` displayName 과 같다 */
export const CARRIER_LABELS: Record<CarrierCode, string> = {
  CJ_LOGISTICS: 'CJ대한통운',
  KOREA_POST: '우체국택배',
  LOTTE: '롯데택배',
  HANJIN: '한진택배',
  LOGEN: '로젠택배',
  EMS: 'EMS',
  DHL: 'DHL',
  FEDEX: 'FedEx',
  UPS: 'UPS',
  OTHER: '기타 택배사',
};

export type ShipmentItem = { orderId: string; carrier: CarrierCode; trackingNumber: string };

export type ShipmentResultCode = 'REGISTERED' | 'ALREADY_REGISTERED' | 'FAILED';

/** `ShipmentRegisterAdminResponse` */
export type ShipmentRegisterResponse = {
  registeredCount: number;
  alreadyRegisteredCount: number;
  failedCount: number;
  results: {
    orderId: string;
    deliveryId: string | null;
    result: ShipmentResultCode;
    errorCode: string | null;
    message: string | null;
  }[];
};

/**
 * 다시 보내면 결과가 달라질 수 있는 행별 실패(§2-19 표) — 그 사이 주문이 바뀜(017) · 주문 서비스 응답 없음(018) ·
 * 동시 등록(020) · 저장 실패(021) · 처리 마감(022). 없는 주문(014) · 출고할 수 없는 상태(015) · 출고 시각 거절(016) ·
 * 다른 송장으로 이미 출고(019)는 다시 보내도 같다.
 */
const RETRYABLE_CODES = new Set([
  'DELIVERY_017',
  'DELIVERY_018',
  'DELIVERY_020',
  'DELIVERY_021',
  'DELIVERY_022',
]);

export const CARRIER_REQUIRED = '택배사를 골라 주세요.';
export const TRACKING_NUMBER_INVALID = `송장 번호는 영문 · 숫자 · 하이픈으로 ${TRACKING_NUMBER_MAX}자 이하로 입력해 주세요.`;

/** 송장 번호 입력 → 본문 값. 앞뒤 공백만 뺀다(대소문자 · 하이픈은 그대로 — 서버가 그대로 저장한다) */
export function normalizeTrackingNumber(text: string): string | null {
  const value = text.trim();
  return TRACKING_NUMBER_PATTERN.test(value) ? value : null;
}

export function isCarrier(value: string): value is CarrierCode {
  return CARRIERS.some((carrier) => carrier === value);
}

/** 붙여넣기 · 입력 칸의 택배사 — 코드(`CJ_LOGISTICS`, 대소문자 무관) 또는 화면 이름(`CJ대한통운`, 공백 무관) */
export function carrierOf(text: string): CarrierCode | null {
  const value = text.trim();
  const upper = value.toUpperCase();
  const byCode = CARRIERS.find((carrier) => carrier === upper);
  if (byCode) return byCode;
  const compact = value.replace(/\s+/g, '').toLowerCase();
  return (
    CARRIERS.find(
      (carrier) => CARRIER_LABELS[carrier].replace(/\s+/g, '').toLowerCase() === compact,
    ) ?? null
  );
}

/** 화면의 송장 한 줄(입력 중). `label` 은 결과에 보일 주문 이름 — 붙여넣은 줄은 주문 아이디다 */
export type ShipmentDraft = {
  orderId: string;
  label: string;
  carrier: CarrierCode | '';
  trackingText: string;
};

export type DraftErrors = Record<string, { carrier?: string; trackingNumber?: string }>;

/** 보내기 전 검사 — 서버 400(`DELIVERY_144` · `145`)과 같은 규칙을 칸마다 먼저 알린다. 문제없으면 본문 */
export function validateDrafts(
  drafts: ShipmentDraft[],
): { ok: true; items: ShipmentItem[] } | { ok: false; errors: DraftErrors } {
  const errors: DraftErrors = {};
  const items: ShipmentItem[] = [];
  for (const draft of drafts) {
    const trackingNumber = normalizeTrackingNumber(draft.trackingText);
    const rowErrors: DraftErrors[string] = {};
    if (draft.carrier === '') rowErrors.carrier = CARRIER_REQUIRED;
    if (trackingNumber === null) rowErrors.trackingNumber = TRACKING_NUMBER_INVALID;
    if (draft.carrier === '' || trackingNumber === null) {
      errors[draft.orderId] = rowErrors;
      continue;
    }
    items.push({ orderId: draft.orderId, carrier: draft.carrier, trackingNumber });
  }
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, items };
}

export type PasteResult = {
  drafts: ShipmentDraft[];
  /** 줄 번호(1부터) + 이유 — 읽지 못한 줄은 넣지 않는다 */
  skipped: { line: number; reason: string }[];
};

/**
 * 엑셀에서 복사한 표(탭 구분) · CSV 한 줄씩 → 송장 줄. 열은 주문 아이디 · 택배사 · 송장 번호 순서다.
 * 첫 칸이 주문 아이디가 아닌 첫 줄은 머리글로 보고 건너뛴다. 빈 줄은 무시한다.
 * 같은 주문이 두 번 나오면 뒤 줄을 뺀다(서버 `DELIVERY_143` — 한 요청에 같은 주문은 한 번).
 * 택배사를 읽지 못한 줄은 택배사를 비워 넣는다 — 화면에서 고르면 된다. 송장 번호 형식은 보내기 전 검사가 칸에 알린다.
 */
export function parseShipmentPaste(text: string, existing: ReadonlySet<string>): PasteResult {
  const drafts: ShipmentDraft[] = [];
  const skipped: PasteResult['skipped'] = [];
  const seen = new Set(existing);
  const lines = text.split(/\r?\n/);
  lines.forEach((raw, index) => {
    const line = index + 1;
    if (raw.trim() === '') return;
    const cells = (raw.includes('\t') ? raw.split('\t') : raw.split(',')).map((cell) =>
      cell
        .trim()
        .replace(/^"(.*)"$/, '$1')
        .trim(),
    );
    const [orderId = '', carrierText = '', trackingText = ''] = cells;
    if (!isOrderId(orderId)) {
      // 머리글(첫 내용 줄)은 조용히 건너뛴다
      if (drafts.length === 0 && skipped.length === 0 && !/\d/.test(orderId)) return;
      if (ORDER_ID_PATTERN.test(orderId)) {
        skipped.push({ line, reason: '없는 주문 아이디입니다(범위를 넘는 숫자).' });
        return;
      }
      skipped.push({ line, reason: '주문 아이디(숫자)를 읽지 못했습니다.' });
      return;
    }
    if (seen.has(orderId)) {
      skipped.push({ line, reason: '이미 넣은 주문입니다.' });
      return;
    }
    seen.add(orderId);
    drafts.push({
      orderId,
      label: orderId,
      carrier: carrierOf(carrierText) ?? '',
      trackingText,
    });
  });
  return { drafts, skipped };
}

/** 줄마다의 결과. `UNKNOWN` 은 요청이 결과를 모르게 끝남(502 · 504 · 연결 끊김), `NOT_SENT` 는 서버가 처리하지 않음 */
export type ShipmentRowResult =
  'REGISTERED' | 'ALREADY_REGISTERED' | 'FAILED' | 'UNKNOWN' | 'NOT_SENT';

export type ShipmentRow = {
  item: ShipmentItem;
  result: ShipmentRowResult;
  errorCode: string | null;
  message: string | null;
};

export type ShipmentRun = {
  rows: ShipmentRow[];
  /** 403 — 화면이 권한 안내를 그린다 */
  denied: AccessDenied | null;
  /**
   * 저장 중 401(세션 만료). 화면은 입력을 그대로 두고 `SESSION_EXPIRED_ON_SAVE` 를 보이며 **다시 읽지 않는다** —
   * 다시 읽기도 401 이라 `AccessNotice` 가 로그인 화면으로 보내 입력이 사라진다(`architecture.md` §9).
   */
  sessionExpired: boolean;
};

export const SHIPMENT_UNKNOWN =
  '등록 결과를 확인하지 못했습니다. 같은 송장으로 다시 보내면 이미 반영된 주문은 그대로 둡니다.';
export const SHIPMENT_NOT_SENT_AFTER_FAILURE = '앞 요청이 실패해 보내지 않았습니다.';

/** 요청 전체의 실패 → 그 요청 줄들의 결과. 행별 실패는 200 응답의 `FAILED` 로 온다 */
function requestFailure(cause: unknown): {
  result: 'UNKNOWN' | 'NOT_SENT';
  message: string;
  errorCode: string | null;
  denied: AccessDenied | null;
  sessionExpired: boolean;
} {
  const base = { errorCode: null, denied: null, sessionExpired: false };
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { ...base, result: 'NOT_SENT', message: SESSION_EXPIRED_ON_SAVE, sessionExpired: true };
  if (denied) return { ...base, result: 'NOT_SENT', message: FORBIDDEN_INLINE, denied };
  if (!(cause instanceof ApiError))
    return { ...base, result: 'UNKNOWN', message: SHIPMENT_UNKNOWN };
  if (cause.kind === 'unknownOutcome' || cause.kind === 'network')
    return { ...base, result: 'UNKNOWN', message: SHIPMENT_UNKNOWN };
  // 400(본문 오류) · 429 등 코드가 있는 4xx — 서버가 이 요청을 처리하지 않았다
  if (cause.status !== null && cause.status < 500 && cause.code)
    return { ...base, result: 'NOT_SENT', message: cause.message, errorCode: cause.code };
  // 503 은 게이트웨이 · BFF 가 서비스에 닿기 전에 낸다(`GATEWAY_*` · `BFF_002`) — delivery 는 행별 실패로만 503 성격을 낸다.
  // 다시 보내도 되는 것은 결과 모름과 같으므로 문구만 "보내지 않음" 이다
  if (cause.kind === 'unavailable' || cause.kind === 'rateLimited')
    return { ...base, result: 'NOT_SENT', message: cause.message, errorCode: cause.code };
  // 500 · 봉투 아닌 응답 — 처리됐는지 모른다
  return { ...base, result: 'UNKNOWN', message: SHIPMENT_UNKNOWN };
}

/**
 * 송장 등록 — 20건씩 차례로 보낸다(자동 재시도 없음). 요청 하나가 통째로 실패하면 거기서 멈추고
 * 그 요청 줄은 `UNKNOWN` / `NOT_SENT`, 뒤 줄은 `NOT_SENT` 로 둔다 — 결과를 모르는 채 다음 요청을 이어 보내지 않는다.
 * 결과는 보낸 순서 그대로다.
 */
export async function runShipmentRegistration(
  call: Call,
  items: ShipmentItem[],
): Promise<ShipmentRun> {
  const rows: ShipmentRow[] = [];
  for (let start = 0; start < items.length; start += SHIPMENT_REQUEST_MAX) {
    const chunk = items.slice(start, start + SHIPMENT_REQUEST_MAX);
    try {
      const response = await call<ShipmentRegisterResponse>(ADMIN_DELIVERIES_PATH, {
        method: 'POST',
        json: { items: chunk },
      });
      rows.push(...rowsOf(chunk, response));
    } catch (cause) {
      const failure = requestFailure(cause);
      rows.push(
        ...chunk.map((item) => ({
          item,
          result: failure.result,
          errorCode: failure.errorCode,
          message: failure.message,
        })),
        ...items.slice(start + SHIPMENT_REQUEST_MAX).map((item) => ({
          item,
          result: 'NOT_SENT' as const,
          errorCode: null,
          message: SHIPMENT_NOT_SENT_AFTER_FAILURE,
        })),
      );
      return { rows, denied: failure.denied, sessionExpired: failure.sessionExpired };
    }
  }
  return { rows, denied: null, sessionExpired: false };
}

/** 응답 결과를 보낸 줄과 잇는다 — 주문 아이디로 찾고, 빠진 줄은 결과 모름으로 둔다(서버는 요청 순서대로 모두 돌려준다) */
function rowsOf(chunk: ShipmentItem[], response: ShipmentRegisterResponse): ShipmentRow[] {
  const byOrder = new Map(response.results.map((row) => [row.orderId, row]));
  return chunk.map((item) => {
    const row = byOrder.get(item.orderId);
    if (!row) return { item, result: 'UNKNOWN', errorCode: null, message: SHIPMENT_UNKNOWN };
    return {
      item,
      result: row.result,
      errorCode: row.errorCode,
      message: row.result === 'FAILED' ? (row.message ?? SAVE_FAILED) : null,
    };
  });
}

export function isShipped(row: ShipmentRow): boolean {
  return row.result === 'REGISTERED' || row.result === 'ALREADY_REGISTERED';
}

/** 다시 보낼 만한 줄 — 결과 모름 · 보내지 않음 · 다시 보내면 달라질 수 있는 행별 실패 */
export function isRetryable(row: ShipmentRow): boolean {
  if (row.result === 'UNKNOWN' || row.result === 'NOT_SENT') return true;
  return row.result === 'FAILED' && row.errorCode !== null && RETRYABLE_CODES.has(row.errorCode);
}

/** 결과 요약 한 줄. 일부라도 실패하면 성공처럼 말하지 않는다 */
export function shipmentSummary(rows: ShipmentRow[]): string {
  const registered = rows.filter((row) => row.result === 'REGISTERED').length;
  const already = rows.filter((row) => row.result === 'ALREADY_REGISTERED').length;
  const unknown = rows.filter((row) => row.result === 'UNKNOWN').length;
  const notSent = rows.filter((row) => row.result === 'NOT_SENT').length;
  const failed = rows.filter((row) => row.result === 'FAILED').length;
  const parts = [`등록 ${registered}건`];
  if (already > 0) parts.push(`이미 같은 송장 ${already}건`);
  parts.push(`실패 ${failed}건`);
  if (unknown > 0) parts.push(`결과 모름 ${unknown}건`);
  if (notSent > 0) parts.push(`보내지 않음 ${notSent}건`);
  const counts = parts.join(' · ');
  if (registered + already === rows.length) return `모두 출고했습니다 — ${counts}`;
  return registered + already === 0
    ? `출고하지 못했습니다 — ${counts}`
    : `일부 주문을 출고하지 못했습니다 — ${counts}`;
}

const SHIPPING = 'SHIPPING';

/**
 * 주문 상세의 단건 등록 뒤 안내 — 등록 결과와 **다시 읽은 주문 상태**를 함께 본다(출고 반영은 서버 재조회로 확인한다).
 * 결과를 모르는 실패여도 다시 읽은 주문이 배송 중이면 반영된 것이다.
 */
export function shipmentNotice(
  row: ShipmentRow,
  fresh: { code: string; name: string },
): { tone: 'ok' | 'warn'; message: string } {
  const label = `${CARRIER_LABELS[row.item.carrier]} ${row.item.trackingNumber}`;
  if (isShipped(row)) {
    if (fresh.code !== SHIPPING)
      return {
        tone: 'warn',
        message: `송장(${label})은 등록됐지만 다시 불러온 주문이 ${fresh.name} 상태입니다. 주문 이력을 확인해 주세요.`,
      };
    return {
      tone: 'ok',
      message:
        row.result === 'ALREADY_REGISTERED'
          ? `같은 송장(${label})으로 이미 출고된 주문입니다.`
          : `송장(${label})을 등록해 배송 중으로 바꿨습니다.`,
    };
  }
  if (row.result === 'UNKNOWN' && fresh.code === SHIPPING)
    return {
      tone: 'ok',
      // 다른 관리자가 다른 송장으로 출고했을 수도 있어 "이 송장으로" 라고 단정하지 않는다(송장 조회는 BE #18 PR-B2)
      message: `등록 응답은 받지 못했지만 다시 불러온 주문이 배송 중입니다. 어느 송장으로 출고됐는지는 같은 송장(${label})으로 다시 보내 보면 압니다 — 같으면 "이미 같은 송장", 다르면 "다른 송장으로 이미 출고" 입니다.`,
    };
  return { tone: 'warn', message: row.message ?? SAVE_FAILED };
}

/**
 * 목록에서 고른 주문을 등록 표에 더한다. 상품 준비중이 아닌 주문은 출고할 수 없어(`DELIVERY_015`) 빼고 그 수를 알린다.
 * 이미 표에 있는 주문은 다시 넣지 않는다. 택배사는 `carrier`(표 위에서 고른 기본 택배사)로 채운다.
 */
export function addSelectedOrders(
  drafts: ShipmentDraft[],
  selected: { orderId: string; orderName: string; status: { code: string } }[],
  carrier: CarrierCode | '',
): { drafts: ShipmentDraft[]; notPreparing: number } {
  const existing = new Set(drafts.map((draft) => draft.orderId));
  const added = selected
    .filter((order) => order.status.code === 'PREPARING' && !existing.has(order.orderId))
    .map((order) => ({
      orderId: order.orderId,
      label: order.orderName,
      carrier,
      trackingText: '',
    }));
  return {
    drafts: [...drafts, ...added],
    notPreparing: selected.filter((order) => order.status.code !== 'PREPARING').length,
  };
}

/** 보낸 뒤 등록 표에 남길 줄 — 다시 보낼 만한 줄만, 입력값 그대로(같은 송장으로 다시 보낸다) */
export function remainingDrafts(drafts: ShipmentDraft[], rows: ShipmentRow[]): ShipmentDraft[] {
  const keep = new Set(rows.filter(isRetryable).map((row) => row.item.orderId));
  const sent = new Set(rows.map((row) => row.item.orderId));
  // 보내지 않은 줄(검사 뒤 표가 바뀐 경우)은 그대로 둔다
  return drafts.filter((draft) => keep.has(draft.orderId) || !sent.has(draft.orderId));
}

/** 단건 등록 뒤 상세를 다시 읽지 못했을 때 — 등록 결과는 그대로 알린다(성공을 실패처럼 보이면 다른 송장으로 다시 보내 `DELIVERY_019` 가 난다) */
export function shipmentNoticeWithoutReload(
  row: ShipmentRow,
  loadFailed: string,
): {
  tone: 'ok' | 'warn';
  message: string;
} {
  if (isShipped(row))
    return {
      tone: 'warn',
      message: `송장(${CARRIER_LABELS[row.item.carrier]} ${row.item.trackingNumber})을 등록했습니다. 최신 상세는 ${loadFailed}`,
    };
  return { tone: 'warn', message: `${row.message ?? SAVE_FAILED} ${loadFailed}` };
}
