'use client';

import { formatMoney } from '@cf/api-client';
import { useEffect, useId, useReducer, useRef, useState } from 'react';
import {
  INITIAL_PRACTICE,
  isFinished,
  practiceReducer,
  runPracticeAction,
  SCENARIOS,
  type ScenarioId,
} from '@/lib/practice/scenario';
import {
  createPracticeTransport,
  type PracticeAction,
  type PracticeTransport,
} from '@/lib/practice/transport';
import {
  PracticeBanner,
  PracticeOrderCard,
  ScenarioPicker,
  StepAction,
  StepError,
  StepList,
} from './PracticeParts';

/**
 * 샘플 주문으로 배우는 업무 연습(#365). 도움말(?)의 "샘플 주문으로 연습하기" 로 들어온다.
 * - 데이터는 고정 fixture 와 연습 전용 transport(`lib/practice/transport`)뿐이다. 실서비스 API 를 부르지 않고, 실패해도 실서비스로 넘기지 않는다.
 * - 운영자가 그 단계의 샘플 버튼을 눌러 성공해야 다음 단계로 간다. 연습을 마쳐도 서버에 업무 완료로 남기지 않는다.
 * - 진행은 이 화면 메모리에만 있다. 처음부터 다시 · 연습 끝내기 · 뒤로가기로 떠나면 샘플 주문은 처음 상태로 돌아간다.
 * - 단계가 바뀌면 단계 제목으로 포커스를 옮겨 키보드 · 화면 낭독기 사용자도 다음 할 일을 바로 듣는다.
 *   처리 중에는 버튼을 `disabled` 대신 `aria-disabled` 로 막는다 — 실패해 단계가 그대로일 때 포커스가 body 로 빠지지 않게.
 */
export function PracticeScreen({ transport }: { transport?: PracticeTransport }) {
  const id = useId();
  const [practice] = useState(() => transport ?? createPracticeTransport());
  const [state, dispatch] = useReducer(practiceReducer, INITIAL_PRACTICE);
  const [pending, setPending] = useState(false);
  const inFlight = useRef(false);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const shown = useRef({ scenario: state.scenario, stepIndex: state.stepIndex });

  useEffect(() => {
    // 단계가 실제로 바뀌었을 때만 옮긴다(처음 그릴 때 · 개발 모드의 effect 두 번 실행에는 옮기지 않는다)
    const before = shown.current;
    if (before.scenario === state.scenario && before.stepIndex === state.stepIndex) return;
    shown.current = { scenario: state.scenario, stepIndex: state.stepIndex };
    // 처음부터 다시 하면 단계 제목이 사라지므로 화면 제목으로 옮긴다
    (state.scenario ? headingRef : titleRef).current?.focus();
  }, [state.scenario, state.stepIndex]);

  const start = (scenario: ScenarioId) => {
    const order = practice.order(SCENARIOS[scenario].orderId);
    if (order) dispatch({ type: 'start', scenario, order });
  };

  const reset = () => {
    practice.reset();
    inFlight.current = false;
    setPending(false);
    dispatch({ type: 'reset' });
  };

  const send = async (action: PracticeAction) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setPending(true);
    const event = await runPracticeAction(practice, state, action);
    inFlight.current = false;
    setPending(false);
    if (event) dispatch(event);
  };

  const scenario = state.scenario ? SCENARIOS[state.scenario] : null;
  const step = scenario?.steps[state.stepIndex] ?? null;
  const finished = isFinished(state);

  return (
    <div className="flex max-w-4xl flex-col gap-6">
      <PracticeBanner onReset={reset} />

      <div className="flex flex-col gap-1">
        <h1 ref={titleRef} tabIndex={-1} className="text-xl font-semibold outline-none">
          샘플 주문으로 업무 연습
        </h1>
        <p className="text-sm text-muted-fg">
          주문 처리 순서를 샘플 주문으로 직접 눌러 봅니다. 연습 결과는 실제 업무 완료나 운영 통계에
          기록되지 않습니다.
        </p>
      </div>

      {!scenario && <ScenarioPicker onStart={start} />}

      {scenario && state.order && (
        <div className="flex flex-col gap-4">
          <StepList steps={scenario.steps} current={state.stepIndex} />
          <section
            aria-labelledby={`${id}-step`}
            className="flex flex-col gap-3 rounded-md bg-muted/50 p-4"
          >
            <h2
              id={`${id}-step`}
              ref={headingRef}
              tabIndex={-1}
              className="font-semibold outline-none"
            >
              {step
                ? `${state.stepIndex + 1}단계 · ${step.title}`
                : `연습 완료 · ${scenario.title}`}
            </h2>
            {step ? (
              <>
                <p className="text-sm">{step.instruction}</p>
                <StepAction
                  key={`${state.scenario}-${state.stepIndex}`}
                  step={step}
                  pending={pending}
                  errorId={state.error ? `${id}-error` : undefined}
                  onSend={(action) => void send(action)}
                />
                <StepError key={state.errorSeq} id={`${id}-error`} message={state.error} />
              </>
            ) : (
              <p className="text-sm">
                모든 단계를 마쳤습니다. 연습한 내용은 실제 주문에 반영되지 않았고, 시작 체크리스트의
                업무 완료로도 세지 않습니다. 실제 주문은 주문 메뉴에서 처리합니다.
              </p>
            )}
            {/* 늘 있는 live region — 내용만 바꿔야 화면 낭독기가 환불 결과를 읽는다 */}
            <p className="text-sm empty:hidden" role="status">
              {state.refund &&
                `환불 결과(연습): ${state.refund.methodLabel}로 ${formatMoney(state.refund.amount)} 환불되었습니다.`}
            </p>
            {finished && (
              <div className="flex flex-wrap gap-3">
                <button
                  type="button"
                  onClick={reset}
                  className="text-sm underline underline-offset-4"
                >
                  다른 연습 고르기
                </button>
              </div>
            )}
          </section>
          <PracticeOrderCard order={state.order} />
        </div>
      )}
    </div>
  );
}
