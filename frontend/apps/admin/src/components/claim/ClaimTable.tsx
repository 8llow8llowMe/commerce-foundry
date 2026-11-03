import { formatMoney, type Money } from '@cf/api-client';
import Link from 'next/link';
import { claimHref } from '@/lib/claim/query';
import type { ClaimListItem } from '@/lib/claim/types';
import { orderHref } from '@/lib/order/query';

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

export const formatClaimTime = (value: string) => dateTime.format(new Date(value));

/** 예상 환불액 — null 은 미산정(0원과 다르다). 서버 값을 `formatMoney` 로 표시만 한다 */
export const refundLabel = (money: Money | null) => (money ? formatMoney(money) : '미산정');

/**
 * 클레임 표 — 접수 시각 · 클레임(상세 링크) · 주문(상세 링크) · 유형 · 상태 · 예상 환불액.
 * 유형 · 상태는 서버 `name`(설명은 `title`)이다. 금액은 계산하지 않는다.
 */
export function ClaimTable({ claims }: { claims: ClaimListItem[] }) {
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[48rem] text-left text-sm">
        <caption className="sr-only">클레임 목록</caption>
        <thead className="text-muted-fg">
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">
              접수 시각
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              클레임
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              주문
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              유형
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              상태
            </th>
            <th scope="col" className="py-2 text-right font-medium">
              예상 환불액
            </th>
          </tr>
        </thead>
        <tbody>
          {claims.map((claim) => (
            <tr key={claim.claimId} className="border-t border-border align-top">
              <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                {formatClaimTime(claim.requestedAt)}
              </td>
              <th scope="row" className="py-3 pr-4 font-normal whitespace-nowrap">
                <Link
                  href={claimHref(claim.claimId)}
                  className="underline-offset-4 hover:underline"
                >
                  {claim.claimId}
                </Link>
              </th>
              <td className="py-3 pr-4 whitespace-nowrap">
                <Link
                  href={orderHref(claim.orderId)}
                  className="underline-offset-4 hover:underline"
                >
                  {claim.orderId}
                </Link>
              </td>
              <td className="py-3 pr-4 whitespace-nowrap" title={claim.type.description}>
                {claim.type.name}
              </td>
              <td className="py-3 pr-4 whitespace-nowrap" title={claim.status.description}>
                {claim.status.name}
              </td>
              <td className="py-3 text-right whitespace-nowrap">
                {refundLabel(claim.expectedRefund)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
