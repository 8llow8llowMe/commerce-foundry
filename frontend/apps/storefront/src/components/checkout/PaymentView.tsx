'use client';

import { apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import {
  ANONYMOUS,
  loadTossPayments,
  type TossPaymentsWidgets,
} from '@tosspayments/tosspayments-sdk';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { SignedOutNotice } from '@/components/account/SignedOutNotice';
import {
  useDictionary,
  useFormatMoney,
  useLocale,
  useLocalePrefix,
} from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { formatDueAt } from '@/lib/checkout/order';
import {
  paymentOutcome,
  paymentPageHref,
  paymentReturnUrls,
  PAYMENTS_PATH,
  prepareFailure,
  requestFailure,
  type PaymentPrepared,
  type PrepareFailure,
} from '@/lib/checkout/payment';
import { fill } from '@/lib/i18n/format';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';

const METHODS_ID = 'payment-methods';
const AGREEMENT_ID = 'payment-agreement';

type Prepared =
  | { kind: 'loading' }
  | { kind: 'ready'; payment: PaymentPrepared }
  | { kind: 'failed'; failure: Exclude<PrepareFailure, { kind: 'signedOut' }> }
  | { kind: 'signedOut' };

type Widget =
  { kind: 'loading' } | { kind: 'ready'; widgets: TossPaymentsWidgets } | { kind: 'failed' };

/**
 * 결제 화면(#27). 결제 준비(`POST /api/v1/payments {orderId}`)로 결제 시도를 받고, READY 면 토스 결제위젯을 띄운다.
 * 결제 준비는 주문당 활성 시도 하나라 멱등 키가 없다 — 다시 불러도 같은 시도(200)거나 새 시도(201)다.
 * 위젯에는 준비 응답의 금액 · 주문명 · pgOrderId 를 그대로 넘긴다(클라이언트에서 금액을 계산하지 않는다). 고객 식별자는 넘기지 않는다(ANONYMOUS).
 * 승인 중 · 확인 중 · 완료 시도면 위젯을 띄우지 않는다 — 같은 주문을 다시 결제하면 이중 결제 위험이 있다.
 * `clientKey` 가 없으면(빌드에 결제위젯 키가 없다) 결제를 받을 수 없다고 안내한다.
 */
export function PaymentView({ orderId, clientKey }: { orderId: string; clientKey: string | null }) {
  const t = useDictionary().payment;
  const checkoutText = useDictionary().checkout;
  const locale = useLocale();
  const prefix = useLocalePrefix();
  const formatMoney = useFormatMoney();
  const cooldown = useRetryCooldown();
  const { startFrom } = cooldown;
  const [prepared, setPrepared] = useState<Prepared>({ kind: 'loading' });
  const [prepareKey, setPrepareKey] = useState(0);
  const [widget, setWidget] = useState<Widget>({ kind: 'loading' });
  const [widgetKey, setWidgetKey] = useState(0);
  const [paying, setPaying] = useState(false);
  const [notice, setNotice] = useState<{ canceled: boolean; message: string } | null>(null);
  // 상태 반영 전 두 번째 클릭까지 막는다
  const payingNow = useRef(false);
  // 위젯을 그린 시도 — StrictMode effect 이중 실행에 같은 자리에 두 번 그리지 않는다(다시 시도는 widgetKey 를 올린다)
  const renderedFor = useRef<string | null>(null);

  // 결제 준비. 상태는 응답 콜백에서만 바꾼다
  useEffect(() => {
    let alive = true;
    apiFetch<PaymentPrepared>(PAYMENTS_PATH, { method: 'POST', json: { orderId } }).then(
      (payment) => alive && setPrepared({ kind: 'ready', payment }),
      (cause: unknown) => {
        if (!alive) return;
        startFrom(cause);
        const failure = prepareFailure(cause, t);
        setPrepared(failure.kind === 'signedOut' ? failure : { kind: 'failed', failure });
      },
    );
    return () => {
      alive = false;
    };
  }, [orderId, prepareKey, startFrom, t]);

  const payment = prepared.kind === 'ready' ? prepared.payment : null;
  const ready = payment !== null && paymentOutcome(payment) === 'ready';
  const pgOrderId = ready ? payment.pgOrderId : null;
  const value = ready ? payment.amount.amount : null;
  const currency = ready ? payment.amount.currency : null;

  // 결제위젯 — 키 · READY 시도가 있을 때 한 번 그린다
  useEffect(() => {
    if (!clientKey || !pgOrderId || value === null || !currency) return;
    const attempt = `${pgOrderId}:${widgetKey}`;
    if (renderedFor.current === attempt) return;
    renderedFor.current = attempt;
    (async () => {
      const toss = await loadTossPayments(clientKey);
      const widgets = toss.widgets({ customerKey: ANONYMOUS });
      await widgets.setAmount({ currency, value });
      await Promise.all([
        widgets.renderPaymentMethods({ selector: `#${METHODS_ID}` }),
        widgets.renderAgreement({ selector: `#${AGREEMENT_ID}` }),
      ]);
      return widgets;
    })().then(
      (widgets) => setWidget({ kind: 'ready', widgets }),
      // 오류 내용(키 포함 가능)은 남기지 않는다
      () => setWidget({ kind: 'failed' }),
    );
  }, [clientKey, pgOrderId, value, currency, widgetKey]);

  if (prepared.kind === 'signedOut') {
    return <SignedOutNotice returnPath={paymentPageHref(orderId)} />;
  }
  if (prepared.kind === 'loading') {
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {t.loading}
      </p>
    );
  }
  if (prepared.kind === 'failed') {
    const { failure } = prepared;
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-danger">
          {failure.message}
        </p>
        {failure.kind === 'retry' ? (
          <Button
            variant="secondary"
            disabled={cooldown.coolingDown}
            onClick={() => {
              setPrepared({ kind: 'loading' });
              setPrepareKey((n) => n + 1);
            }}
          >
            {t.retry}
          </Button>
        ) : (
          <Link href="/cart" className="text-sm underline underline-offset-4">
            {checkoutText.backToCart}
          </Link>
        )}
      </div>
    );
  }

  const pay = async () => {
    if (payingNow.current || widget.kind !== 'ready' || !ready) return;
    payingNow.current = true;
    setPaying(true);
    setNotice(null);
    try {
      // 성공하면 결제창 인증 뒤 successUrl · failUrl 로 이동한다(리다이렉트 방식)
      await widget.widgets.requestPayment({
        orderId: payment.pgOrderId,
        orderName: payment.orderName,
        ...paymentReturnUrls(window.location.origin, prefix, payment.orderId),
      });
    } catch (cause) {
      setNotice(requestFailure(cause, t, locale));
    } finally {
      payingNow.current = false;
      setPaying(false);
    }
  };

  return (
    <PaymentSummary
      payment={prepared.payment}
      formatMoney={formatMoney}
      dueAt={formatDueAt(prepared.payment.paymentDueAt, locale)}
      t={t}
    >
      {!ready ? (
        <NotPayable outcome={paymentOutcome(prepared.payment)} t={t} />
      ) : !clientKey ? (
        <p role="alert" className="text-sm text-danger">
          {t.unavailable}
        </p>
      ) : (
        <div className="flex flex-col gap-4">
          <h2 className="text-lg">{t.methods}</h2>
          {widget.kind === 'loading' && (
            <p role="status" className="text-sm text-muted-fg">
              {t.widgetLoading}
            </p>
          )}
          {widget.kind === 'failed' ? (
            <div role="alert" className="flex flex-col items-start gap-3 text-sm">
              <p className="text-danger">{t.widgetFailed}</p>
              <Button
                variant="secondary"
                onClick={() => {
                  setWidget({ kind: 'loading' });
                  setWidgetKey((n) => n + 1);
                }}
              >
                {t.retry}
              </Button>
            </div>
          ) : (
            // 결제위젯이 이 자리에 그린다(토스 iframe). 다시 그릴 때 이전 위젯이 남지 않게 시도마다 새 자리다
            <div key={widgetKey} className="flex flex-col gap-2">
              <div id={METHODS_ID} />
              <div id={AGREEMENT_ID} />
            </div>
          )}
          <Button
            className="h-12 w-full"
            disabled={widget.kind !== 'ready' || paying}
            aria-busy={paying}
            onClick={() => void pay()}
          >
            {paying ? t.paying : t.pay}
          </Button>
          {notice && (
            <p
              role={notice.canceled ? 'status' : 'alert'}
              className={notice.canceled ? 'text-sm text-muted-fg' : 'text-sm text-danger'}
            >
              {notice.message}
            </p>
          )}
        </div>
      )}
    </PaymentSummary>
  );
}

