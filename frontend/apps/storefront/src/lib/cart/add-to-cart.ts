'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { useRouter } from 'next/navigation';
import { useCallback } from 'react';
import { useSignedIn } from '@/components/auth/SessionProvider';
import { addToLocalCart, normalizeOptions } from './local-cart';
import type { CartItemRequest, CartResponse, CustomOption } from './types';

export type AddLine = {
  productId: string;
  skuId: string;
  quantity: number;
  customOptions: CustomOption[];
  /** 상품의 1회 주문 최대 수량(null 이면 99) — 로컬 장바구니의 수량 상한 */
  maxQuantity: number | null;
};

export type AddOutcome =
  | { status: 'added' }
  /** 비회원 장바구니가 50줄이다 */
  | { status: 'lineLimit' }
  /** 브라우저 저장소를 쓸 수 없다 */
  | { status: 'storageUnavailable' }
  /** 서버가 거절한 줄이 있다. error 는 첫 거절, addedCount 는 담긴 줄 수(여러 줄을 차례로 담을 때) */
  | { status: 'failed'; error: unknown; addedCount: number };

/**
 * 장바구니 담기. 로그인 상태면 회원 장바구니(`POST /api/v1/carts/me/items`)에 한 줄씩 차례로,
 * 아니면 브라우저 로컬 장바구니에 담는다. 회원 담기가 401(세션 끝남)이면 남은 줄을 로컬에 담고 화면을 다시 그린다 —
 * 로그인하면 병합된다. 회원 담기에 멱등 키를 두지 않는 이유는 BE 와 같다(결제 · 재고와 무관, 상한이 피해를 제한).
 */
export function useAddToCart(): (lines: AddLine[]) => Promise<AddOutcome> {
  const signedIn = useSignedIn();
  const router = useRouter();

  return useCallback(
    async (lines) => {
      const toLocal = (rest: AddLine[]): AddOutcome => {
        const result = addToLocalCart(
          rest.map(({ maxQuantity, ...line }) => ({ ...line, maxQuantity })),
        );
        return { status: result };
      };
      if (!signedIn) return toLocal(lines);

      const result = await addMemberLines(lines, (body) =>
        apiFetch<CartResponse>('/api/v1/carts/me/items', { method: 'POST', json: body }),
      );
      if ('remaining' in result) {
        router.refresh();
        return toLocal(result.remaining);
      }
      return result;
    },
    [signedIn, router],
  );
}

/**
 * 회원 장바구니에 한 줄씩 차례로 담는다. 한 줄이 거절돼도(상한 · 품절) 나머지는 담는다.
 * 요청 제한(429) · 연결 실패 · 결과 모름(502/504)이면 멈춘다 — 나머지도 같은 결과이거나, 사용자가 장바구니를 보고 다시 누르게 한다.
 * 401(세션 끝남)이면 남은 줄을 돌려준다 — 호출한 쪽이 로컬에 담는다.
 */
export async function addMemberLines(
  lines: AddLine[],
  post: (body: CartItemRequest) => Promise<unknown>,
): Promise<AddOutcome | { remaining: AddLine[] }> {
  let addedCount = 0;
  let firstError: unknown = null;
  for (const [index, line] of lines.entries()) {
    const body: CartItemRequest = { skuId: line.skuId, quantity: line.quantity };
    // 로컬 담기와 같은 정규화 — 제어 문자가 남은 값은 BE 가 400 CART_115 로 거절한다(#263)
    const customOptions = normalizeOptions(line.customOptions);
    if (customOptions.length > 0) body.customOptions = customOptions;
    try {
      await post(body);
      addedCount += 1;
    } catch (error) {
      if (error instanceof ApiError && error.kind === 'unauthorized') {
        return { remaining: lines.slice(index) };
      }
      firstError ??= error;
      const stop =
        !(error instanceof ApiError) ||
        error.status === null ||
        error.kind === 'rateLimited' ||
        error.kind === 'unknownOutcome';
      if (stop) break;
    }
  }
  return firstError === null
    ? { status: 'added' }
    : { status: 'failed', error: firstError, addedCount };
}
