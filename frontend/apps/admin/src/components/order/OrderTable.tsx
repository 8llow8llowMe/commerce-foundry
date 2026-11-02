import { formatMoney } from '@cf/api-client';
import Link from 'next/link';
import { orderHref } from '@/lib/order/query';
import type { OrderListItem } from '@/lib/order/types';

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 주문 표 — 고르기(일괄 변경) · 주문 시각 · 주문 이름(상세 링크) · 상태 · 받는 분(마스킹) · 결제 금액.
 * 금액은 서버 값을 `formatMoney` 로 표시만 한다. 상태는 서버 `name`(설명은 `title`)이다.
 */
export function OrderTable({
  orders,
  disabled = false,
  selected,
  onToggle,
  onToggleAll,
}: {
  orders: OrderListItem[];
  /** 고르기를 막는다(목록을 다시 읽는 중) */
  disabled?: boolean;
  selected: ReadonlySet<string>;
  onToggle: (orderId: string) => void;
  onToggleAll: (checked: boolean) => void;
}) {
  const allChecked = orders.length > 0 && orders.every((order) => selected.has(order.orderId));

  return (
    // sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다
    <div data-tour="order-table" className="relative overflow-x-auto">
      <table className="w-full min-w-[52rem] text-left text-sm">
        <caption className="sr-only">주문 목록</caption>
        <thead className="text-muted-fg">
          <tr>
            <th scope="col" className="w-10 py-2 pr-2 font-medium">
              <input
                type="checkbox"
                aria-label="이 페이지 주문 모두 고르기"
                checked={allChecked}
                disabled={disabled}
                onChange={(event) => onToggleAll(event.currentTarget.checked)}
                className="size-4 cursor-pointer accent-primary"
              />
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              주문 시각
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              주문
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              상태
            </th>
            <th scope="col" className="py-2 pr-4 font-medium">
              받는 분
            </th>
            <th scope="col" className="py-2 text-right font-medium">
              결제 금액
            </th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => (
            <tr key={order.orderId} className="border-t border-border">
              <td className="py-3 pr-2">
                <input
                  type="checkbox"
                  aria-label={`${order.orderName} 고르기`}
                  checked={selected.has(order.orderId)}
                  disabled={disabled}
                  onChange={() => onToggle(order.orderId)}
                  className="size-4 cursor-pointer accent-primary"
                />
              </td>
              <td className="py-3 pr-4 whitespace-nowrap text-muted-fg">
                {dateTime.format(new Date(order.createdAt))}
              </td>
              <th scope="row" className="max-w-[20rem] py-3 pr-4 font-medium">
                <Link
                  href={orderHref(order.orderId)}
                  className="line-clamp-2 underline-offset-4 hover:underline"
                >
                  {order.orderName}
                </Link>
                <span className="block text-xs font-normal text-muted-fg">{order.orderId}</span>
              </th>
              <td className="py-3 pr-4 whitespace-nowrap" title={order.status.description}>
                {order.status.name}
              </td>
              <td className="py-3 pr-4 whitespace-nowrap">{order.recipientName}</td>
              <td className="py-3 text-right whitespace-nowrap">
                {formatMoney({ amount: order.totalAmount, currency: order.currency })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
