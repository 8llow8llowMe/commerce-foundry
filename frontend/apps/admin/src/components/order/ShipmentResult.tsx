import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { orderHref } from '@/lib/order/query';
import {
  CARRIER_LABELS,
  isRetryable,
  isShipped,
  shipmentSummary,
  type ShipmentRow,
  type ShipmentRowResult,
} from '@/lib/order/shipment';

const RESULT_LABELS: Record<ShipmentRowResult, string> = {
  REGISTERED: '등록',
  ALREADY_REGISTERED: '이미 같은 송장',
  FAILED: '실패',
  UNKNOWN: '결과 모름',
  NOT_SENT: '보내지 않음',
};

/**
 * 송장 일괄 등록 결과 — 요약 한 줄과 주문별 결과(보낸 순서). 실패 이유는 서버 문구 그대로다.
 * 다시 보낼 만한 줄(결과 모름 · 보내지 않음 · `DELIVERY_017` · `018` · `020` · `021` · `022`)은 등록 표에 그대로 남아 있다.
 */
export function ShipmentResult({
  rows,
  names,
  onDismiss,
  disabled,
}: {
  rows: ShipmentRow[];
  /** 주문 아이디 → 결과 줄에 보일 이름 */
  names: Record<string, string>;
  onDismiss: () => void;
  disabled: boolean;
}) {
  const allShipped = rows.every(isShipped);
  const retryable = rows.filter(isRetryable).length;
  const final = rows.filter((row) => !isShipped(row) && !isRetryable(row)).length;

  return (
    <div className="flex flex-col gap-3 rounded-md bg-muted px-4 py-3">
      <p role="status" className={cx('text-sm font-medium', !allShipped && 'text-danger')}>
        송장 등록: {shipmentSummary(rows)}
      </p>
      <div className="relative overflow-x-auto">
        <table className="w-full min-w-[40rem] text-left text-sm">
          <caption className="sr-only">주문별 송장 등록 결과</caption>
          <thead className="text-muted-fg">
            <tr>
              <th scope="col" className="py-1 pr-4 font-medium">
                주문
              </th>
              <th scope="col" className="py-1 pr-4 font-medium">
                송장
              </th>
              <th scope="col" className="py-1 pr-4 font-medium">
                결과
              </th>
              <th scope="col" className="py-1 font-medium">
                이유
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.item.orderId} className="border-t border-border">
                <th scope="row" className="py-2 pr-4 font-normal">
                  <Link
                    href={orderHref(row.item.orderId)}
                    className="underline-offset-4 hover:underline"
                  >
                    {names[row.item.orderId] ?? row.item.orderId}
                  </Link>
                </th>
                <td className="py-2 pr-4 whitespace-nowrap">
                  {CARRIER_LABELS[row.item.carrier]} {row.item.trackingNumber}
                </td>
                <td className={cx('py-2 pr-4 whitespace-nowrap', !isShipped(row) && 'text-danger')}>
                  {RESULT_LABELS[row.result]}
                </td>
                <td className="py-2 text-muted-fg">{row.message ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {retryable > 0 && (
        <p className="text-sm text-muted-fg">
          다시 보낼 수 있는 {retryable}건은 위 등록 표에 그대로 두었습니다. 같은 송장으로 다시
          보내면 이미 반영된 주문은 그대로 둡니다.
        </p>
      )}
      {final > 0 && (
        <p className="text-sm text-muted-fg">
          없는 주문 · 출고할 수 없는 상태 · 출고 시각 거절(관리자 문의) · 다른 송장으로 이미 출고된
          주문은 다시 보내도 같은 결과라 표에서 뺐습니다. 주문 상세에서 확인해 주세요.
        </p>
      )}
      <div>
        <Button variant="secondary" onClick={onDismiss} disabled={disabled}>
          결과 닫기
        </Button>
      </div>
    </div>
  );
}
