import { Button, cx } from '@cf/ui';
import Link from 'next/link';
import { orderHref } from '@/lib/order/query';
import { bulkSummary, retryableOrderIds } from '@/lib/order/status-change';
import { ORDER_STATUS_LABELS, type BulkOutcome, type BulkResultCode } from '@/lib/order/types';

const RESULT_LABELS: Record<BulkResultCode, string> = {
  CHANGED: '바꿈',
  UNCHANGED: '이미 그 상태',
  FAILED: '실패',
};

/**
 * 일괄 변경 결과 — 요약 한 줄과 주문별 결과(요청 순서). 다시 보내면 달라질 수 있는 실패(경합 · 저장 오류)가 있으면 "실패만 다시 시도" 를 둔다.
 * 실패 이유는 서버 문구 그대로다(`ORDER_020` 등 — 단건과 같은 업무 오류).
 */
export function BulkResult({
  outcome,
  retrying,
  onRetryFailed,
  onDismiss,
}: {
  outcome: BulkOutcome;
  retrying: boolean;
  onRetryFailed: () => void;
  onDismiss: () => void;
}) {
  const { request, response, names } = outcome;
  const failed = response.failedCount > 0;
  const retryable = retryableOrderIds(response).length;

  return (
    <div className="flex flex-col gap-3 rounded-md bg-muted px-4 py-3">
      <p role="status" className={cx('text-sm font-medium', failed && 'text-danger')}>
        {ORDER_STATUS_LABELS[request.status]}(으)로 변경: {bulkSummary(response)}
      </p>
      <div className="relative overflow-x-auto">
        <table className="w-full min-w-[32rem] text-left text-sm">
          <caption className="sr-only">주문별 결과</caption>
          <thead className="text-muted-fg">
            <tr>
              <th scope="col" className="py-1 pr-4 font-medium">
                주문
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
            {response.results.map((row) => (
              <tr key={row.orderId} className="border-t border-border">
                <th scope="row" className="py-2 pr-4 font-normal">
                  <Link
                    href={orderHref(row.orderId)}
                    className="underline-offset-4 hover:underline"
                  >
                    {names[row.orderId] ?? row.orderId}
                  </Link>
                </th>
                <td
                  className={cx(
                    'py-2 pr-4 whitespace-nowrap',
                    row.result === 'FAILED' && 'text-danger',
                  )}
                >
                  {RESULT_LABELS[row.result]}
                </td>
                <td className="py-2 text-muted-fg">{row.message ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {failed && retryable < response.failedCount && (
        <p className="text-sm text-muted-fg">
          지금 상태에서 할 수 없는 주문은 다시 보내도 같은 결과라 다시 시도에서 뺍니다. 주문
          상세에서 상태를 확인해 주세요.
        </p>
      )}
      <div className="flex flex-wrap gap-3">
        {retryable > 0 && (
          <Button variant="secondary" onClick={onRetryFailed} disabled={retrying}>
            {retrying ? '다시 보내는 중…' : `실패한 ${retryable}건만 다시 시도`}
          </Button>
        )}
        <Button variant="secondary" onClick={onDismiss} disabled={retrying}>
          결과 닫기
        </Button>
      </div>
    </div>
  );
}
