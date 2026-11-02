import { formatMoney } from '@cf/api-client';
import type { OrderDetail } from '@/lib/order/types';

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});
const at = (iso: string) => dateTime.format(new Date(iso));

/**
 * 어드민 주문 상세 본문(#30) — 항목 · 금액 · 배송지 · 상태 이력. 서버가 준 값 그대로 표시만 한다.
 * 배송지의 받는 분 · 연락처 · 상세 주소 · 요청 사항은 서버가 가린 값이다. 맞춤 제작 입력값은 제작에 필요해 그대로 온다.
 * 결제 · 송장 요약은 BE 가 열리면(#14 · #18) 여기에 더한다.
 */
export function OrderDetailView({ order }: { order: OrderDetail }) {
  const address = order.shippingAddress;
  const region = [address.city, address.stateProvince].filter(Boolean).join(', ');

  return (
    <div className="flex flex-col gap-8">
      <section aria-labelledby="order-items" className="flex flex-col gap-3">
        <h2 id="order-items" className="font-medium">
          주문 항목
        </h2>
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[40rem] text-left text-sm">
            <thead className="text-muted-fg">
              <tr>
                <th scope="col" className="py-2 pr-4 font-medium">
                  상품
                </th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">
                  단가
                </th>
                <th scope="col" className="py-2 pr-4 text-right font-medium">
                  수량
                </th>
                <th scope="col" className="py-2 text-right font-medium">
                  금액
                </th>
              </tr>
            </thead>
            <tbody>
              {order.items.map((item) => (
                <tr key={item.skuId} className="border-t border-border align-top">
                  <th scope="row" className="py-3 pr-4 font-normal">
                    <span className="font-medium">{item.productName}</span>
                    {item.optionName && (
                      <span className="block text-muted-fg">{item.optionName}</span>
                    )}
                    {item.customOrder && (
                      <span className="mt-1 block text-xs text-muted-fg">
                        맞춤 제작
                        {item.customOptions.length > 0 &&
                          ` · ${item.customOptions.map((option) => `${option.fieldKey} ${option.value}`).join(' · ')}`}
                      </span>
                    )}
                  </th>
                  <td className="py-3 pr-4 text-right whitespace-nowrap">
                    {formatMoney(item.unitPrice)}
                  </td>
                  <td className="py-3 pr-4 text-right">{item.quantity}</td>
                  <td className="py-3 text-right whitespace-nowrap">
                    {formatMoney(item.lineAmount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <dl className="ml-auto grid w-full max-w-xs grid-cols-[1fr_auto] gap-x-6 gap-y-1 text-sm">
          <dt className="text-muted-fg">상품 합계</dt>
          <dd className="text-right">{formatMoney(order.amounts.itemsAmount)}</dd>
          <dt className="text-muted-fg">배송비</dt>
          <dd className="text-right">{formatMoney(order.amounts.shippingFee)}</dd>
          <dt className="font-medium">결제 금액</dt>
          <dd className="text-right font-medium">{formatMoney(order.amounts.totalAmount)}</dd>
        </dl>
      </section>

      <section aria-labelledby="order-address" className="flex flex-col gap-3">
        <h2 id="order-address" className="font-medium">
          배송지
        </h2>
        <dl className="grid grid-cols-[6rem_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-fg">받는 분</dt>
          <dd>{address.recipientName}</dd>
          <dt className="text-muted-fg">연락처</dt>
          <dd>{address.recipientPhone}</dd>
          <dt className="text-muted-fg">주소</dt>
          <dd>
            {address.postalCode && `(${address.postalCode}) `}
            {address.addressLine1}
            {address.addressLine2 && ` ${address.addressLine2}`}
            {region && `, ${region}`} · {address.countryCode}
          </dd>
          <dt className="text-muted-fg">요청 사항</dt>
          <dd>{address.deliveryMemo ?? '—'}</dd>
        </dl>
      </section>

      <section
        aria-labelledby="order-histories"
        data-tour="order-histories"
        className="flex flex-col gap-3"
      >
        <h2 id="order-histories" className="font-medium">
          상태 이력
        </h2>
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[40rem] text-left text-sm">
            <thead className="text-muted-fg">
              <tr>
                <th scope="col" className="py-2 pr-4 font-medium">
                  시각
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  상태
                </th>
                <th scope="col" className="py-2 pr-4 font-medium">
                  바꾼 쪽
                </th>
                <th scope="col" className="py-2 font-medium">
                  사유
                </th>
              </tr>
            </thead>
            <tbody>
              {order.histories.map((history, index) => (
                <tr key={`${history.changedAt}-${index}`} className="border-t border-border">
                  <td className="py-2 pr-4 whitespace-nowrap text-muted-fg">
                    {at(history.changedAt)}
                  </td>
                  <td className="py-2 pr-4 whitespace-nowrap">
                    {history.fromStatus
                      ? `${history.fromStatus.name} → ${history.toStatus.name}`
                      : `${history.toStatus.name}(주문 접수)`}
                  </td>
                  <td className="py-2 pr-4 whitespace-nowrap" title={history.changedBy.description}>
                    {history.changedBy.name}
                  </td>
                  <td className="py-2">{history.reason ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
