import { ApiError } from '@cf/api-client';
import { normalizeOptions, type LocalCartLine } from './local-cart';
import { CART_MAX_LINES, type CartItemRequest, type CartMergeResponse } from './types';

/** 병합이 끝나 회원 장바구니가 바뀌었음을 알리는 창 이벤트 — 장바구니 화면이 다시 읽는다 */
export const CART_MERGED_EVENT = 'cf:cart-merged';

/** 병합으로 보낼 줄 — 오래 담은 것부터 50줄(BE 상한). 나머지는 다음 병합에 간다 */
export function mergeBatch(lines: LocalCartLine[]): LocalCartLine[] {
  return [...lines].sort((a, b) => a.addedAt - b.addedAt).slice(0, CART_MAX_LINES);
}

/** 맞춤 제작 입력은 담을 때와 같이 정규화해 싣는다 — 제어 문자가 남은 값으로 요청 전체가 400 `CART_115` 가 되지 않게(#263) */
export function mergeRequestItems(batch: LocalCartLine[]): CartItemRequest[] {
  return batch.map((line) => {
    const item: CartItemRequest = { skuId: line.skuId, quantity: line.quantity };
    const customOptions = normalizeOptions(line.customOptions);
    if (customOptions.length > 0) item.customOptions = customOptions;
    return item;
  });
}

const ITEM_FIELD = /^items\[(\d+)\]/;

/**
 * 400 의 `fieldErrors[].field`(`items[3].customOptions[0].value` 처럼 BE 가 요청 경로로 준다)에서 거절된 항목 번호를 읽는다.
 * 모든 오류가 보낸 항목 중 하나를 가리킬 때만 번호 집합을 돌려주고, 요청 전체 오류(`items` 개수 등)가 섞이면 null 이다.
 */
export function rejectedItemIndexes(error: ApiError, sent: number): Set<number> | null {
  if (error.fieldErrors.length === 0) return null;
  const indexes = new Set<number>();
  for (const { field } of error.fieldErrors) {
    const match = field ? ITEM_FIELD.exec(field) : null;
    const index = match ? Number(match[1]) : NaN;
    if (!Number.isInteger(index) || index >= sent) return null;
    indexes.add(index);
  }
  return indexes;
}

export type MergeResult =
  | { kind: 'empty' }
  /** skipped — 서버가 건너뛴 항목(`skippedItems`)과 400 으로 빼고 다시 보낸 항목의 합 */
  | { kind: 'merged'; skipped: number }
  /** 400 — 형식이 틀린 줄이 있어 요청 전체가 거절됐고, 어느 줄인지 가릴 수 없거나 다시 보내도 거절됐다. 보낸 줄을 버렸다 */
  | { kind: 'invalid'; dropped: number }
  /** 502 · 504 — 서버가 병합했는지 모른다. 다시 보내면 두 번 더해질 수 있어 보낸 줄을 버렸다 */
  | { kind: 'unknown' }
  /** 세션이 끝났다(401) — 로컬은 그대로 둔다 */
  | { kind: 'signedOut' }
  /** 서버가 처리하지 않았다(429 · 503 · 500 · 연결 실패) — 로컬을 두고 다음 화면에서 다시 보낸다 */
  | { kind: 'retryLater' };

/**
 * 로컬 장바구니를 회원 장바구니로 한 번 보낸다. 병합은 **멱등하지 않다**(BE 에 멱등 키가 없다) —
 * 서버가 반영했을 수 있는 결과(성공 · 502/504)면 보낸 만큼 지우고, 반영하지 않은 것이 확실한 실패만 다시 보낸다.
 * 연결 실패(status 없음)는 대개 요청이 나가지 않은 경우(오프라인)라 다시 보낸다 — 드물게 두 번 더해질 수 있지만
 * 상한(99 · 1회 최대 수량)에서 잘리고, 비회원 장바구니를 잃는 것보다 낫다고 본다.
 *
 * 400(형식 오류)은 요청 전체가 거절돼 아무것도 반영되지 않았다. `fieldErrors` 가 가리키는 항목만 버리고 나머지를
 * **한 번** 다시 보낸다 — 한 줄(예: 제어 문자 `CART_115`, #263) 때문에 다른 줄까지 잃지 않게. 항목을 가릴 수 없거나
 * 다시 보낸 것도 400 이면 보낸 줄을 버린다.
 */
export async function mergeLocalCart(deps: {
  read: () => LocalCartLine[];
  remove: (batch: LocalCartLine[]) => void;
  post: (items: CartItemRequest[]) => Promise<CartMergeResponse>;
}): Promise<MergeResult> {
  let batch = mergeBatch(deps.read());
  if (batch.length === 0) return { kind: 'empty' };
  let dropped = 0;
  for (let attempt = 0; ; attempt += 1) {
    try {
      const result = await deps.post(mergeRequestItems(batch));
      deps.remove(batch);
      return { kind: 'merged', skipped: result.skippedItems.length + dropped };
    } catch (cause) {
      if (!(cause instanceof ApiError)) return { kind: 'retryLater' };
      switch (cause.kind) {
        case 'unauthorized':
          return { kind: 'signedOut' };
        case 'validation': {
          const rejected = attempt === 0 ? rejectedItemIndexes(cause, batch.length) : null;
          const rest = rejected ? batch.filter((_, index) => !rejected.has(index)) : [];
          if (!rejected || rest.length === 0) {
            deps.remove(batch);
            return { kind: 'invalid', dropped: dropped + batch.length };
          }
          deps.remove(batch.filter((_, index) => rejected.has(index)));
          dropped += rejected.size;
          batch = rest;
          continue;
        }
        case 'unknownOutcome':
          deps.remove(batch);
          return { kind: 'unknown' };
        default:
          return { kind: 'retryLater' };
      }
    }
  }
}
