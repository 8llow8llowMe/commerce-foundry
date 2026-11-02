'use client';

import { formatMoney } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import Link from 'next/link';
import { useId, useState } from 'react';
import {
  PRACTICE_CARRIERS,
  PRACTICE_STATUS_LABELS,
  type PracticeOrder,
} from '@/lib/practice/fixtures';
import { SCENARIOS, type ScenarioId, type ScenarioStep } from '@/lib/practice/scenario';
import { PRACTICE_REASON_MAX, type PracticeAction } from '@/lib/practice/transport';

const fieldClass = 'h-10 min-w-0 rounded-md border border-border bg-bg px-3';

/** 화면 맨 위 연습 표시 — 끝내기 · 처음부터 다시를 늘 같은 자리에 둔다 */
export function PracticeBanner({ onReset }: { onReset: () => void }) {
  return (
    <div
      role="note"
      aria-label="연습 모드"
      className="flex flex-col gap-3 rounded-md border-2 border-dashed border-primary bg-muted px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
    >
      <p className="text-sm">
        <span className="mr-2 rounded-sm bg-primary px-1.5 py-0.5 text-xs font-semibold text-primary-fg">
          연습 모드
        </span>
        샘플 주문만 씁니다. 실제 주문 · 결제 · 재고 · 배송 · 고객 알림에 아무것도 반영되지 않습니다.
      </p>
      <div className="flex shrink-0 items-center gap-3">
        <Button type="button" variant="secondary" onClick={onReset}>
          처음부터 다시
        </Button>
        <Link href="/" className="text-sm underline underline-offset-4">
          연습 끝내기
        </Link>
      </div>
    </div>
  );
}

export function ScenarioPicker({ onStart }: { onStart: (scenario: ScenarioId) => void }) {
  return (
    <ul className="grid gap-4 sm:grid-cols-2">
      {(Object.keys(SCENARIOS) as ScenarioId[]).map((id) => {
        const scenario = SCENARIOS[id];
        return (
          <li key={id} className="flex flex-col gap-3 rounded-md border border-border p-4">
            <h2 className="font-semibold">{scenario.title}</h2>
            <p className="text-sm text-muted-fg">{scenario.summary}</p>
            <ol className="list-decimal pl-5 text-sm">
              {scenario.steps.map((step) => (
                <li key={step.action}>{step.title}</li>
              ))}
            </ol>
            <Button type="button" className="mt-auto w-fit" onClick={() => onStart(id)}>
              이 연습 시작
            </Button>
          </li>
        );
      })}
    </ul>
  );
}

export function StepList({ steps, current }: { steps: readonly ScenarioStep[]; current: number }) {
  return (
    <ol aria-label="연습 단계" className="flex flex-wrap gap-2 text-sm">
      {steps.map((step, index) => {
        const active = index === current;
        return (
          <li
            key={step.action}
            aria-current={active ? 'step' : undefined}
            className={`rounded-md border px-2.5 py-1 ${
              active ? 'border-primary font-semibold' : 'border-border text-muted-fg'
            }`}
          >
            {index + 1}. {step.title}
            {index < current && ' (완료)'}
          </li>
        );
      })}
    </ol>
  );
}

