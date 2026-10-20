import { ApiError } from '@cf/api-client';
import type { LocalCartLine } from './local-cart';
import { CART_MAX_LINES, type CartItemRequest, type CartMergeResponse } from './types';

/** 병합이 끝나 회원 장바구니가 바뀌었음을 알리는 창 이벤트 — 장바구니 화면이 다시 읽는다 */
export const CART_MERGED_EVENT = 'cf:cart-merged';

/** 병합으로 보낼 줄 — 오래 담은 것부터 50줄(BE 상한). 나머지는 다음 병합에 간다 */
export function mergeBatch(lines: LocalCartLine[]): LocalCartLine[] {
  return [...lines].sort((a, b) => a.addedAt - b.addedAt).slice(0, CART_MAX_LINES);
}

export function mergeRequestItems(batch: LocalCartLine[]): CartItemRequest[] {
  return batch.map((line) => {
    const item: CartItemRequest = { skuId: line.skuId, quantity: line.quantity };
    if (line.customOptions.length > 0) item.customOptions = line.customOptions;
    return item;
  });
}

export type MergeResult =
  | { kind: 'empty' }
  | { kind: 'merged'; skipped: number }
  /** 400 — 형식이 틀린 줄이 있어 요청 전체가 거절됐다. 다시 보내도 같으므로 보낸 줄을 버렸다 */
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
 */
export async function mergeLocalCart(deps: {
  read: () => LocalCartLine[];
  remove: (batch: LocalCartLine[]) => void;
  post: (items: CartItemRequest[]) => Promise<CartMergeResponse>;
}): Promise<MergeResult> {
  const batch = mergeBatch(deps.read());
  if (batch.length === 0) return { kind: 'empty' };
  try {
    const result = await deps.post(mergeRequestItems(batch));
    deps.remove(batch);
    return { kind: 'merged', skipped: result.skippedItems.length };
  } catch (cause) {
    if (!(cause instanceof ApiError)) return { kind: 'retryLater' };
    switch (cause.kind) {
      case 'unauthorized':
        return { kind: 'signedOut' };
      case 'validation':
        deps.remove(batch);
        return { kind: 'invalid', dropped: batch.length };
      case 'unknownOutcome':
        deps.remove(batch);
        return { kind: 'unknown' };
      default:
        return { kind: 'retryLater' };
    }
  }
}
