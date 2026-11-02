import {
  PRACTICE_CARRIERS,
  PRACTICE_ORDERS,
  PRACTICE_REFUND,
  type PracticeCarrierCode,
  type PracticeOrder,
  type PracticeRefund,
} from './fixtures';

/**
 * 샘플 주문 연습(#365) 전용 transport. 메모리 안의 고정 fixture 만 바꾼다 — 네트워크 요청을 하나도 보내지 않고(테스트가 소스까지 검사한다),
 * 처리할 수 없는 요청은 실패로 돌려준다. **실패해도 실서비스로 넘기지 않는다**(fallback 없음).
 * 상태 전이는 실제 주문 흐름의 모양만 흉내 낸다. 실제 주문 · 환불 계약(#30 · #27)이 바뀌면 문구 · 상태를 맞춘다.
 */

export const PRACTICE_REASON_MAX = 200;
const TRACKING_NUMBER_PATTERN = /^[A-Za-z0-9-]{1,50}$/;

export type PracticeAction =
  | { type: 'confirm' }
  | { type: 'prepare' }
  | { type: 'ship'; carrier: string; trackingNumber: string }
  | { type: 'cancel'; reason: string }
  | { type: 'refund-result' };

export type PracticeActionType = PracticeAction['type'];

export type PracticeResult =
  | { ok: true; order: PracticeOrder; refund: PracticeRefund | null }
  | { ok: false; message: string };

export type PracticeTransport = {
  send: (orderId: string, action: PracticeAction) => Promise<PracticeResult>;
  /** 샘플 주문을 처음 상태로 되돌린다 */
  reset: () => void;
  /** 이 샘플 주문의 지금 상태 */
  order: (orderId: string) => PracticeOrder | null;
};

const fresh = (): Map<string, PracticeOrder> =>
  new Map(Object.values(PRACTICE_ORDERS).map((order) => [order.orderId, structuredClone(order)]));

const carrierOf = (code: string) =>
  PRACTICE_CARRIERS.find((carrier) => carrier.code === (code as PracticeCarrierCode)) ?? null;

const fail = (message: string): PracticeResult => ({ ok: false, message });

export function createPracticeTransport(): PracticeTransport {
  let orders = fresh();
  const confirmed = new Set<string>();

  const apply = (order: PracticeOrder, action: PracticeAction): PracticeResult => {
    switch (action.type) {
      case 'confirm':
        if (order.status !== 'PAID') return fail('결제 완료 주문만 확인할 수 있습니다.');
        if (confirmed.has(order.orderId)) return { ok: true, order, refund: null };
        confirmed.add(order.orderId);
        order.histories.push({ status: 'PAID', note: '운영자가 주문 내용을 확인했습니다.' });
        return { ok: true, order, refund: null };
      case 'prepare':
        if (order.status !== 'PAID' || !confirmed.has(order.orderId))
          return fail('주문 내용을 먼저 확인해 주세요.');
        order.status = 'PREPARING';
        order.histories.push({ status: 'PREPARING', note: '운영자가 상품 준비를 시작했습니다.' });
        return { ok: true, order, refund: null };
      case 'ship': {
        if (order.status !== 'PREPARING') return fail('상품 준비중 주문에만 송장을 등록합니다.');
        const carrier = carrierOf(action.carrier);
        if (!carrier) return fail('택배사를 골라 주세요.');
        const trackingNumber = action.trackingNumber.trim();
        if (!TRACKING_NUMBER_PATTERN.test(trackingNumber))
          return fail('송장 번호는 영문 · 숫자 · 하이픈으로 50자 이하입니다.');
        order.status = 'SHIPPING';
        order.shipment = { carrierLabel: carrier.label, trackingNumber };
        order.histories.push({ status: 'SHIPPING', note: '가짜 송장을 등록해 출고했습니다.' });
        return { ok: true, order, refund: null };
      }
      case 'cancel': {
        if (order.status !== 'PAID' || !confirmed.has(order.orderId))
          return fail('주문 내용을 먼저 확인해 주세요.');
        const reason = action.reason.trim();
        if (reason.length === 0) return fail('취소 사유를 적어 주세요.');
        if (reason.length > PRACTICE_REASON_MAX)
          return fail(`취소 사유는 ${PRACTICE_REASON_MAX}자 이하로 적어 주세요.`);
        order.status = 'CANCELED';
        order.histories.push({ status: 'CANCELED', note: `운영자가 취소했습니다 — ${reason}` });
        return { ok: true, order, refund: null };
      }
      case 'refund-result':
        if (order.status !== 'CANCELED') return fail('취소한 주문만 환불 결과가 있습니다.');
        return { ok: true, order, refund: PRACTICE_REFUND };
      default:
        return fail('연습에서 할 수 없는 동작입니다.');
    }
  };

  return {
    send: (orderId, action) => {
      const order = orders.get(orderId);
      const result = order ? apply(order, action) : fail('샘플 주문이 아닙니다.');
      // 화면이 바꿀 수 없도록 사본을 돌려준다
      return Promise.resolve(
        result.ok ? { ...result, order: structuredClone(result.order) } : result,
      );
    },
    reset: () => {
      orders = fresh();
      confirmed.clear();
    },
    order: (orderId) => {
      const order = orders.get(orderId);
      return order ? structuredClone(order) : null;
    },
  };
}
