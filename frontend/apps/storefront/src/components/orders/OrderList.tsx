'use client';

import { ApiError, apiFetch, type Money, type SliceResponse } from '@cf/api-client';
import { Button, type Locale } from '@cf/ui';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { SignedOutNotice } from '@/components/account/SignedOutNotice';
import { useDictionary, useFormatMoney, useLocale } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { formatDueAt } from '@/lib/checkout/order';
import { paymentPageHref } from '@/lib/checkout/payment';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';
import {
  appendOrders,
  canResumePayment,
  orderListPath,
  orderPageHref,
  statusLabel,
  type OrderSummary,
} from '@/lib/orders/history';

type Loaded = {
  status: 'ready';
  orders: OrderSummary[];
  /** 마지막으로 읽은 조각(0 부터) */
  page: number;
  hasNext: boolean;
  /** 결제 이어하기 판단 기준 시각 — 읽은 시각 */
  now: number;
};
type Load = { status: 'loading' } | { status: 'failed' } | { status: 'signedOut' } | Loaded;

const isUnauthorized = (cause: unknown) =>
  cause instanceof ApiError && cause.kind === 'unauthorized';

/**
 * 주문 내역(#28 · BE #16). `GET /api/v1/orders` 조각(최신순, 전체 건수 없음)을 "더 보기"로 이어 붙인다.
 * 브라우저에서 BFF 로 읽는다(만료된 access 를 BFF 가 재발급할 수 있게). 금액 · 상태는 서버 값이다.
 */
export function OrderList() {
  const t = useDictionary().orders;
  const locale = useLocale();
  const formatMoney = useFormatMoney();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  const loadingMoreNow = useRef(false);

  const first = useCallback(
    () =>
      apiFetch<SliceResponse<OrderSummary>>(orderListPath(0)).then(
        (slice) =>
          setLoad({
            status: 'ready',
            orders: slice.contents,
            page: 0,
            hasNext: slice.hasNext,
            now: Date.now(),
          }),
        (cause: unknown) => setLoad({ status: isUnauthorized(cause) ? 'signedOut' : 'failed' }),
      ),
    [],
  );

  useEffect(() => {
    void first();
  }, [first]);

  if (load.status === 'signedOut') return <SignedOutNotice returnPath="/mypage/orders" />;
  if (load.status === 'failed') {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {t.loadFailed}
        </p>
        <Button
          variant="secondary"
          onClick={() => {
            setLoad({ status: 'loading' });
            void first();
          }}
        >
          {t.reload}
        </Button>
      </div>
    );
  }
  if (load.status === 'loading') {
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {t.loading}
      </p>
    );
  }

  const more = async () => {
    // 상태 반영 전 두 번째 클릭은 ref 로 막는다(같은 조각을 두 번 읽지 않게)
    if (loadingMoreNow.current) return;
    loadingMoreNow.current = true;
    setLoadingMore(true);
    setMoreFailed(false);
    const page = load.page + 1;
    try {
      const slice = await apiFetch<SliceResponse<OrderSummary>>(orderListPath(page));
      setLoad((prev) =>
        prev.status === 'ready'
          ? {
              ...prev,
              orders: appendOrders(prev.orders, slice.contents),
              page,
              hasNext: slice.hasNext,
            }
          : prev,
      );
    } catch (cause) {
      if (isUnauthorized(cause)) setLoad({ status: 'signedOut' });
      else setMoreFailed(true);
    } finally {
      loadingMoreNow.current = false;
      setLoadingMore(false);
    }
  };

  return (
    <OrderListView
      orders={load.orders}
      now={load.now}
      t={t}
      locale={locale}
      formatMoney={formatMoney}
      footer={
        load.hasNext && (
          <div className="flex flex-col items-center gap-2">
            {moreFailed && (
              <p role="alert" className="text-sm text-danger">
                {t.loadFailed}
              </p>
            )}
            <Button
              variant="secondary"
              disabled={loadingMore}
              aria-busy={loadingMore}
              onClick={() => void more()}
            >
              {loadingMore ? t.loadingMore : t.more}
            </Button>
          </div>
        )
      }
    />
  );
}

/** 주문 내역 목록(표시만). 결제 대기이고 기한 전인 주문에만 "결제 이어하기"를 둔다 */
export function OrderListView({
  orders,
  now,
  t,
  locale,
  formatMoney,
  footer,
}: {
  orders: OrderSummary[];
  now: number;
  t: Dictionary['orders'];
  locale: Locale;
  formatMoney: (money: Money) => string;
  footer?: ReactNode;
}) {
  if (orders.length === 0) {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p className="text-sm text-muted-fg">{t.empty}</p>
        <Link href="/shop" className="text-sm underline underline-offset-4">
          {t.shop}
        </Link>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-6">
      <ul className="flex flex-col gap-2">
        {orders.map((order) => {
          const resumable = canResumePayment(order, now);
          return (
            <li key={order.orderId} className="flex flex-col gap-3 bg-muted px-5 py-5 text-sm">
              <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <span className="font-medium">{statusLabel(order.status, t, locale)}</span>
                <span className="text-muted-fg">{formatDueAt(order.createdAt, locale)}</span>
              </div>
              <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <span className="min-w-0">
                  {order.orderName ?? fill(t.itemCount, { count: order.itemCount })}
                </span>
                <span>{formatMoney(order.totalAmount)}</span>
              </div>
              {resumable && (
                <p className="text-xs text-muted-fg">
                  {fill(t.paymentDue, { time: formatDueAt(order.paymentDueAt, locale) })}
                </p>
              )}
              <div className="flex flex-wrap gap-4">
                <Link
                  href={orderPageHref(order.orderId)}
                  className="underline underline-offset-4 hover:text-primary"
                >
                  {t.viewDetail}
                  <span className="sr-only">
                    {' '}
                    ({t.orderNumber} {order.orderId})
                  </span>
                </Link>
                {resumable && (
                  <Link
                    href={paymentPageHref(order.orderId)}
                    className="underline underline-offset-4 hover:text-primary"
                  >
                    {t.resumePayment}
                  </Link>
                )}
              </div>
            </li>
          );
        })}
      </ul>
      {footer}
    </div>
  );
}
