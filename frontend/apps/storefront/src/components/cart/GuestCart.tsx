'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import type { ProductDetail } from '@/lib/catalog/types';
import { guestLineView } from '@/lib/cart/line-view';
import {
  readLocalCart,
  removeLocalLine,
  serverLocalCart,
  setLocalQuantity,
  subscribeLocalCart,
} from '@/lib/cart/local-cart';
import { EmptyCart } from './MemberCart';
import { CartLineItem } from './CartLineItem';

type ProductState = ProductDetail | null | 'error';

const noop = () => () => {};

/** 상품 상세를 읽는다. 없거나 숨긴 상품(404)은 null, 그 밖의 실패는 'error'(다시 불러오기 대상) */
function fetchProducts(ids: string[]): Promise<[string, ProductState][]> {
  return Promise.all(
    ids.map(async (id): Promise<[string, ProductState]> => {
      try {
        return [id, await apiFetch<ProductDetail>(`/api/v1/products/${encodeURIComponent(id)}`)];
      } catch (cause) {
        return [id, cause instanceof ApiError && cause.kind === 'notFound' ? null : 'error'];
      }
    }),
  );
}

/**
 * 비회원(로컬) 장바구니. 줄에는 SKU · 수량 · 맞춤 제작 입력만 있으므로 상품마다 상세 API 로 현재 이름 · 가격 · 상태를 읽는다
 * (보통 몇 개라 상품 수만큼 부른다). 없거나 숨긴 상품(404)은 판매하지 않는 상품으로 남겨 지울 수 있게 한다.
 * 합계는 보이지 않는다 — 금액은 서버가 계산한 값만 보이는 규칙이라, 로그인하면 회원 장바구니가 합계를 낸다.
 */
export function GuestCart() {
  const t = useDictionary().cart;
  const lines = useSyncExternalStore(subscribeLocalCart, readLocalCart, serverLocalCart);
  const [products, setProducts] = useState<Map<string, ProductState>>(new Map());
  const hydrated = useSyncExternalStore(
    noop,
    () => true,
    () => false,
  );
  const productIds = [...new Set(lines.map((line) => line.productId))];
  // 아직 읽지 않은 상품만 자동으로 읽는다. 실패한 상품은 다시 불러오기 버튼으로
  const unloadedKey = productIds.filter((id) => !products.has(id)).join(',');
  const failedIds = productIds.filter((id) => products.get(id) === 'error');

  const loadProducts = useCallback((ids: string[]) => {
    void fetchProducts(ids).then((entries) =>
      setProducts((current) => new Map([...current, ...entries])),
    );
  }, []);

  useEffect(() => {
    if (unloadedKey) loadProducts(unloadedKey.split(','));
  }, [unloadedKey, loadProducts]);

  // 서버 렌더 · 첫 렌더에서는 저장소를 읽지 않아(빈 장바구니) 깜빡임 대신 불러오는 중으로 둔다
  if (!hydrated) {
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {t.loading}
      </p>
    );
  }
  if (lines.length === 0) return <EmptyCart />;

  const pending = unloadedKey !== '';

  return (
    <div className="flex flex-col gap-8">
      {pending ? (
        <p role="status" className="py-8 text-center text-sm text-muted-fg">
          {t.loading}
        </p>
      ) : (
        <ul className="border-t border-border">
          {lines.map((line) => {
            const product = products.get(line.productId);
            if (product === 'error' || product === undefined) return null;
            return (
              <CartLineItem
                key={`${line.skuId}-${line.addedAt}`}
                line={guestLineView(line, product)}
                busy={false}
                onQuantity={(quantity) =>
                  setLocalQuantity(line.skuId, line.customOptions, quantity)
                }
                onRemove={() => removeLocalLine(line.skuId, line.customOptions)}
              />
            );
          })}
        </ul>
      )}
      {failedIds.length > 0 && (
        <div className="flex flex-col items-center gap-3 text-center">
          <p role="alert" className="text-sm text-muted-fg">
            {t.loadFailed}
          </p>
          <Button variant="secondary" onClick={() => loadProducts(failedIds)}>
            {t.reload}
          </Button>
        </div>
      )}

      <div className="flex flex-col gap-4 sm:ml-auto sm:w-80">
        <p className="text-sm text-muted-fg">{t.guestNotice}</p>
        <Link
          href="/login?next=%2Fcart"
          className="inline-flex h-12 w-full items-center justify-center bg-primary text-sm font-medium text-primary-fg hover:opacity-90"
        >
          {t.login}
        </Link>
      </div>
    </div>
  );
}
