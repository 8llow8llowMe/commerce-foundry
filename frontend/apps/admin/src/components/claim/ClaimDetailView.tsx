import Link from 'next/link';
import type { ClaimDetail } from '@/lib/claim/types';
import { orderHref } from '@/lib/order/query';
import { formatClaimTime, refundLabel } from './ClaimTable';

/**
 * 클레임 상세 본문 — 헤더(유형 · 상태 · 주문 링크 · 접수 시각 · 예상 환불액)와 접수 항목(주문 항목 · 수량 · 사유).
 * 상품 이름은 응답에 없어 주문 상세에서 확인하게 한다. 금액은 서버 값 그대로다.
 */
export function ClaimDetailView({ claim }: { claim: ClaimDetail }) {
  return (
    <>
      <section
        aria-labelledby="claim-summary"
        className="flex flex-col gap-3 rounded-md border border-border p-4"
      >
        <h2 id="claim-summary" className="font-medium">
          <span title={claim.type.description}>{claim.type.name}</span> ·{' '}
          <span title={claim.status.description}>{claim.status.name}</span>
        </h2>
        <p className="text-sm text-muted-fg">{claim.status.description}</p>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-muted-fg">주문</dt>
          <dd>
            <Link href={orderHref(claim.orderId)} className="underline underline-offset-4">
              {claim.orderId}
            </Link>
          </dd>
          <dt className="text-muted-fg">접수 시각</dt>
          <dd>{formatClaimTime(claim.requestedAt)}</dd>
          <dt className="text-muted-fg">예상 환불액</dt>
          <dd>{refundLabel(claim.expectedRefund)}</dd>
        </dl>
      </section>

      <section aria-labelledby="claim-items" className="flex flex-col gap-3">
        <h2 id="claim-items" className="font-medium">
          접수 항목
        </h2>
        <p className="text-sm text-muted-fg">
          상품 이름 · 옵션은 주문 상세의 주문 항목에서 확인해 주세요.
        </p>
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[32rem] text-left text-sm">
            <caption className="sr-only">접수 항목</caption>
            <thead className="text-muted-fg">
              <tr>
                <th scope="col" className="py-2 pr-4 font-medium">
                  주문 항목
                </th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">
                  수량
                </th>
                <th scope="col" className="py-2 font-medium">
                  사유
                </th>
              </tr>
            </thead>
            <tbody>
              {claim.items.map((item) => (
                <tr key={item.orderItemId} className="border-t border-border">
                  <th scope="row" className="py-3 pr-4 font-normal whitespace-nowrap">
                    {item.orderItemId}
                  </th>
                  <td className="py-3 pr-4 text-right">{item.quantity}</td>
                  <td className="py-3" title={item.reason.description}>
                    {item.reason.name}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </>
  );
}
