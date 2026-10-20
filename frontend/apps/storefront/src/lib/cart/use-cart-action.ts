'use client';

import { useState } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { fill } from '@/lib/i18n/format';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';
import { useAddToCart, type AddLine, type AddOutcome } from './add-to-cart';
import { cartErrorText } from './cart-errors';

export type CartActionResult = {
  /** 버튼 아래에 보일 문구 */
  text: string;
  /** 하나라도 담겼으면 장바구니 링크를 함께 보인다 */
  showCartLink: boolean;
};

/** 담기 결과를 화면 문구로 — 테스트하려고 훅 밖에 둔다 */
export function cartActionResult(
  outcome: AddOutcome,
  t: ReturnType<typeof useDictionary>['cart'],
): CartActionResult {
  switch (outcome.status) {
    case 'added':
      return { text: t.added, showCartLink: true };
    case 'lineLimit':
      return { text: t.lineLimit, showCartLink: true };
    case 'storageUnavailable':
      return { text: t.storageUnavailable, showCartLink: false };
    case 'failed': {
      const reason = cartErrorText(outcome.error, t);
      if (outcome.addedCount === 0) return { text: reason, showCartLink: false };
      return {
        text: `${fill(t.partlyAdded, { count: outcome.addedCount })} ${reason}`,
        showCartLink: true,
      };
    }
  }
}

/**
 * 담기 버튼 하나의 상태 — 진행 중 · 429 쿨다운 · 결과 문구. 상세 구매 패널과 Shop the Look 이 같이 쓴다.
 * 선택을 바꾸면 이전 결과 문구는 지운다(clear).
 */
export function useCartAction(): {
  pending: boolean;
  coolingDown: boolean;
  result: CartActionResult | null;
  add: (lines: AddLine[]) => Promise<void>;
  clear: () => void;
} {
  const t = useDictionary().cart;
  const addToCart = useAddToCart();
  const cooldown = useRetryCooldown();
  const [pending, setPending] = useState(false);
  const [result, setResult] = useState<CartActionResult | null>(null);

  const add = async (lines: AddLine[]) => {
    if (pending || cooldown.coolingDown || lines.length === 0) return;
    setPending(true);
    setResult(null);
    const outcome = await addToCart(lines);
    if (outcome.status === 'failed') cooldown.startFrom(outcome.error);
    setResult(cartActionResult(outcome, t));
    setPending(false);
  };

  return {
    pending,
    coolingDown: cooldown.coolingDown,
    result,
    add,
    clear: () => setResult(null),
  };
}
