'use client';

import { ApiError, apiFetch, createIdempotencyKey, type Money } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useEffect, useRef, useState } from 'react';
import { SignedOutNotice } from '@/components/account/SignedOutNotice';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import {
  confirmFailure,
  confirmKeyFor,
  methodLabel,
  PAYMENT_CONFIRM_PATH,
  paymentOutcome,
  paymentPageHref,
  PAYMENTS_PATH,
  POLL_DELAYS_SEC,
  sessionKeyStorage,
  type ConfirmFailure,
  type PaymentConfirmRequest,
  type PaymentResponse,
  type SuccessReturn,
} from '@/lib/checkout/payment';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { cooldownMsForSec } from '@/lib/retry-cooldown';

export type ResultView =
  | { kind: 'invalid' }
  | { kind: 'confirming' }
  | { kind: 'done'; payment: PaymentResponse }
  | { kind: 'failed'; payment: PaymentResponse }
  /** 승인 중 · 결과 미확정 — 상태 조회 중. exhausted 면 조회 간격을 다 돌았다 */
  | { kind: 'pending'; payment: PaymentResponse; exhausted: boolean }
  | { kind: 'error'; failure: Exclude<ConfirmFailure, { kind: 'signedOut' }> }
  | { kind: 'signedOut' };

type Poll = { paymentId: string; step: number };

/**
 * 결제 성공 복귀 화면(#27) — 토스가 `?paymentKey&orderId(=pgOrderId)&amount` 를 붙여 보낸다. 서버가 쿼리를 걸러 `result` 로 넘긴다(어긋나면 null).
 * 승인(`POST /api/v1/payments/confirm`)은 결제 시도마다 이 탭에 둔 같은 `Idempotency-Key` 로 보낸다 — 새로 고치거나 다시 확인해도
 * 서버가 처음 결과(현재 상태)를 돌려준다. StrictMode effect 이중 실행은 ref 로 막아 한 번만 보낸다. 자동으로 다시 보내지 않는다.
 * 결과 미확정(UNKNOWN · IN_PROGRESS)이면 상태 조회(GET)를 몇 번 하고, 그래도 모르면 "다시 확인" 만 둔다 — 다시 결제는 이중 결제 위험이라 두지 않는다.
 * 결제 완료 뒤 장바구니는 비우지 않는다(결제 완료 → 주문 반영은 BE #308 전이다).
 */
export function PaymentResult({
  result,
  returnPath,
}: {
  result: SuccessReturn | null;
  /** 로그인 뒤 돌아올 앱 경로(이 화면 · 같은 쿼리) */
  returnPath: string;
}) {
  const t = useDictionary().payment;
  const formatMoney = useFormatMoney();
  const [view, setView] = useState<ResultView>(
    result ? { kind: 'confirming' } : { kind: 'invalid' },
  );
  const [confirmRun, setConfirmRun] = useState(0);
  const [poll, setPoll] = useState<Poll | null>(null);
  const [blocked, setBlocked] = useState<number | null>(null);
  // 보낸 승인 시도(confirmRun) — 같은 시도를 두 번 보내지 않는다
  const sent = useRef<number | null>(null);
  const body = result ? JSON.stringify(result.confirm) : null;

  // 결과를 화면으로. 미확정이면 다음 조회를 잡는다
  const showRef = useRef((payment: PaymentResponse, nextStep: number) => {
    const outcome = paymentOutcome(payment);
    if (outcome === 'done') {
      setPoll(null);
      setView({ kind: 'done', payment });
    } else if (outcome === 'failed') {
      setPoll(null);
      setView({ kind: 'failed', payment });
    } else if (outcome === 'pending') {
      setPoll({ paymentId: payment.paymentId, step: nextStep });
      setView({ kind: 'pending', payment, exhausted: nextStep >= POLL_DELAYS_SEC.length });
    } else {
      setPoll(null);
      setView({
        kind: 'error',
        failure: { kind: 'final', message: t.invalidReturn, cartLink: false },
      });
    }
  });

  // 승인 — 같은 탭 · 같은 결제 시도는 같은 키
  useEffect(() => {
    if (!body || sent.current === confirmRun) return;
    sent.current = confirmRun;
    const confirm = JSON.parse(body) as PaymentConfirmRequest;
    const idempotencyKey = confirmKeyFor(
      confirm.pgOrderId,
      sessionKeyStorage(),
      createIdempotencyKey,
    );
    apiFetch<PaymentResponse>(PAYMENT_CONFIRM_PATH, {
      method: 'POST',
      json: confirm,
      idempotencyKey,
    }).then(
      (payment) => showRef.current(payment, 0),
      (cause: unknown) => {
        const failure = confirmFailure(cause, t);
        if (failure.kind === 'signedOut') {
          setView(failure);
          return;
        }
        if (
          failure.kind === 'recheck' &&
          (failure.retryAfterSec !== null || isRateLimited(cause))
        ) {
          setBlocked(Date.now() + cooldownMsForSec(failure.retryAfterSec));
        }
        setView({ kind: 'error', failure });
      },
    );
  }, [body, confirmRun, t]);

  // 미확정 결제 상태 조회(GET — 부수 효과 없음). 간격을 다 돌면 멈춘다
  useEffect(() => {
    if (!poll) return;
    const delay = POLL_DELAYS_SEC[poll.step];
    if (delay === undefined) return;
    const timer = setTimeout(() => {
      apiFetch<PaymentResponse>(`${PAYMENTS_PATH}/${encodeURIComponent(poll.paymentId)}`).then(
        (payment) => showRef.current(payment, poll.step + 1),
        (cause: unknown) => {
          if (cause instanceof ApiError && cause.kind === 'unauthorized') {
            setPoll(null);
            setView({ kind: 'signedOut' });
            return;
          }
          // 일시 장애는 다음 간격에 다시 본다
          const next = poll.step + 1;
          setPoll({ paymentId: poll.paymentId, step: next });
          setView((current) =>
            current.kind === 'pending'
              ? { ...current, exhausted: next >= POLL_DELAYS_SEC.length }
              : current,
          );
        },
      );
    }, delay * 1000);
    return () => clearTimeout(timer);
  }, [poll]);

  // 다시 확인 버튼 쿨다운(429 · `PAYMENT_010`)
  useEffect(() => {
    if (blocked === null) return;
    const timer = setTimeout(() => setBlocked(null), Math.max(0, blocked - Date.now()));
    return () => clearTimeout(timer);
  }, [blocked]);

  if (view.kind === 'signedOut') return <SignedOutNotice returnPath={returnPath} />;
  return (
    <PaymentResultView
      view={view}
      t={t}
      formatMoney={formatMoney}
      repayOrderId={result?.orderId ?? null}
      recheckBlocked={blocked !== null}
      onRecheck={() => {
        setView({ kind: 'confirming' });
        setConfirmRun((n) => n + 1);
      }}
      onPollAgain={(paymentId) => {
        setView((current) =>
          current.kind === 'pending' ? { ...current, exhausted: false } : current,
        );
        setPoll({ paymentId, step: 0 });
      }}
    />
  );
}