/** 샘플 주문 요약. 금액은 fixture 값을 `formatMoney` 로 보이기만 한다 */
export function PracticeOrderCard({ order }: { order: PracticeOrder }) {
  return (
    <section
      aria-label="샘플 주문"
      className="flex flex-col gap-3 rounded-md border border-border p-4 text-sm"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <p className="font-medium">
          {order.orderName} <span className="text-muted-fg">· {order.orderId}</span>
        </p>
        <p>
          상태: <strong>{PRACTICE_STATUS_LABELS[order.status]}</strong>
        </p>
      </div>
      <ul className="flex flex-col gap-1">
        {order.items.map((item) => (
          <li key={item.productName} className="flex justify-between gap-3">
            <span>
              {item.productName} ({item.optionName}) × {item.quantity}
            </span>
            <span>{formatMoney(item.lineAmount)}</span>
          </li>
        ))}
      </ul>
      <p className="flex justify-between gap-3 border-t border-border pt-2 font-medium">
        <span>결제 금액(배송비 포함)</span>
        <span>{formatMoney(order.totalAmount)}</span>
      </p>
      <p className="text-muted-fg">받는 분 {order.recipientName}</p>
      {order.shipment && (
        <p>
          송장: {order.shipment.carrierLabel} {order.shipment.trackingNumber}
        </p>
      )}
      <div>
        <p className="text-xs font-medium text-muted-fg">상태 이력</p>
        <ol className="mt-1 flex flex-col gap-0.5">
          {order.histories.map((history, index) => (
            <li key={index}>
              {PRACTICE_STATUS_LABELS[history.status]} — {history.note}
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

/**
 * 지금 단계의 샘플 버튼. 단계마다 그 동작 하나만 보인다 — 다른 단계로 건너뛰거나 "다음" 으로 넘기는 버튼은 없다.
 * 입력 칸은 이 단계 안에서만 들고, 실패하면 그대로 둔다.
 */
type ActionProps = {
  pending: boolean;
  /** 이 단계가 실패했을 때 오류 안내 id — 입력 칸이 가리킨다 */
  errorId?: string;
  onSend: (action: PracticeAction) => void;
};

export function StepAction({ step, ...props }: ActionProps & { step: ScenarioStep }) {
  const { pending, onSend } = props;
  // 처리 중에는 aria-disabled 로만 막는다(포커스 유지). 두 번 보내기는 화면이 막는다
  const button = (label: string, action: PracticeAction) => (
    <Button
      type="button"
      aria-disabled={pending || undefined}
      onClick={() => onSend(action)}
      className="w-fit"
    >
      {label}
    </Button>
  );
  switch (step.action) {
    case 'confirm':
      return button('주문 확인', { type: 'confirm' });
    case 'prepare':
      return button('상품 준비중으로 바꾸기', { type: 'prepare' });
    case 'ship':
      return <ShipForm {...props} />;
    case 'cancel':
      return <CancelForm {...props} />;
    case 'refund-result':
      return button('환불 결과 보기', { type: 'refund-result' });
  }
}

function ShipForm({ pending, errorId, onSend }: ActionProps) {
  const id = useId();
  const [carrier, setCarrier] = useState('');
  const [trackingNumber, setTrackingNumber] = useState('');
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        onSend({ type: 'ship', carrier, trackingNumber });
      }}
      className="flex flex-col gap-3 sm:flex-row sm:items-end"
    >
      <div className="flex flex-col gap-1 sm:w-44">
        <label htmlFor={`${id}-carrier`} className="text-sm font-medium">
          택배사
        </label>
        <select
          id={`${id}-carrier`}
          value={carrier}
          aria-invalid={(errorId && !carrier) || undefined}
          aria-describedby={errorId}
          onChange={(event) => setCarrier(event.currentTarget.value)}
          className={fieldClass}
        >
          <option value="">선택</option>
          {PRACTICE_CARRIERS.map((option) => (
            <option key={option.code} value={option.code}>
              {option.label}
            </option>
          ))}
        </select>
      </div>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <label htmlFor={`${id}-tracking`} className="text-sm font-medium">
          송장 번호 (연습용)
        </label>
        <input
          id={`${id}-tracking`}
          value={trackingNumber}
          aria-invalid={(errorId && Boolean(carrier)) || undefined}
          aria-describedby={errorId}
          autoComplete="off"
          spellCheck={false}
          placeholder="예: PRACTICE-123"
          onChange={(event) => setTrackingNumber(event.currentTarget.value)}
          className={fieldClass}
        />
      </div>
      <Button type="submit" aria-disabled={pending || undefined} className="w-fit">
        송장 등록
      </Button>
    </form>
  );
}

function CancelForm({ pending, errorId, onSend }: ActionProps) {
  const id = useId();
  const [reason, setReason] = useState('');
  return (
    <form
      noValidate
      onSubmit={(event) => {
        event.preventDefault();
        onSend({ type: 'cancel', reason });
      }}
      className="flex flex-col gap-3"
    >
      <div className="flex flex-col gap-1">
        <label htmlFor={`${id}-reason`} className="text-sm font-medium">
          취소 사유 ({PRACTICE_REASON_MAX}자 이하)
        </label>
        <textarea
          id={`${id}-reason`}
          value={reason}
          rows={2}
          maxLength={PRACTICE_REASON_MAX}
          aria-invalid={errorId ? true : undefined}
          aria-describedby={errorId}
          placeholder="예: 고객이 사이즈를 잘못 골라 취소를 요청함"
          onChange={(event) => setReason(event.currentTarget.value)}
          className="min-w-0 rounded-md border border-border bg-bg px-3 py-2"
        />
      </div>
      <Button type="submit" aria-disabled={pending || undefined} className="w-fit">
        주문 취소
      </Button>
    </form>
  );
}

export function StepError({ id, message }: { id: string; message: string | null }) {
  return <FieldError id={id} messages={message ? [message] : []} />;
}
