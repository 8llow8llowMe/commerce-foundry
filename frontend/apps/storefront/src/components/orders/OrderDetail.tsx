'use client';

import { ApiError, apiFetch, type Money } from '@cf/api-client';
import { Button, type Locale } from '@cf/ui';
import { useCallback, useEffect, useId, useRef, useState, type Ref } from 'react';
import { AddressDetails } from '@/components/account/AddressDetails';
import { SignedOutNotice } from '@/components/account/SignedOutNotice';
import { QuoteSummary } from '@/components/checkout/QuoteSummary';
import { useDictionary, useFormatMoney, useLocale } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { countryOptions } from '@/lib/account/countries';
import { formatDueAt } from '@/lib/checkout/order';
import { paymentPageHref } from '@/lib/checkout/payment';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';
import {
  CANCEL_REASONS,
  cancelFailure,
  canResumePayment,
  orderCancelPath,
  orderDetailPath,
  orderPageHref,
  statusLabel,
  type CancelReason,
  type OrderDetail as Order,
} from '@/lib/orders/history';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';

type Load =
  | { status: 'loading' }
  | { status: 'failed' }
  | { status: 'signedOut' }
  | { status: 'notFound' }
  | { status: 'ready'; order: Order; now: number };

/**
 * 주문 상세 · 결제 전 취소(#28 · BE #16 PR-1). 상세는 브라우저에서 BFF 로 읽고, 취소 버튼은 서버의 `cancelable` 일 때만 둔다.
 * 취소는 사유 코드(`CANCEL_REASONS`)를 골라 `POST .../cancellation` — 응답이 취소된 상세라 그대로 바꿔 끼운다.
 * 실패 처리는 `cancelFailure` 다(경합 · 결과 모름은 상세를 다시 읽는다). 출고 전 취소 · 반품 · 구매 확정 · 배송 조회는 BE 전이라 두지 않는다.
 */
