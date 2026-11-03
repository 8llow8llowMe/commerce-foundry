import { formatMoney } from '@cf/api-client';
import Link from 'next/link';
import { orderHref } from '@/lib/order/query';
import type { PaymentListItem } from '@/lib/payment/types';

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

const formatTime = (value: string | null) => (value ? dateTime.format(new Date(value)) : '—');

/**
 * 주문 회신 표시 — 성공 회신을 받았으면 "수신", 거절 코드가 있으면 "거절(코드)", 둘 다 아니면 "미수신".
 * 미수신이 곧 복구 대상은 아니다(BE PR #484) — 화면은 사실만 보인다.
 */
export function orderSyncLabel(payment: PaymentListItem): string {
  if (payment.orderSynced) return '수신';
  return payment.orderSyncFailureCode ? `거절(${payment.orderSyncFailureCode})` : '미수신';
}

/**
 * 결제 표 — 생성 시각 · 결제 아이디 · 주문(상세 링크) · 상태(+ 미확정 사유) · 수단 · 원금 · 주문 회신 · 승인 시작/완료 시각.
 * 금액은 저장된 결제 시도 원금을 `formatMoney` 로 표시만 한다. 상태 · 사유 · 수단은 서버 `name`(설명은 `title`)이다.
 * 결제키 · 개인정보는 서버가 내려 주지 않는다. `data-tour` 는 온보딩 투어(#228 `payments-v1`)의 앵커다.
 */
export function PaymentTable({ payments }: { payments: PaymentListItem[] }) {
  return (
    <div data-tour="payment-table" className="relative overflow-x-auto">
      <table className="w-full min-w-[60rem] text-left text-sm">
        <caption className="sr-only">결제 목록</caption>
        <thead className="text-muted-fg">
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">
              생성 시각
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              결제
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              주문
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              상태
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              수단
            </th>
            <th scope="col" className="py-2 pr-4 text-right font-medium">
              결제 원금
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              주문 회신
            </th>
            <th scope="col" className="py-2 font-medium">
              승인 시작 · 완료
            </th>
          </tr>
        </thead>
        <tbody>
          {payments.map((payment) => (
            <tr key={payment.paymentId} className="border-t border-border align-top">
              <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                {formatTime(payment.createdAt)}
              </td>
              <th scope="row" className="py-3 pr-4 font-normal whitespace-nowrap">
                {payment.paymentId}
              </th>
              <td className="py-3 pr-4 whitespace-nowrap">
                <Link
                  href={orderHref(payment.orderId)}
                  className="underline-offset-4 hover:underline"
                >
                  {payment.orderId}
                </Link>
              </td>
              <td className="py-3 pr-4">
                <span className="whitespace-nowrap" title={payment.status.description}>
                  {payment.status.name}
                </span>
                {payment.unknownReason && (
                  <span
                    className="block text-xs text-muted-fg"
                    title={payment.unknownReason.description}
                  >
                    사유: {payment.unknownReason.name}
                  </span>
                )}
              </td>
              <td className="py-3 pr-4 whitespace-nowrap" title={payment.method?.description}>
                {payment.method?.name ?? '—'}
              </td>
              <td className="py-3 pr-4 text-right whitespace-nowrap">
                {formatMoney(payment.amount)}
              </td>
              <td className="py-3 pr-4 whitespace-nowrap">{orderSyncLabel(payment)}</td>
              <td className="py-3 whitespace-nowrap text-muted-fg">
                {formatTime(payment.approvalStartedAt)}
                <span className="block">{formatTime(payment.approvedAt)}</span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