/** 결제할 주문 요약 — 주문 번호 · 주문명 · 서버 금액 · 결제 기한 */
export function PaymentSummary({
  payment,
  formatMoney,
  dueAt,
  t,
  children,
}: {
  payment: PaymentPrepared;
  formatMoney: (money: PaymentPrepared['amount']) => string;
  dueAt: string;
  t: ReturnType<typeof useDictionary>['payment'];
  children: ReactNode;
}) {
  return (
    <div className="flex max-w-xl flex-col gap-8">
      <dl className="flex flex-col gap-2 bg-muted px-5 py-4 text-sm">
        <div className="flex justify-between gap-4">
          <dt className="text-muted-fg">{t.orderNumber}</dt>
          <dd>{payment.orderId}</dd>
        </div>
        <div className="flex justify-between gap-4">
          <dt className="text-muted-fg">{t.orderName}</dt>
          <dd className="min-w-0 text-right">{payment.orderName}</dd>
        </div>
        <div className="flex justify-between gap-4 text-base">
          <dt>{t.amount}</dt>
          <dd className="font-medium">{formatMoney(payment.amount)}</dd>
        </div>
      </dl>
      <p className="text-sm text-muted-fg">{fill(t.paymentDue, { time: dueAt })}</p>
      {children}
    </div>
  );
}

/** 준비 응답이 READY 가 아니다 — 위젯을 띄우지 않는다 */
export function NotPayable({
  outcome,
  t,
}: {
  outcome: ReturnType<typeof paymentOutcome>;
  t: ReturnType<typeof useDictionary>['payment'];
}) {
  if (outcome === 'done') {
    return (
      <p role="status" className="text-sm">
        {t.alreadyPaid}
      </p>
    );
  }
  // 승인 중 · 확인 중 · 그 밖(취소 계열 등) — 다시 결제를 두지 않는다
  return (
    <p role="alert" className="text-sm text-danger">
      {outcome === 'pending' ? t.inProgress : t.invalidOrder}
    </p>
  );
}
