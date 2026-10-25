import type { ShippingNotice } from './shipping-policy';

/**
 * 상세 가격 바로 아래 · 모바일 하단 구매 바 · 구매 바텀시트에 보이는 배송 안내(#60). 지금 데이터로 말할 수 있는 것만
 * 담는다 — 프리오더 발송 예정(상품 상세 `preorder`, BE #61)과 배송비 정책(delivery API)의 기본 배송비 · 무료배송 기준.
 * 도서산간 · 관세 줄은 길어서 배송 아코디언에만 둔다.
 *
 * 프리오더 줄은 화면이 배경 톤(`bg-accent`)을 한 단계 달리해 일반 배송 안내와 구분한다(#60 명세). 프리오더여도 배송비 줄은
 * 그대로 보인다. 맞춤 제작 기간은 구매 패널의 맞춤 제작 안내(`CustomOrderFields`)가 이미 보여서 여기 넣지 않는다.
 * 일반 상품의 "n일 이내 발송" 은 고객사 값이 없어 넣지 않는다.
 */
export type DeliveryNote = {
  /** 프리오더 상품의 발송 예정 안내(`preorderNote`). 일반 상품은 null */
  preorder: string | null;
  /** 배송비 정책 줄. 비어 있을 수 있다 */
  lines: string[];
};

/**
 * @param preorder 프리오더 안내 한 줄(`preorderNote` 결과). 일반 상품은 null
 * @returns 보일 줄이 하나도 없으면 null — 안내 블록을 그리지 않는다
 */
export function deliveryNote(
  shipping: ShippingNotice | null,
  preorder: string | null = null,
): DeliveryNote | null {
  const lines = shipping
    ? [shipping.lines[0], shipping.freeShipping].filter((line): line is string => Boolean(line))
    : [];
  if (!preorder && lines.length === 0) return null;
  return { preorder, lines };
}

/** 한 줄만 보일 자리(모바일 하단 구매 바)의 문구 — 프리오더면 발송 예정, 아니면 기본 배송비 */
export function deliveryHeadline(note: DeliveryNote): string | null {
  return note.preorder ?? note.lines[0] ?? null;
}
