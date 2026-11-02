import { PRACTICE_ORDERS, type PracticeOrder, type PracticeRefund } from './fixtures';
import type {
  PracticeAction,
  PracticeActionType,
  PracticeResult,
  PracticeTransport,
} from './transport';

/**
 * 샘플 주문 연습(#365) 시나리오 두 개와 진행 상태. 라이브 투어(driver.js)와 달리 "다음" 버튼이 없다 —
 * 운영자가 그 단계의 샘플 버튼을 직접 눌러 transport 가 성공을 돌려줘야만 다음 단계로 간다.
 * 연습을 마쳐도 서버의 업무 완료(#358) · 운영 통계에 기록하지 않는다.
 */

export type ScenarioId = keyof typeof PRACTICE_ORDERS;

export type ScenarioStep = { action: PracticeActionType; title: string; instruction: string };

export type Scenario = {
  title: string;
  summary: string;
  orderId: string;
  steps: readonly ScenarioStep[];
};

const CONFIRM_STEP: ScenarioStep = {
  action: 'confirm',
  title: '주문 확인',
  instruction:
    '상품 · 수량 · 결제 금액 · 받는 분을 확인하고 "주문 확인" 을 누르세요. 실제로는 주문 목록에서 주문을 눌러 상세를 엽니다.',
};

export const SCENARIOS: Readonly<Record<ScenarioId, Scenario>> = {
  ship: {
    title: '주문 준비 → 송장 입력',
    summary: '결제된 주문을 확인하고, 상품 준비중으로 바꾼 뒤 가짜 송장을 넣어 출고합니다.',
    orderId: PRACTICE_ORDERS.ship.orderId,
    steps: [
      CONFIRM_STEP,
      {
        action: 'prepare',
        title: '상품 준비',
        instruction:
          '포장을 시작할 때 "상품 준비중으로 바꾸기" 를 누르세요. 실제로는 주문 상세의 상태 바꾸기나 목록의 상태 일괄 변경을 씁니다.',
      },
      {
        action: 'ship',
        title: '송장 입력',
        instruction:
          '택배사를 고르고 송장 번호를 넣은 뒤 "송장 등록" 을 누르세요. 연습에서는 아무 번호나 넣어도 됩니다(영문 · 숫자 · 하이픈). 실제로는 출고된 송장을 바꿀 수 없습니다.',
      },
    ],
  },
  cancel: {
    title: '주문 취소 → 환불 결과',
    summary: '결제된 주문을 확인하고 취소한 뒤, 환불이 어떻게 처리됐는지 결과를 봅니다.',
    orderId: PRACTICE_ORDERS.cancel.orderId,
    steps: [
      CONFIRM_STEP,
      {
        action: 'cancel',
        title: '주문 취소',
        instruction:
          '고객 요청 같은 취소 사유를 적고 "주문 취소" 를 누르세요. 사유는 상태 이력에 남습니다.',
      },
      {
        action: 'refund-result',
        title: '환불 결과 확인',
        instruction:
          '"환불 결과 보기" 를 눌러 환불 금액과 방법을 확인하세요. 실제 환불 금액은 결제 서버가 정하고, 화면은 계산하지 않습니다.',
      },
    ],
  },
};

export type PracticeState = {
  scenario: ScenarioId | null;
  /** 지금 단계. 단계 수와 같으면 다 마쳤다 */
  stepIndex: number;
  order: PracticeOrder | null;
  refund: PracticeRefund | null;
  error: string | null;
  /** 실패할 때마다 오른다 — 같은 이유로 또 실패해도 오류 안내를 다시 읽게 한다 */
  errorSeq: number;
};

export const INITIAL_PRACTICE: PracticeState = {
  scenario: null,
  stepIndex: 0,
  order: null,
  refund: null,
  error: null,
  errorSeq: 0,
};

export type PracticeEvent =
  | { type: 'start'; scenario: ScenarioId; order: PracticeOrder }
  | { type: 'result'; action: PracticeActionType; result: PracticeResult }
  | { type: 'reset' };

/** 지금 눌러야 할 샘플 동작. 시나리오를 고르기 전이나 다 마쳤으면 null */
export function expectedAction(state: PracticeState): PracticeActionType | null {
  if (!state.scenario) return null;
  return SCENARIOS[state.scenario].steps[state.stepIndex]?.action ?? null;
}

export function isFinished(state: PracticeState): boolean {
  return state.scenario !== null && expectedAction(state) === null;
}

/**
 * 샘플 버튼 한 번 — 지금 시나리오의 샘플 주문으로 연습 transport 에 보내고 결과 이벤트를 만든다.
 * 화면(`PracticeScreen`)과 테스트가 같은 길을 지난다. 시나리오를 고르기 전이면 보내지 않는다.
 */
export async function runPracticeAction(
  transport: PracticeTransport,
  state: PracticeState,
  action: PracticeAction,
): Promise<PracticeEvent | null> {
  if (!state.scenario) return null;
  const result = await transport.send(SCENARIOS[state.scenario].orderId, action);
  return { type: 'result', action: action.type, result };
}

export function practiceReducer(state: PracticeState, event: PracticeEvent): PracticeState {
  switch (event.type) {
    case 'start':
      return { ...INITIAL_PRACTICE, scenario: event.scenario, order: event.order };
    case 'reset':
      return INITIAL_PRACTICE;
    case 'result': {
      // 지금 단계의 동작이 아닌 결과(늦게 온 응답 · 다른 단계 버튼)로는 넘어가지 않는다
      if (event.action !== expectedAction(state)) return state;
      if (!event.result.ok)
        return { ...state, error: event.result.message, errorSeq: state.errorSeq + 1 };
      return {
        ...state,
        stepIndex: state.stepIndex + 1,
        order: event.result.order,
        refund: event.result.refund ?? state.refund,
        error: null,
      };
    }
  }
}
