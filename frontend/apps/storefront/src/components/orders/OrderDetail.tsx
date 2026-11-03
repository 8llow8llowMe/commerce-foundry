'use client';

import { ApiError, apiFetch, createIdempotencyKey, type Money } from '@cf/api-client';
import { Button, type Locale } from '@cf/ui';
import { useCallback, useEffect, useId, useRef, useState, type ReactNode, type Ref } from 'react';
import { AddressDetails } from '@/components/account/AddressDetails';
import { SignedOutNotice } from '@/components/account/SignedOutNotice';
import { QuoteSummary } from '@/components/checkout/QuoteSummary';
import { useDictionary, useFormatMoney, useLocale } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { countryOptions } from '@/lib/account/countries';
import { formatDueAt, nextAttempt, type OrderAttempt } from '@/lib/checkout/order';
import { paymentPageHref } from '@/lib/checkout/payment';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';
import {
  deliveryLabel,
  readOrderDelivery,
  trackingUrl,
  type DeliveryLoad,
} from '@/lib/orders/delivery';
import {
  CANCEL_REASONS,
  cancelFailure,
  canResumePayment,
  confirmFailure,
  orderActions,
  orderCancelPath,
  orderDetailPath,
  orderPageHref,
  orderPurchaseConfirmationPath,
  orderReturnPath,
  RETURN_REASONS,
  returnableLines,
  returnFailure,
  returnRequestBody,
  statusLabel,
  type CancelReason,
  type OrderDetail as Order,
  type OrderDetailLine,
  type OrderReturnResult,
  type ReturnReason,
} from '@/lib/orders/history';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';

type Load =
  | { status: 'loading' }
  | { status: 'failed' }
  | { status: 'signedOut' }
  | { status: 'notFound' }
  | { status: 'ready'; order: Order; now: number };

