import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { taskViewsOf } from '@/lib/onboarding/start-tasks';
import type { StartCardPref, TasksState } from '@/lib/onboarding/tasks-store';
import type { ServerProgress, TourState } from '@/lib/tour/progress';
import { StartChecklistView } from './StartChecklist';

const views = (my: string) =>
  taskViewsOf({
    tours: [],
    tasks: [
      {
        taskKey: 'SHIPPING_POLICY_SAVED',
        storeStatus: { code: 'READY' },
        myStatus: { code: my },
        myCompletedAt: my === 'COMPLETED' ? '2026-10-01T00:00:00Z' : null,
      },
    ],
  });

const tourState = (server: ServerProgress = { mode: 'loading' }): TourState => ({
  seen: new Set(),
  requested: null,
  requestSeq: 0,
  server,
});

function render(tasks: TasksState, pref: StartCardPref = 'open', tours = tourState()) {
  return renderToStaticMarkup(
    <StartChecklistView
      tasks={tasks}
      tours={tours}
      pref={pref}
      onPref={() => {}}
      onRetry={() => {}}
      onOpenWithTour={() => {}}
    />,
  );
}

describe('StartChecklistView', () => {
  it('업무 이름 · 상태 · 업무 화면 이동 · 안내와 함께 열기를 보인다', () => {
    const html = render({ tasks: views('NOT_COMPLETED'), loading: false, failed: false });
    expect(html).toContain('국내 배송비 정책 저장');
    expect(html).toContain('아직 하지 않음');
    expect(html).toContain('1개 중 0개 완료');
    expect(html).toContain('href="/shipping-policies/domestic"');
    expect(html).toContain('안내와 함께 열기');
    // 카드에서 완료로 바꾸는 체크박스는 없다
    expect(html).not.toContain('type="checkbox"');
  });

  it('서버가 완료라고 할 때만 완료다. 스토어에만 있으면 완료가 아니다', () => {
    expect(render({ tasks: views('COMPLETED'), loading: false, failed: false })).toContain(
      '시작 업무를 모두 마쳤습니다.',
    );
    const storeOnly = render({ tasks: views('NO_EVIDENCE'), loading: false, failed: false });
    expect(storeOnly).toContain('스토어에는 설정됨 · 내 저장 기록 없음');
    expect(storeOnly).toContain('1개 중 0개 완료');
  });

  it('투어를 끝까지 본 것은 업무 완료와 다른 문구다', () => {
    const tours = tourState({
      mode: 'ready',
      tours: new Map([['shipping-domestic', { version: 1, status: 'COMPLETED' }]]),
    });
    const html = render(
      { tasks: views('NOT_COMPLETED'), loading: false, failed: false },
      'open',
      tours,
    );
    expect(html).toContain('안내를 끝까지 봤습니다');
    expect(html).toContain('아직 하지 않음');
  });

  it('로딩 · 조회 실패 · 원천 장애는 미완료로 보이지 않는다', () => {
    const first = render({ tasks: null, loading: true, failed: false });
    expect(first).toContain('업무 상태를 확인하는 중');
    expect(first).not.toContain('아직 하지 않음');

    const failed = render({ tasks: null, loading: false, failed: true });
    expect(failed).toContain('아직 하지 않았다는 뜻은 아닙니다');
    expect(failed).toContain('다시 확인');
    expect(failed).not.toContain('아직 하지 않음');

    // 다시 읽는 동안 지난 값을 보이지 않는다(저장 직후 지난 미완료가 남지 않게)
    const rechecking = render({ tasks: views('NOT_COMPLETED'), loading: true, failed: false });
    expect(rechecking).toContain('확인 중…');
    expect(rechecking).not.toContain('아직 하지 않음');

    const unavailable = render({ tasks: views('UNAVAILABLE'), loading: false, failed: false });
    expect(unavailable).toContain('확인 불가');
    expect(unavailable).not.toContain('아직 하지 않음');
  });

  it('접으면 펼치기 버튼과 감춘 본문, 나중에면 다시 보기 버튼만 남는다', () => {
    const collapsed = render(
      { tasks: views('NOT_COMPLETED'), loading: false, failed: false },
      'collapsed',
    );
    expect(collapsed).toContain('aria-expanded="false"');
    expect(collapsed).toContain('펼치기');
    expect(collapsed).toMatch(/<div[^>]*hidden=""/);

    const hidden = render(
      { tasks: views('NOT_COMPLETED'), loading: false, failed: false },
      'hidden',
    );
    expect(hidden).toContain('시작 체크리스트 보기');
    expect(hidden).not.toContain('국내 배송비 정책 저장');
  });

  it('서버가 그릴 업무를 주지 않으면 카드를 두지 않는다', () => {
    expect(render({ tasks: [], loading: false, failed: false })).toBe('');
  });
});
