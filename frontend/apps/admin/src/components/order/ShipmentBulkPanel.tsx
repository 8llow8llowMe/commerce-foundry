'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import { useId, useRef, useState } from 'react';
import { SESSION_EXPIRED_ON_SAVE, type AccessDenied } from '@/lib/api-errors';
import {
  addSelectedOrders,
  CARRIER_LABELS,
  CARRIERS,
  isCarrier,
  parseShipmentPaste,
  remainingDrafts,
  runShipmentRegistration,
  SHIPMENT_REQUEST_MAX,
  SHIPMENT_ROWS_MAX,
  TRACKING_NUMBER_MAX,
  validateDrafts,
  type CarrierCode,
  type DraftErrors,
  type ShipmentDraft,
  type ShipmentRow,
} from '@/lib/order/shipment';
import type { OrderListItem } from '@/lib/order/types';
import { ShipmentResult } from './ShipmentResult';

/**
 * 송장 일괄 등록(`POST /api/v1/admin/deliveries`). 등록 표에 줄을 넣는 길은 둘이다 — 목록에서 고른 상품 준비중 주문 넣기,
 * 엑셀에서 복사한 표(주문 아이디 · 택배사 · 송장 번호) 붙여넣기. 줄마다 택배사 · 송장 번호를 고칠 수 있다.
 * 20건씩 차례로 보내고(서버 상한), 결과는 줄마다 보인다. 보낸 뒤 표에는 다시 보낼 만한 줄만 입력값 그대로 남긴다 —
 * "다시 등록" 이 곧 실패 건만 같은 송장으로 다시 보내기다. 보내는 중에는 버튼 · 입력을 막는다(자동 재시도 없음).
 */
