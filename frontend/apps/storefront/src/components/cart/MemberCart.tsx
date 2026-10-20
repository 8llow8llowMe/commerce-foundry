'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { cartErrorText } from '@/lib/cart/cart-errors';
import { memberLineView } from '@/lib/cart/line-view';
import type { CartResponse } from '@/lib/cart/types';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';
import { CartLineItem } from './CartLineItem';

const CART_PATH = '/api/v1/carts/me';

/**
 * 회원 장바구니(`/api/v1/carts/me`). 가격 · 상태 · 합계는 조회 시점의 서버 값이고, 수량 변경 · 삭제는 갱신된 장바구니 전체를 돌려받아 바꿔 끼운다.
 * 401(세션 끝남)이면 화면을 다시 그려 비회원 장바구니로 내려간다. 429 는 Retry-After 동안 줄 버튼을 막는다.
 * `refreshKey` 가 바뀌면 다시 읽는다 — 로그인 직후 병합이 끝났을 때.
 */
export function MemberCart({ refreshKey }: { refreshKey: number }) {
  const t = useDictionary().cart;
  const formatMoney = useFormatMoney();
  const router = useRouter();
  const cooldown = useRetryCooldown();
  const [cart, setCart] = useState<CartResponse | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  // 401 — 세션이 끝났다. refresh 쿠키 없이 access 만 남은 경우 쿠키가 지워지지 않아 다시 그려도 회원 화면일 수 있어 안내로 멈춘다
  const [signedOut, setSignedOut] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleError = useCallback(
    (cause: unknown) => {
      if (cause instanceof ApiError && cause.kind === 'unauthorized') {
        setSignedOut(true);
        router.refresh();
        return;
      }
      cooldown.startFrom(cause);
      setError(cartErrorText(cause, t));
    },
    [router, cooldown, t],
  );

  const fetchCart = useCallback(
    () =>
      apiFetch<CartResponse>(CART_PATH).then(setCart, (cause: unknown) => {
        if (cause instanceof ApiError && cause.kind === 'unauthorized') {
          setSignedOut(true);
          router.refresh();
        } else setLoadFailed(true);
      }),
    [router],
  );

  useEffect(() => {
    void fetchCart();
  }, [fetchCart, refreshKey]);

  const reload = () => {
    setLoadFailed(false);
    void fetchCart();
  };

  const mutate = async (path: string, init: Parameters<typeof apiFetch>[1]) => {
    if (busy || cooldown.coolingDown) return;
    setBusy(true);
    setError(null);
    try {
      setCart(await apiFetch<CartResponse>(`${CART_PATH}${path}`, init));
    } catch (cause) {
      handleError(cause);
    } finally {
      setBusy(false);
    }
  };

  if (signedOut) {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {t.signInRequired}
        </p>
        <Link href="/login?next=%2Fcart" className="text-sm underline underline-offset-4">
          {t.login}
        </Link>
      </div>
    );
  }
  if (loadFailed) {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {t.loadFailed}
        </p>
        <Button variant="secondary" onClick={reload}>
          {t.reload}
        </Button>
      </div>
    );
  }
  if (!cart) {
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {t.loading}
      </p>
    );
  }
  if (cart.items.length === 0) return <EmptyCart />;

  return (
    <div className="flex flex-col gap-8">
      <ul className="border-t border-border">
        {cart.items.map((line) => (
          <CartLineItem
            key={line.itemId}
            line={memberLineView(line)}
            busy={busy || cooldown.coolingDown}
            onQuantity={(quantity) =>
              void mutate(`/items/${line.itemId}`, { method: 'PATCH', json: { quantity } })
            }
            onRemove={() => void mutate(`/items/${line.itemId}`, { method: 'DELETE' })}
          />
        ))}
      </ul>
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}

      <div className="flex flex-col gap-4 sm:ml-auto sm:w-80">
        <div className="flex items-baseline justify-between">
          <span className="text-sm">{t.total}</span>
          <span className="text-lg">{cart.totalAmount ? formatMoney(cart.totalAmount) : '—'}</span>
        </div>
        <p className="text-xs text-muted-fg">{t.totalNote}</p>
        {/* 주문서 · 주문 생성은 주문 API(#13) 뒤에 붙인다 */}
        <Button disabled className="h-12 w-full" aria-describedby="checkout-pending">
          {t.checkout}
        </Button>
        <p id="checkout-pending" className="text-center text-xs text-muted-fg">
          {t.checkoutPending}
        </p>
      </div>
    </div>
  );
}

export function EmptyCart() {
  const t = useDictionary().cart;
  return (
    <div className="flex flex-col items-center gap-6 py-16 text-center">
      <p className="text-sm text-muted-fg">{t.empty}</p>
      <Link href="/shop" className="text-sm underline underline-offset-4">
        {t.continueShopping}
      </Link>
    </div>
  );
}
