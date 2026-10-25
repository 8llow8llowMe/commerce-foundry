import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';
import type { ShippingNotice } from './shipping-policy';
import type { CustomOrder } from './types';

/**
 * 상세 가격 바로 아래 · 모바일 하단 구매 바에 보이는 배송 안내(#60). 지금 데이터로 말할 수 있는 것만 담는다.
 * - 맞춤 제작: 상품의 제작 기간(`customOrder.leadTimeDays`)
 * - 공통: 배송비 정책(delivery API)의 기본 배송비 · 무료배송 기준
 *
 * 일반 상품의 "n일 이내 발송" 은 고객사 값이 없어 넣지 않는다. 프리오더 · 발송 예정일(BE #61)은 응답에 생기면
 * 여기서 종류를 나눠 돌려주고, 화면은 배경 톤(`bg-accent`)을 한 단계 달리해 일반 안내와 구분한다(#60 명세).
 */
export type DeliveryNote = {
  /** 첫 줄은 하단 구매 바에도 쓴다. 비어 있지 않다 */
  lines: string[];
};

export function deliveryNote(
  customOrder: Pick<CustomOrder, 'leadTimeDays'> | null,
  shipping: ShippingNotice | null,
  t: Dictionary['product']['delivery'],
): DeliveryNote | null {
  const fee = shipping
    ? [shipping.lines[0], shipping.freeShipping].filter((line): line is string => Boolean(line))
    : [];
  const leadTimeDays = customOrder?.leadTimeDays ?? null;
  if (leadTimeDays !== null) {
    return { lines: [fill(t.madeToOrder, { days: leadTimeDays }), ...fee] };
  }
  return fee.length > 0 ? { lines: fee } : null;
}
