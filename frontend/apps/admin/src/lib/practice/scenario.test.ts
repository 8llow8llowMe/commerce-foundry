import { describe, expect, it } from 'vitest';
import {
  expectedAction,
  INITIAL_PRACTICE,
  isFinished,
  practiceReducer,
  SCENARIOS,
  type PracticeState,
  type ScenarioId,
} from './scenario';
import { createPracticeTransport, type PracticeAction } from './transport';

const ACTIONS: Record<string, PracticeAction> = {
  confirm: { type: 'confirm' },
  prepare: { type: 'prepare' },
  ship: { type: 'ship', carrier: 'PRACTICE_A', trackingNumber: 'PRACTICE-1' },
  cancel: { type: 'cancel', reason: '고객 요청' },
  'refund-result': { type: 'refund-result' },
};

/** 화면이 하는 일 그대로 — 지금 단계의 샘플 버튼을 눌러 결과를 넘긴다 */
async function playThrough(scenario: ScenarioId) {
  const transport = createPracticeTransport();
  const { orderId } = SCENARIOS[scenario];
  let state = practiceReducer(INITIAL_PRACTICE, {
    type: 'start',
    scenario,
    order: transport.order(orderId)!,
  });
  const seen: (string | null)[] = [];
  while (!isFinished(state)) {
    const action = ACTIONS[expectedAction(state)!]!;
    seen.push(action.type);
    state = practiceReducer(state, {
      type: 'result',
      action: action.type,
      result: await transport.send(orderId, action),
    });
  }
  return { state, seen };
}

describe('practiceReducer', () => {
  it('주문 준비 시나리오는 확인 → 준비 → 송장으로 끝나고 배송 중이다', async () => {
    const { state, seen } = await playThrough('ship');
    expect(seen).toEqual(['confirm', 'prepare', 'ship']);
    expect(state.order?.status).toBe('SHIPPING');
    expect(state.refund).toBeNull();
  });

  it('취소 시나리오는 확인 → 취소 → 환불 결과로 끝나고 가짜 환불을 보인다', async () => {
    const { state, seen } = await playThrough('cancel');
    expect(seen).toEqual(['confirm', 'cancel', 'refund-result']);
    expect(state.order?.status).toBe('CANCELED');
    expect(state.refund?.amount).toEqual({ amount: 93000, currency: 'KRW' });
  });

  it('지금 단계가 아닌 동작의 결과로는 넘어가지 않는다(다음 버튼 · 늦은 응답)', async () => {
    const transport = createPracticeTransport();
    const started: PracticeState = practiceReducer(INITIAL_PRACTICE, {
      type: 'start',
      scenario: 'ship',
      order: transport.order(SCENARIOS.ship.orderId)!,
    });
    const order = transport.order(SCENARIOS.ship.orderId)!;
    const skipped = practiceReducer(started, {
      type: 'result',
      action: 'prepare',
      result: { ok: true, order, refund: null },
    });
    expect(skipped).toBe(started);
  });

  it('실패하면 단계를 그대로 두고 이유를 보인다. 다음에 성공하면 이유를 지운다', () => {
    const transport = createPracticeTransport();
    const order = transport.order(SCENARIOS.cancel.orderId)!;
    const started = practiceReducer(INITIAL_PRACTICE, { type: 'start', scenario: 'cancel', order });
    const failed = practiceReducer(started, {
      type: 'result',
      action: 'confirm',
      result: { ok: false, message: '실패' },
    });
    expect(failed).toMatchObject({ stepIndex: 0, error: '실패' });
    const passed = practiceReducer(failed, {
      type: 'result',
      action: 'confirm',
      result: { ok: true, order, refund: null },
    });
    expect(passed).toMatchObject({ stepIndex: 1, error: null });
  });

  it('처음부터 다시 하면 시나리오 고르기로 돌아간다', async () => {
    const { state } = await playThrough('ship');
    expect(isFinished(state)).toBe(true);
    const reset = practiceReducer(state, { type: 'reset' });
    expect(reset).toEqual(INITIAL_PRACTICE);
    expect(expectedAction(reset)).toBeNull();
    expect(isFinished(reset)).toBe(false);
  });
});