export function OrderDetail({ orderId }: { orderId: string }) {
  const t = useDictionary().orders;
  const locale = useLocale();
  const formatMoney = useFormatMoney();
  const [load, setLoad] = useState<Load>({ status: 'loading' });
  const [cancelOpen, setCancelOpen] = useState(false);
  const [reason, setReason] = useState<CancelReason | null>(null);
  const [canceling, setCanceling] = useState(false);
  const cancelingNow = useRef(false);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const cooldown = useRetryCooldown();
  // 패널이 열리고 닫히면 누른 버튼이 사라진다 — 다음 렌더 뒤 포커스를 옮길 곳(주문서의 pendingFocus 와 같은 방식)
  const pendingFocus = useRef<'panel' | 'trigger' | 'notice' | null>(null);
  const panelTitle = useRef<HTMLHeadingElement>(null);
  const trigger = useRef<HTMLDivElement>(null);
  const noticeRef = useRef<HTMLParagraphElement>(null);

  const read = useCallback(
    () =>
      apiFetch<Order>(orderDetailPath(orderId)).then(
        (order) => setLoad({ status: 'ready', order, now: Date.now() }),
        (cause: unknown) => {
          const kind = cause instanceof ApiError ? cause.kind : null;
          setLoad({
            status:
              kind === 'unauthorized' ? 'signedOut' : kind === 'notFound' ? 'notFound' : 'failed',
          });
        },
      ),
    [orderId],
  );

  useEffect(() => {
    void read();
  }, [read]);

  useEffect(() => {
    const target = pendingFocus.current;
    if (!target) return;
    pendingFocus.current = null;
    // 취소가 끝나 버튼이 없으면 안내로
    const element =
      target === 'panel'
        ? panelTitle.current
        : target === 'trigger'
          ? (trigger.current?.querySelector('button') ?? noticeRef.current)
          : noticeRef.current;
    element?.focus();
  });

  if (load.status === 'signedOut') return <SignedOutNotice returnPath={orderPageHref(orderId)} />;
  if (load.status === 'notFound') {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {t.notFound}
        </p>
        <Link href="/mypage/orders" className="text-sm underline underline-offset-4">
          {t.back}
        </Link>
      </div>
    );
  }
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
            void read();
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

  const closeCancel = (focus: 'trigger' | 'notice' = 'trigger') => {
    pendingFocus.current = focus;
    setCancelOpen(false);
    setReason(null);
    setCancelError(null);
  };

  const cancel = async () => {
    // 상태 반영 전 두 번째 클릭은 ref 로 막는다
    if (!reason || cancelingNow.current) return;
    cancelingNow.current = true;
    setCanceling(true);
    setCancelError(null);
    try {
      const canceled = await apiFetch<Order>(orderCancelPath(orderId), {
        method: 'POST',
        json: { reason },
      });
      setLoad({ status: 'ready', order: canceled, now: Date.now() });
      closeCancel('notice');
      setNotice(t.canceled);
    } catch (cause) {
      cooldown.startFrom(cause);
      const failure = cancelFailure(cause, t);
      if (failure.kind === 'signedOut') {
        setLoad({ status: 'signedOut' });
      } else if (failure.kind === 'reload') {
        // 경합에서 졌거나 결과를 모른다 — 지금 상태를 다시 읽어 보인다. 아직 취소할 수 있으면 다시 누르면 된다(취소는 멱등)
        closeCancel('notice');
        setNotice(failure.message);
        await read();
      } else {
        setCancelError(failure.message);
      }
    } finally {
      cancelingNow.current = false;
      setCanceling(false);
    }
  };

  const { order } = load;
  return (
    <div className="flex flex-col gap-8">
      {/* 상태 영역은 늘 두고 내용만 바꾼다 — 내용과 함께 새로 끼운 라이브 영역은 스크린리더가 읽지 않기도 한다 */}
      <p
        ref={noticeRef}
        role="status"
        tabIndex={-1}
        className={notice ? 'bg-muted px-5 py-4 text-sm' : 'sr-only'}
      >
        {notice}
      </p>
      <OrderDetailView
        order={order}
        now={load.now}
        t={t}
        locale={locale}
        formatMoney={formatMoney}
      />
      {order.cancelable &&
        (cancelOpen ? (
          <CancelPanel
            titleRef={panelTitle}
            t={t}
            reason={reason}
            onReason={(next) => {
              setReason(next);
              setCancelError(null);
            }}
            error={cancelError}
            canceling={canceling}
            blocked={cooldown.coolingDown}
            onConfirm={() => void cancel()}
            onClose={() => closeCancel()}
          />
        ) : (
          <div ref={trigger}>
            <Button
              variant="secondary"
              className="h-12 w-full"
              onClick={() => {
                setNotice(null);
                pendingFocus.current = 'panel';
                setCancelOpen(true);
              }}
            >
              {t.cancel}
            </Button>
          </div>
        ))}
    </div>
  );
}