/**
 * 주문 상세 · 결제 전 취소 · 구매 확정(#28 · BE #16). 상세는 브라우저에서 BFF 로 읽고, 액션은 `orderActions` 가 정한 것만 둔다
 * (취소는 서버의 `cancelable`, 구매 확정은 배송 완료). 두 액션 모두 확인 패널을 거친다.
 * 취소는 사유 코드(`CANCEL_REASONS`)를 골라 `POST .../cancellation`, 구매 확정은 `PATCH .../purchase-confirmation` —
 * 응답이 바뀐 상세라 그대로 바꿔 끼운다. 실패 처리는 `cancelFailure` · `confirmFailure` 다(경합 · 결과 모름은 상세를 다시 읽는다).
 * 부분 반품은 항목 · 수량 · 사유를 골라 `POST .../returns`(멱등 키 필수) — 응답은 클레임이라 접수 뒤 상세를 다시 읽어
 * 주문 상태(반품 요청)를 반영한다. 실패 처리는 `returnFailure`(같은 키로 다시 보낼지 가른다). 결제 뒤 취소 · 환불은 BE 전이라 두지 않는다.
 * 배송 조회(BE #18 PR-B2)는 상세와 함께 따로 읽는다 — 실패해도 상세는 그대로 보이고 배송 자리에서만 다시 시도한다.
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
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const confirmingNow = useRef(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);
  const [returnOpen, setReturnOpen] = useState(false);
  // 주문 항목 아이디 → 반품 수량. 없으면 고르지 않은 항목
  const [returnSelected, setReturnSelected] = useState<Record<string, number>>({});
  const [returnReason, setReturnReason] = useState<ReturnReason | null>(null);
  const [returning, setReturning] = useState(false);
  const returningNow = useRef(false);
  const [returnError, setReturnError] = useState<string | null>(null);
  // 같은 키로 다시 보내도 되는 실패 뒤에만 남는 시도(본문 + 키). 본문이 바뀌면 nextAttempt 가 새 키를 만든다
  const returnAttempt = useRef<OrderAttempt | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const cooldown = useRetryCooldown();
  // 패널이 열리고 닫히면 누른 버튼이 사라진다 — 다음 렌더 뒤 포커스를 옮길 곳(주문서의 pendingFocus 와 같은 방식)
  const pendingFocus = useRef<'panel' | 'trigger' | 'returnTrigger' | 'notice' | 'delivery' | null>(
    null,
  );
  const panelTitle = useRef<HTMLHeadingElement>(null);
  const trigger = useRef<HTMLDivElement>(null);
  const returnTrigger = useRef<HTMLDivElement>(null);
  const noticeRef = useRef<HTMLParagraphElement>(null);
  const deliveryTitle = useRef<HTMLHeadingElement>(null);
  const [delivery, setDelivery] = useState<DeliveryLoad>({ status: 'loading' });

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

  const readDelivery = useCallback(() => readOrderDelivery(orderId).then(setDelivery), [orderId]);

  useEffect(() => {
    void read();
    void readDelivery();
  }, [read, readDelivery]);

  useEffect(() => {
    const target = pendingFocus.current;
    if (!target) return;
    pendingFocus.current = null;
    // 취소가 끝나 버튼이 없으면 안내로. 배송 다시 읽기가 성공하면 버튼이 사라져 배송 제목으로(송장이 없으면 그대로)
    const element =
      target === 'panel'
        ? panelTitle.current
        : target === 'trigger'
          ? (trigger.current?.querySelector('button') ?? noticeRef.current)
          : target === 'returnTrigger'
            ? (returnTrigger.current?.querySelector('button') ?? noticeRef.current)
            : target === 'delivery'
              ? deliveryTitle.current
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

  const closeConfirm = (focus: 'trigger' | 'notice' = 'trigger') => {
    pendingFocus.current = focus;
    setConfirmOpen(false);
    setConfirmError(null);
  };

  const confirmPurchase = async () => {
    // 상태 반영 전 두 번째 클릭은 ref 로 막는다
    if (confirmingNow.current) return;
    confirmingNow.current = true;
    setConfirming(true);
    setConfirmError(null);
    try {
      // 이미 확정된 주문도 200 으로 확정된 상세가 온다(자동 구매 확정과 겹친 경우 포함)
      const confirmed = await apiFetch<Order>(orderPurchaseConfirmationPath(orderId), {
        method: 'PATCH',
      });
      setLoad({ status: 'ready', order: confirmed, now: Date.now() });
      closeConfirm('notice');
      setNotice(t.confirmed);
    } catch (cause) {
      cooldown.startFrom(cause);
      const failure = confirmFailure(cause, t);
      if (failure.kind === 'signedOut') {
        setLoad({ status: 'signedOut' });
      } else if (failure.kind === 'reload') {
        // 반품 요청 등 다른 전이가 먼저였거나 결과를 모른다 — 지금 상태를 다시 읽는다. 배송 완료로 남아 있으면 다시 누르면 된다
        closeConfirm('notice');
        setNotice(failure.message);
        await read();
      } else {
        setConfirmError(failure.message);
      }
    } finally {
      confirmingNow.current = false;
      setConfirming(false);
    }
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

  const closeReturn = (focus: 'returnTrigger' | 'notice' = 'returnTrigger') => {
    pendingFocus.current = focus;
    setReturnOpen(false);
    setReturnSelected({});
    setReturnReason(null);
    setReturnError(null);
    returnAttempt.current = null;
  };

  const { order } = load;
  const actions = orderActions(order);
  const returnBody = returnRequestBody(order, returnSelected, returnReason);

  const requestReturn = async () => {
    // 상태 반영 전 두 번째 클릭은 ref 로 막는다
    if (!returnBody || returningNow.current) return;
    returningNow.current = true;
    setReturning(true);
    setReturnError(null);
    // 같은 본문의 재시도는 같은 키 — 앞 시도가 접수됐으면 서버가 그 접수를 200 으로 재생한다
    const attempt = nextAttempt(
      returnAttempt.current,
      JSON.stringify(returnBody),
      createIdempotencyKey,
    );
    returnAttempt.current = attempt;
    try {
      // 응답은 클레임(접수 상태 · 항목). 환불 금액은 미산정(null)이라 보이지 않는다
      await apiFetch<OrderReturnResult>(orderReturnPath(orderId), {
        method: 'POST',
        json: returnBody,
        idempotencyKey: attempt.idempotencyKey,
      });
      closeReturn('notice');
      setNotice(t.returned);
      // 배송 완료였으면 반품 요청으로 바뀌었다 — 상태는 서버에서 다시 읽는다
      await read();
    } catch (cause) {
      cooldown.startFrom(cause);
      const failure = returnFailure(cause, t);
      if (failure.kind === 'signedOut') {
        setLoad({ status: 'signedOut' });
      } else if (failure.kind === 'reload') {
        // 구매 확정 등으로 반품할 수 없게 됐다 — 지금 상태를 다시 읽는다(반품할 수 없으면 버튼이 사라진다)
        closeReturn('notice');
        setNotice(failure.message);
        await read();
      } else {
        if (!failure.sameKey) returnAttempt.current = null;
        setReturnError(failure.message);
      }
    } finally {
      returningNow.current = false;
      setReturning(false);
    }
  };

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
        shipment={
          <DeliveryView
            load={delivery}
            titleRef={deliveryTitle}
            t={t}
            locale={locale}
            onRetry={() => {
              if (delivery.status === 'failed' && delivery.retrying) return;
              setDelivery({ status: 'failed', retrying: true });
              // 상태 반영과 같은 렌더 뒤에 포커스를 옮기도록 결과를 넣기 전에 표시한다
              void readOrderDelivery(orderId).then((next) => {
                pendingFocus.current = 'delivery';
                setDelivery(next);
              });
            }}
          />
        }
      />
      {/* 반품 · 구매 확정 패널은 한 번에 하나만 연다 — 제목 포커스 ref 를 함께 쓴다 */}
      {actions.requestReturn &&
        !confirmOpen &&
        (returnOpen ? (
          <ReturnPanel
            titleRef={panelTitle}
            t={t}
            lines={returnableLines(order)}
            selected={returnSelected}
            onSelect={(orderItemId, quantity) => {
              setReturnSelected((current) => {
                const next = { ...current };
                if (quantity > 0) next[orderItemId] = quantity;
                else delete next[orderItemId];
                return next;
              });
              setReturnError(null);
            }}
            reason={returnReason}
            onReason={(next) => {
              setReturnReason(next);
              setReturnError(null);
            }}
            error={returnError}
            ready={returnBody !== null}
            returning={returning}
            blocked={cooldown.coolingDown}
            onConfirm={() => void requestReturn()}
            onClose={() => closeReturn()}
          />
        ) : (
          <div ref={returnTrigger}>
            <Button
              variant="secondary"
              className="h-12 w-full"
              onClick={() => {
                setNotice(null);
                pendingFocus.current = 'panel';
                setReturnOpen(true);
              }}
            >
              {t.requestReturn}
            </Button>
          </div>
        ))}
      {actions.confirmPurchase &&
        !returnOpen &&
        (confirmOpen ? (
          <ConfirmPurchasePanel
            titleRef={panelTitle}
            t={t}
            error={confirmError}
            confirming={confirming}
            blocked={cooldown.coolingDown}
            onConfirm={() => void confirmPurchase()}
            onClose={() => closeConfirm()}
          />
        ) : (
          <div ref={trigger}>
            <Button
              className="h-12 w-full"
              onClick={() => {
                setNotice(null);
                pendingFocus.current = 'panel';
                setConfirmOpen(true);
              }}
            >
              {t.confirm}
            </Button>
          </div>
        ))}
      {actions.cancel &&
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

/** 주문 상세(표시만) — 상태 · 주문 번호 · 일시 · 결제 이어하기 · 배송(`shipment`) · 항목 · 금액(서버 값) · 배송지 */
export function OrderDetailView({
  order,
  now,
  t,
  locale,
  formatMoney,
  shipment,
}: {
  order: Order;
  now: number;
  t: Dictionary['orders'];
  locale: Locale;
  formatMoney: (money: Money) => string;
  shipment?: ReactNode;
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

      {shipment}

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

/**
 * 배송 정보 — 택배사 · 송장 번호 · 배송 상태 · 출고 · 배송 완료 일시와 택배사 조회 링크(새 창).
 * 불러오는 중이거나 송장이 없으면(null) 아무것도 그리지 않는다. 실패하면 이 자리에서만 다시 시도한다.
 */
export function DeliveryView({
  load,
  titleRef,
  t,
  locale,
  onRetry,
}: {
  load: DeliveryLoad;
  /** 다시 읽기가 성공하면 포커스를 받을 제목 */
  titleRef?: Ref<HTMLHeadingElement>;
  t: Dictionary['orders'];
  locale: Locale;
  onRetry: () => void;
}) {
  if (load.status === 'loading') return null;
  if (load.status === 'failed') {
    return (
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        {/* 상세 본문은 보이고 있어 끼어들지 않는다(status) */}
        <p role="status" className="text-muted-fg">
          {t.deliveryLoadFailed}
        </p>
        <Button
          variant="secondary"
          disabled={load.retrying}
          aria-busy={load.retrying}
          onClick={onRetry}
        >
          {t.reload}
        </Button>
      </div>
    );
  }
  const { delivery } = load;
  if (!delivery) return null;
  const href = trackingUrl(delivery);
  return (
    <section aria-labelledby="order-delivery" className="flex flex-col gap-3">
      <h2 id="order-delivery" ref={titleRef} tabIndex={-1} className="text-lg">
        {t.delivery}
      </h2>
      <div className="flex flex-col gap-3 bg-muted px-5 py-5 text-sm">
        <p className="text-base font-medium">
          {deliveryLabel(delivery.status, t.deliveryStatuses, locale)}
        </p>
        {/* 상태 설명은 서버 국문 고정이라 국문 스토어만 */}
        {locale === 'ko' && delivery.status.description && (
          <p className="text-muted-fg">{delivery.status.description}</p>
        )}
        <dl className="flex flex-col gap-1">
          <div className="flex justify-between gap-4">
            <dt className="text-muted-fg">{t.carrier}</dt>
            <dd>{deliveryLabel(delivery.carrier, t.carriers, locale)}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-muted-fg">{t.trackingNumber}</dt>
            <dd className="break-all">{delivery.trackingNumber}</dd>
          </div>
          <div className="flex justify-between gap-4">
            <dt className="text-muted-fg">{t.shippedAt}</dt>
            <dd>{formatDueAt(delivery.shippedAt, locale)}</dd>
          </div>
          {delivery.deliveredAt && (
            <div className="flex justify-between gap-4">
              <dt className="text-muted-fg">{t.deliveredAt}</dt>
              <dd>{formatDueAt(delivery.deliveredAt, locale)}</dd>
            </div>
          )}
        </dl>
        {href && (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer"
            // @cf/ui Button(secondary) 색에 이 화면의 큰 버튼 크기(h-12) — 택배사 사이트로 이동이라 a 다
            className="inline-flex h-12 items-center justify-center rounded-md bg-accent px-6 text-sm font-medium text-accent-fg transition hover:opacity-90"
          >
            {t.track}
            <span className="sr-only"> {t.newWindow}</span>
          </a>
        )}
      </div>
    </section>
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

/** 구매 확정 확인 — 확정 뒤에는 반품 · 교환을 요청할 수 없다는 안내를 함께 둔다 */
export function ConfirmPurchasePanel({
  titleRef,
  t,
  error,
  confirming,
  blocked,
  onConfirm,
  onClose,
}: {
  /** 패널이 열리면 포커스를 받을 제목 */
  titleRef?: Ref<HTMLHeadingElement>;
  t: Dictionary['orders'];
  error: string | null;
  confirming: boolean;
  blocked: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const name = useId();
  return (
    <section
      aria-labelledby={`${name}-title`}
      aria-describedby={`${name}-caution`}
      className="flex flex-col gap-4 border border-border p-5"
    >
      <h2 id={`${name}-title`} ref={titleRef} tabIndex={-1} className="text-lg">
        {t.confirmTitle}
      </h2>
      <p id={`${name}-caution`} className="text-sm text-muted-fg">
        {t.confirmCaution}
      </p>
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          disabled={confirming || blocked}
          aria-busy={confirming}
          onClick={onConfirm}
          className="h-12 flex-1"
        >
          {confirming ? t.confirming : t.confirmConfirm}
        </Button>
        <Button variant="secondary" disabled={confirming} onClick={onClose} className="h-12 flex-1">
          {t.confirmClose}
        </Button>
      </div>
    </section>
  );
}

/**
 * 부분 반품 접수 — 항목(주문 항목 아이디가 있는 줄)을 고르고 수량(1 ~ 주문 수량) · 사유(모든 항목 공통)를 정한다.
 * 고른 항목과 사유가 있어야 누를 수 있다. 이미 접수한 수량을 뺀 잔여 수량은 서버가 판단한다(넘으면 `ORDER_030`).
 */
export function ReturnPanel({
  titleRef,
  t,
  lines,
  selected,
  onSelect,
  reason,
  onReason,
  error,
  ready,
  returning,
  blocked,
  onConfirm,
  onClose,
}: {
  /** 패널이 열리면 포커스를 받을 제목 */
  titleRef?: Ref<HTMLHeadingElement>;
  t: Dictionary['orders'];
  lines: (OrderDetailLine & { orderItemId: string })[];
  /** 주문 항목 아이디 → 반품 수량 */
  selected: Record<string, number>;
  /** 수량 0 은 고르지 않음 */
  onSelect: (orderItemId: string, quantity: number) => void;
  reason: ReturnReason | null;
  onReason: (reason: ReturnReason) => void;
  error: string | null;
  /** 보낼 본문이 있다(항목 · 사유를 골랐다) */
  ready: boolean;
  returning: boolean;
  blocked: boolean;
  onConfirm: () => void;
  onClose: () => void;
}) {
  const name = useId();
  return (
    <section
      aria-labelledby={`${name}-title`}
      aria-describedby={`${name}-caution`}
      className="flex flex-col gap-4 border border-border p-5"
    >
      <h2 id={`${name}-title`} ref={titleRef} tabIndex={-1} className="text-lg">
        {t.returnTitle}
      </h2>
      <fieldset className="flex flex-col gap-3" disabled={returning}>
        <legend className="mb-2 text-sm font-medium">{t.returnItems}</legend>
        {lines.map((line) => {
          const quantity = selected[line.orderItemId] ?? 0;
          const max = Math.min(line.quantity, 99);
          return (
            <div
              key={line.orderItemId}
              className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 text-sm"
            >
              <label className="flex min-w-0 items-start gap-3">
                <input
                  type="checkbox"
                  className="mt-1"
                  checked={quantity > 0}
                  onChange={(event) =>
                    onSelect(line.orderItemId, event.currentTarget.checked ? max : 0)
                  }
                />
                <span className="flex min-w-0 flex-col gap-0.5">
                  <span className="font-medium">{line.productName}</span>
                  {line.optionName && <span className="text-muted-fg">{line.optionName}</span>}
                  <span className="text-muted-fg">
                    {fill(t.quantity, { count: line.quantity })}
                  </span>
                </span>
              </label>
              {max > 1 && (
                <select
                  aria-label={fill(t.returnQuantity, { name: line.productName })}
                  value={quantity > 0 ? quantity : max}
                  disabled={quantity === 0}
                  onChange={(event) =>
                    onSelect(line.orderItemId, Number(event.currentTarget.value))
                  }
                  className="h-10 rounded-md border border-border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary disabled:opacity-50"
                >
                  {Array.from({ length: max }, (_, index) => index + 1).map((count) => (
                    <option key={count} value={count}>
                      {count}
                    </option>
                  ))}
                </select>
              )}
            </div>
          );
        })}
      </fieldset>
      <fieldset className="flex flex-col gap-2" disabled={returning}>
        <legend className="mb-2 text-sm font-medium">{t.returnReason}</legend>
        {RETURN_REASONS.map((code) => (
          <label key={code} className="flex items-center gap-3 text-sm">
            <input
              type="radio"
              name={`${name}-reason`}
              value={code}
              checked={reason === code}
              onChange={() => onReason(code)}
            />
            <span>{t.returnReasons[code]}</span>
          </label>
        ))}
      </fieldset>
      <p id={`${name}-caution`} className="text-xs text-muted-fg">
        {t.returnCaution}
      </p>
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        <Button
          disabled={!ready || returning || blocked}
          aria-busy={returning}
          onClick={onConfirm}
          className="h-12 flex-1"
        >
          {returning ? t.returning : t.returnConfirm}
        </Button>
        <Button variant="secondary" disabled={returning} onClick={onClose} className="h-12 flex-1">
          {t.returnClose}
        </Button>
      </div>
    </section>
  );
}