export function ShipmentBulkPanel({
  selected,
  onSent,
  onDenied,
}: {
  /** 목록에서 고른 주문 — 목록 순서 */
  selected: OrderListItem[];
  /** 서버가 요청을 받았을 수 있다 — 부모가 목록을 다시 읽어 배송 중 반영을 확인한다 */
  onSent: () => void;
  onDenied: (denied: AccessDenied) => void;
}) {
  const id = useId();
  const [drafts, setDrafts] = useState<ShipmentDraft[]>([]);
  const [defaultCarrier, setDefaultCarrier] = useState<CarrierCode | ''>('');
  const [pasteText, setPasteText] = useState('');
  const [rowErrors, setRowErrors] = useState<DraftErrors>({});
  const [messages, setMessages] = useState<string[]>([]);
  const [saving, setSaving] = useState(false);
  const [result, setResult] = useState<{
    rows: ShipmentRow[];
    names: Record<string, string>;
  } | null>(null);
  // 같은 렌더 안의 두 번째 클릭은 `saving` 상태가 아직 false 다 — 보내는 중은 ref 로 막는다
  const inFlight = useRef(false);

  const tooMany = (count: number) =>
    count > SHIPMENT_ROWS_MAX ? [`한 번에 ${SHIPMENT_ROWS_MAX}건까지 넣을 수 있습니다.`] : [];

  const addSelected = () => {
    const next = addSelectedOrders(drafts, selected, defaultCarrier);
    const overflow = tooMany(next.drafts.length);
    if (overflow.length > 0) {
      setMessages(overflow);
      return;
    }
    setDrafts(next.drafts);
    setMessages(
      next.notPreparing > 0
        ? [`상품 준비중이 아닌 주문 ${next.notPreparing}건은 출고할 수 없어 넣지 않았습니다.`]
        : [],
    );
  };

  const addPasted = () => {
    const parsed = parseShipmentPaste(pasteText, new Set(drafts.map((draft) => draft.orderId)));
    const next = [...drafts, ...parsed.drafts];
    const overflow = tooMany(next.length);
    if (overflow.length > 0) {
      setMessages(overflow);
      return;
    }
    setDrafts(next);
    setPasteText('');
    setMessages([
      ...(parsed.drafts.length === 0 && parsed.skipped.length === 0
        ? ['붙여넣은 줄이 없습니다.']
        : []),
      ...parsed.skipped.map((skip) => `${skip.line}번째 줄: ${skip.reason}`),
    ]);
  };

  const clearRowError = (orderId: string) =>
    setRowErrors((current) =>
      Object.fromEntries(Object.entries(current).filter(([key]) => key !== orderId)),
    );

  const update = (orderId: string, patch: Partial<ShipmentDraft>) => {
    setDrafts((current) =>
      current.map((draft) => (draft.orderId === orderId ? { ...draft, ...patch } : draft)),
    );
    clearRowError(orderId);
  };

  const submit = async () => {
    if (inFlight.current) return;
    if (drafts.length === 0) {
      setMessages(['등록할 주문을 넣어 주세요.']);
      return;
    }
    const checked = validateDrafts(drafts);
    if (!checked.ok) {
      setRowErrors(checked.errors);
      setMessages(['택배사 · 송장 번호를 확인해 주세요.']);
      return;
    }
    inFlight.current = true;
    setSaving(true);
    setRowErrors({});
    setMessages([]);
    const sentDrafts = drafts;
    const run = await runShipmentRegistration(apiFetch, checked.items);
    inFlight.current = false;
    setSaving(false);
    if (run.denied) {
      onDenied(run.denied);
      return;
    }
    setResult({
      rows: run.rows,
      names: Object.fromEntries(sentDrafts.map((draft) => [draft.orderId, draft.label])),
    });
    setDrafts(remainingDrafts(sentDrafts, run.rows));
    // 저장 중 401 — 목록을 다시 읽으면 그것도 401 이라 로그인 화면으로 넘어가 남은 표가 사라진다.
    // 앞 요청 일부가 등록됐어도 다시 읽지 않는다(결과 표에 보인다). 다시 로그인한 뒤 같은 표로 다시 보낸다
    if (run.sessionExpired) {
      setMessages([SESSION_EXPIRED_ON_SAVE]);
      return;
    }
    onSent();
  };

  const requests = Math.ceil(drafts.length / SHIPMENT_REQUEST_MAX);

  return (
    <section
      aria-labelledby={`${id}-title`}
      data-tour="order-shipment-bulk"
      className="flex flex-col gap-4 rounded-md border border-border p-4"
    >
      <div className="flex flex-col gap-1">
        <h2 id={`${id}-title`} className="font-medium">
          송장 일괄 등록 (출고)
        </h2>
        <p className="text-sm text-muted-fg">
          상품 준비중 주문에 송장을 등록하면 배송 중으로 바뀝니다. 표에서 고른 주문을 넣거나
          엑셀에서 복사한 표를 붙여넣습니다. 출고된 송장은 바꿀 수 없습니다.
        </p>
      </div>

      <div className="flex flex-col gap-4 sm:flex-row sm:items-end">
        <div className="flex min-w-0 flex-col gap-1 sm:w-44">
          <label htmlFor={`${id}-default-carrier`} className="text-sm font-medium">
            넣을 때 택배사
          </label>
          <select
            id={`${id}-default-carrier`}
            value={defaultCarrier}
            disabled={saving}
            onChange={(event) => {
              const value = event.currentTarget.value;
              setDefaultCarrier(isCarrier(value) ? value : '');
            }}
            className="h-10 min-w-0 rounded-md border border-border bg-bg px-3"
          >
            <option value="">선택 안 함</option>
            {CARRIERS.map((code) => (
              <option key={code} value={code}>
                {CARRIER_LABELS[code]}
              </option>
            ))}
          </select>
        </div>
        <Button
          variant="secondary"
          onClick={addSelected}
          disabled={saving || selected.length === 0}
        >
          {selected.length > 0 ? `고른 주문 ${selected.length}건 넣기` : '표에서 주문을 고르세요'}
        </Button>
      </div>

      <div className="flex flex-col gap-1">
        <label htmlFor={`${id}-paste`} className="text-sm font-medium">
          엑셀에서 붙여넣기 (선택)
        </label>
        <textarea
          id={`${id}-paste`}
          value={pasteText}
          rows={3}
          disabled={saving}
          spellCheck={false}
          aria-describedby={`${id}-paste-hint`}
          onChange={(event) => setPasteText(event.currentTarget.value)}
          className="min-w-0 rounded-md border border-border bg-bg px-3 py-2 font-mono text-sm"
        />
        <p id={`${id}-paste-hint`} className="text-xs text-muted-fg">
          열 순서: 주문 아이디 · 택배사(이름 또는 코드) · 송장 번호. 엑셀 칸을 복사하거나 쉼표로
          구분합니다. 머리글 줄은 건너뜁니다. 주문 아이디 열은 엑셀에서 텍스트 서식으로 두세요 —
          숫자 서식이면 19자리 아이디의 끝자리가 0으로 바뀌어 다른 주문이 됩니다.
        </p>
        <div>
          <Button
            variant="secondary"
            onClick={addPasted}
            disabled={saving || pasteText.trim() === ''}
          >
            표에 넣기
          </Button>
        </div>
      </div>

      {drafts.length > 0 && (
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[40rem] text-left text-sm">
            <caption className="sr-only">등록할 송장</caption>
            <thead className="text-muted-fg">
              <tr>
                <th scope="col" className="py-1 pr-4 font-medium">
                  주문
                </th>
                <th scope="col" className="py-1 pr-4 font-medium">
                  택배사
                </th>
                <th scope="col" className="py-1 pr-4 font-medium">
                  송장 번호
                </th>
                <th scope="col" className="py-1 font-medium">
                  <span className="sr-only">빼기</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {drafts.map((draft) => {
                const errors = rowErrors[draft.orderId] ?? {};
                const rowId = `${id}-${draft.orderId}`;
                return (
                  <tr key={draft.orderId} className="border-t border-border align-top">
                    <th scope="row" className="max-w-[16rem] py-2 pr-4 font-normal">
                      <span className="line-clamp-2">{draft.label}</span>
                      {draft.label !== draft.orderId && (
                        <span className="block text-xs text-muted-fg">{draft.orderId}</span>
                      )}
                    </th>
                    <td className="py-2 pr-4">
                      <select
                        value={draft.carrier}
                        disabled={saving}
                        aria-label={`${draft.label} 택배사`}
                        aria-invalid={errors.carrier ? true : undefined}
                        aria-describedby={errors.carrier ? `${rowId}-carrier-error` : undefined}
                        onChange={(event) => {
                          const value = event.currentTarget.value;
                          update(draft.orderId, { carrier: isCarrier(value) ? value : '' });
                        }}
                        className="h-9 min-w-0 rounded-md border border-border bg-bg px-2"
                      >
                        <option value="">선택</option>
                        {CARRIERS.map((code) => (
                          <option key={code} value={code}>
                            {CARRIER_LABELS[code]}
                          </option>
                        ))}
                      </select>
                      <FieldError
                        id={`${rowId}-carrier-error`}
                        messages={errors.carrier ? [errors.carrier] : []}
                      />
                    </td>
                    <td className="py-2 pr-4">
                      <input
                        value={draft.trackingText}
                        maxLength={TRACKING_NUMBER_MAX + 10}
                        disabled={saving}
                        autoComplete="off"
                        spellCheck={false}
                        aria-label={`${draft.label} 송장 번호`}
                        aria-invalid={errors.trackingNumber ? true : undefined}
                        aria-describedby={
                          errors.trackingNumber ? `${rowId}-tracking-error` : undefined
                        }
                        onChange={(event) =>
                          update(draft.orderId, { trackingText: event.currentTarget.value })
                        }
                        className="h-9 w-full min-w-0 rounded-md border border-border bg-bg px-2"
                      />
                      <FieldError
                        id={`${rowId}-tracking-error`}
                        messages={errors.trackingNumber ? [errors.trackingNumber] : []}
                      />
                    </td>
                    <td className="py-2">
                      <Button
                        variant="secondary"
                        disabled={saving}
                        aria-label={`${draft.label} 빼기`}
                        onClick={() => {
                          setDrafts((current) =>
                            current.filter((item) => item.orderId !== draft.orderId),
                          );
                          clearRowError(draft.orderId);
                        }}
                      >
                        빼기
                      </Button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      <FieldError messages={messages} />

      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={() => void submit()} disabled={saving || drafts.length === 0}>
          {saving ? '등록하는 중…' : `송장 ${drafts.length}건 등록`}
        </Button>
        {drafts.length > 0 && (
          <Button
            variant="secondary"
            disabled={saving}
            onClick={() => {
              setDrafts([]);
              setRowErrors({});
              setMessages([]);
            }}
          >
            표 비우기
          </Button>
        )}
        {requests > 1 && (
          <span className="text-xs text-muted-fg">
            {SHIPMENT_REQUEST_MAX}건씩 {requests}번에 나눠 보냅니다.
          </span>
        )}
      </div>

      {result && (
        <ShipmentResult
          rows={result.rows}
          names={result.names}
          disabled={saving}
          onDismiss={() => setResult(null)}
        />
      )}
    </section>
  );
}