const isRateLimited = (cause: unknown) => cause instanceof ApiError && cause.kind === 'rateLimited';

/** 결과 화면 — 상태(view)만 보고 그린다 */
export function PaymentResultView({
  view,
  t,
  formatMoney,
  repayOrderId,
  recheckBlocked,
  onRecheck,
  onPollAgain,
}: {
  view: Exclude<ResultView, { kind: 'signedOut' }>;
  t: Dictionary['payment'];
  formatMoney: (money: Money) => string;
  /** 다시 결제할 주문(복귀 쿼리의 order). 없으면 장바구니로 */
  repayOrderId: string | null;
  recheckBlocked: boolean;
  onRecheck: () => void;
  onPollAgain: (paymentId: string) => void;
}) {
  const box = 'flex flex-col items-center gap-4 py-16 text-center';
  const link = 'text-sm underline underline-offset-4';
  const cartLink = (
    <Link href="/cart" className={link}>
      {useDictionary().checkout.backToCart}
    </Link>
  );

  switch (view.kind) {
    case 'invalid':
      return (
        <div className={box}>
          <p role="alert" className="text-sm text-danger">
            {t.invalidReturn}
          </p>
          {cartLink}
        </div>
      );
    case 'confirming':
      return (
        <p role="status" className="py-16 text-center text-sm text-muted-fg">
          {t.confirming}
        </p>
      );
    case 'done':
      return (
        <section aria-labelledby="payment-done" className="flex max-w-xl flex-col gap-8">
          <h2 id="payment-done" role="status" className="text-xl">
            {t.done}
          </h2>
          <PaymentDetails payment={view.payment} t={t} formatMoney={formatMoney} />
        </section>
      );
    case 'failed':
      return (
        <div className={box}>
          <p role="alert" className="text-sm text-danger">
            {t.failed}
          </p>
          <Link href={paymentPageHref(view.payment.orderId)} className={link}>
            {t.payAgain}
          </Link>
        </div>
      );
    case 'pending':
      return (
        <div className={box}>
          <p role={view.exhausted ? 'alert' : 'status'} className="text-sm text-muted-fg">
            {view.exhausted ? t.pendingLong : t.pending}
          </p>
          {view.exhausted && (
            <Button variant="secondary" onClick={() => onPollAgain(view.payment.paymentId)}>
              {t.checkAgain}
            </Button>
          )}
        </div>
      );
    case 'error': {
      const { failure } = view;
      return (
        <div className={box}>
          <p role="alert" className="text-sm text-danger">
            {failure.message}
          </p>
          {failure.kind === 'recheck' && (
            <Button variant="secondary" disabled={recheckBlocked} onClick={onRecheck}>
              {t.checkAgain}
            </Button>
          )}
          {failure.kind === 'repay' &&
            (repayOrderId ? (
              <Link href={paymentPageHref(repayOrderId)} className={link}>
                {t.payAgain}
              </Link>
            ) : (
              cartLink
            ))}
          {failure.kind === 'final' && failure.cartLink && cartLink}
        </div>
      );
    }
  }
}

/** 결제 완료 내용 — 서버 값 그대로 */
function PaymentDetails({
  payment,
  t,
  formatMoney,
}: {
  payment: PaymentResponse;
  t: Dictionary['payment'];
  formatMoney: (money: Money) => string;
}) {
  const method = methodLabel(payment);
  return (
    <dl className="flex flex-col gap-2 bg-muted px-5 py-4 text-sm">
      <div className="flex justify-between gap-4">
        <dt className="text-muted-fg">{t.orderNumber}</dt>
        <dd>{payment.orderId}</dd>
      </div>
      <div className="flex justify-between gap-4">
        <dt className="text-muted-fg">{t.orderName}</dt>
        <dd className="min-w-0 text-right">{payment.orderName}</dd>
      </div>
      {method && (
        <div className="flex justify-between gap-4">
          <dt className="text-muted-fg">{t.methods}</dt>
          <dd>{method}</dd>
        </div>
      )}
      <div className="flex justify-between gap-4 text-base">
        <dt>{t.amount}</dt>
        <dd className="font-medium">{formatMoney(payment.amount)}</dd>
      </div>
    </dl>
  );
}
