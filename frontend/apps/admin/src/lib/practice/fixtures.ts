import type { Money } from '@cf/api-client';

/**
 * 샘플 주문 연습(#365)의 고정 데이터. 실제 상품 · 주문 · 고객과 무관한 가짜 값이고, 운영 DB 에서 복사하지 않는다.
 * 금액은 서버 값처럼 미리 정해 두고 `formatMoney` 로 보이기만 한다 — 연습 화면도 합계 · 환불액을 계산하지 않는다.
 */

export type PracticeStatus = 'PAID' | 'PREPARING' | 'SHIPPING' | 'CANCELED';

export const PRACTICE_STATUS_LABELS: Readonly<Record<PracticeStatus, string>> = {
  PAID: '결제 완료',
  PREPARING: '상품 준비중',
  SHIPPING: '배송 중',
  CANCELED: '취소 완료',
};

export type PracticeOrder = {
  orderId: string;
  orderName: string;
  recipientName: string;
  items: { productName: string; optionName: string; quantity: number; lineAmount: Money }[];
  totalAmount: Money;
  status: PracticeStatus;
  /** 상태가 바뀐 기록(연습 화면 안에서만) */
  histories: { status: PracticeStatus; note: string }[];
  /** 가짜 송장 — 연습에서 등록하면 채운다 */
  shipment: { carrierLabel: string; trackingNumber: string } | null;
};

export type PracticeRefund = {
  /** 환불 금액 — 고정 값(가짜 결제 취소 결과). 화면에서 계산하지 않는다 */
  amount: Money;
  methodLabel: string;
};

const sampleOrder = (orderId: string, orderName: string): PracticeOrder => ({
  orderId,
  orderName,
  recipientName: '연*생',
  items: [
    {
      productName: '샘플 저고리',
      optionName: 'M · 샘플색',
      quantity: 1,
      lineAmount: { amount: 45000, currency: 'KRW' },
    },
    {
      productName: '샘플 치마',
      optionName: 'FREE',
      quantity: 1,
      lineAmount: { amount: 45000, currency: 'KRW' },
    },
  ],
  totalAmount: { amount: 93000, currency: 'KRW' },
  status: 'PAID',
  histories: [{ status: 'PAID', note: '고객이 결제했습니다(샘플).' }],
  shipment: null,
});

/** 시나리오마다 따로 쓰는 샘플 주문. 주문 번호는 실제 주문 번호 모양(숫자)과 겹치지 않게 SAMPLE- 로 시작한다 */
export const PRACTICE_ORDERS = {
  ship: sampleOrder('SAMPLE-0001', '샘플 저고리 외 1건'),
  cancel: sampleOrder('SAMPLE-0002', '샘플 저고리 외 1건'),
} as const satisfies Record<string, PracticeOrder>;

/** 가짜 택배사 — 실제 택배사 코드를 쓰지 않는다(연습 송장이 실제 배송 조회로 이어지지 않게) */
export const PRACTICE_CARRIERS = [
  { code: 'PRACTICE_A', label: '연습 택배 A' },
  { code: 'PRACTICE_B', label: '연습 택배 B' },
] as const;

export type PracticeCarrierCode = (typeof PRACTICE_CARRIERS)[number]['code'];

/** 가짜 환불 결과 — 결제 금액 전액이 카드 결제 취소로 돌아간 것으로 보인다 */
export const PRACTICE_REFUND: PracticeRefund = {
  amount: { amount: 93000, currency: 'KRW' },
  methodLabel: '카드 결제 취소(연습)',
};