/** 주문 상세(표시만) — 상태 · 주문 번호 · 일시 · 결제 이어하기 · 항목 · 금액(서버 값) · 배송지 */
export function OrderDetailView({
  order,
  now,
  t,
  locale,
  formatMoney,
}: {
  order: Order;
  now: number;
  t: Dictionary['orders'];
  locale: Locale;
  formatMoney: (money: Money) => string;
}) {
  const resumable = canResumePayment(order, now);
  const address = order.shippingAddress;
  return (
    <div className="flex flex-col gap-8">
      <section className="flex flex-col gap-3 bg-muted px-5 py-5 text-sm">
        <p className="text-base font-medium">{statusLabel(order.status, t, locale)}</p>
        {/* 상태 설명은 서버 국문 고정이라 국문 스토어만 */}
        {locale === 'ko' && order.status.description && (
          <p className="text-muted-fg">{order.status.description}</p>
        )}
        <dl className="flex flex-col gap-1">
          <div className="flex justify-between gap-4">
            <dt className="text-muted-fg">{t.orderNumber}</dt>
            <dd>{order.orderId}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-muted-fg">{t.orderedAt}</dt>
            <dd>{formatDueAt(order.createdAt, locale)}</dd>
          </div>
        </dl>
        {resumable && (
          <div className="flex flex-col gap-3">
            <p className="text-xs text-muted-fg">
              {fill(t.paymentDue, { time: formatDueAt(order.paymentDueAt, locale) })}
            </p>
            <Link
              href={paymentPageHref(order.orderId)}
              // @cf/ui Button(primary) 과 같은 모양의 링크 — 결제 화면으로 이동이라 a 다
              className="inline-flex h-12 items-center justify-center rounded-md bg-primary px-6 text-sm font-medium text-primary-fg transition hover:opacity-90"
            >
              {t.resumePayment}
            </Link>
          </div>
        )}
      </section>

      <section aria-labelledby="order-items" className="flex flex-col gap-4">
        <h2 id="order-items" className="text-lg">
          {t.items}
        </h2>
        <ul className="flex flex-col gap-2">
          {/* 같은 SKU 도 맞춤 입력이 다르면 다른 줄이다 — 순서가 고정된 스냅숏이라 위치를 key 에 섞는다 */}
          {order.items.map((line, index) => (
            <li
              key={`${line.skuId}-${index}`}
              className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 bg-muted px-5 py-4 text-sm"
            >
              <div className="flex min-w-0 flex-col gap-0.5">
                <span className="font-medium">{line.productName}</span>
                {line.optionName && <span className="text-muted-fg">{line.optionName}</span>}
                {line.customOptions.map((option) => (
                  <span key={option.fieldKey} className="text-muted-fg">
                    {option.fieldKey}: {option.value}
                  </span>
                ))}
                <span className="text-muted-fg">{fill(t.quantity, { count: line.quantity })}</span>
              </div>
              <span>{formatMoney(line.lineAmount)}</span>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="order-amounts" className="flex flex-col gap-4">
        <h2 id="order-amounts" className="text-lg">
          {t.amounts}
        </h2>
        <QuoteSummary quote={order} />
      </section>

      <section aria-labelledby="order-address" className="flex flex-col gap-2">
        <h2 id="order-address" className="text-lg">
          {t.shippingAddress}
        </h2>
        <p className="text-sm font-medium">{address.recipientName}</p>
        <AddressDetails
          address={{ ...address, label: null }}
          countries={countryOptions([address.countryCode], locale)}
        />
        {address.deliveryMemo && (
          <p className="text-sm text-muted-fg">
            {t.deliveryMemo}: {address.deliveryMemo}
          </p>
        )}
      </section>
    </div>
  );
}

/** 결제 전 취소 — 사유를 골라야 누를 수 있다. 결제를 진행 중이면 결제가 끝날 수 있다는 안내를 함께 둔다 */
export function CancelPanel({
  titleRef,
  t,
  reason,
  onReason,
  error,
  canceling,
  blocked,
  onConfirm,
  onClose,
}: {
  /** 패널이 열리면 포커스를 받을 제목 */
  titleRef?: Ref<HTMLHeadingElement>;
  t: Dictionary['orders'];
  reason: CancelReason | null;
  onReason: (reason: CancelReason) => void;
  error: string | null;
  canceling: boolean;
  blocked: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const name = useId();
  return (
    <section
      aria-labelledby={`${name}-title`}
      className="flex flex-col gap-4 border border-border p-5"
    >
      <h2 id={`${name}-title`} ref={titleRef} tabIndex={-1} className="text-lg">
        {t.cancelTitle}
      </h2>
      <fieldset className="flex flex-col gap-2" disabled={canceling}>
        <legend className="mb-2 text-sm font-medium">{t.cancelReason}</legend>
        {CANCEL_REASONS.map((code) => (
          <label key={code} className="flex items-center gap-3 text-sm">
            <input
              type="radio"
              name={`${name}-reason`}
              value={code}
              checked={reason === code}
              onChange={() => onReason(code)}
            />
            <span>{t.cancelReasons[code]}</span>
          </label>
        ))}
      </fieldset>
      <p className="text-xs text-muted-fg">{t.cancelCaution}</p>
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          disabled={!reason || canceling || blocked}
          aria-busy={canceling}
          onClick={onConfirm}
          className="h-12 flex-1"
        >
          {canceling ? t.canceling : t.cancelConfirm}
        </Button>
        <Button variant="secondary" disabled={canceling} onClick={onClose} className="h-12 flex-1">
          {t.cancelClose}
        </Button>
      </div>
    </section>
  );
}
