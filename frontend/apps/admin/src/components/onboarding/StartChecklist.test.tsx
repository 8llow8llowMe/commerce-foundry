import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { taskViewsOf } from '@/lib/onboarding/start-tasks';
import type { StartCardPref, TasksState } from '@/lib/onboarding/tasks-store';
import type { ServerProgress, TourState } from '@/lib/tour/progress';
import { focusAfterSwap, progressLabel, StartChecklistView } from './StartChecklist';

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

  it('서버가 상품 등록 업무를 주면 배송비 업무 다음에 상품 등록 화면 이동 · 안내와 함께 열기를 보인다', () => {
    const item = (taskKey: string, my: string) => ({
      taskKey,
      storeStatus: { code: 'READY' },
      myStatus: { code: my },
      myCompletedAt: null,
    });
    const both = (my: string) =>
      taskViewsOf({
        tours: [],
        tasks: [item('SHIPPING_POLICY_SAVED', 'NOT_COMPLETED'), item('PRODUCT_REGISTERED', my)],
      });
    const html = render({ tasks: both('NOT_COMPLETED'), loading: false, failed: false });
    expect(html.indexOf('국내 배송비 정책 저장')).toBeLessThan(html.indexOf('첫 상품 등록'));
    expect(html).toContain('href="/products/new"');
    expect(html.match(/>안내와 함께 열기</g)).toHaveLength(2);
    expect(html).toContain('2개 중 0개 완료');
    // 다른 운영자가 등록한 상품만 있으면(스토어만) · 상품 서비스 장애면(확인 불가) 완료도 미완료도 아니다
    const storeOnly = render({ tasks: both('NO_EVIDENCE'), loading: false, failed: false });
    expect(storeOnly).toContain('스토어에는 설정됨 · 내 저장 기록 없음');
    expect(storeOnly).toContain('2개 중 0개 완료');
    const unknown = render({ tasks: both('UNAVAILABLE'), loading: false, failed: false });
    expect(unknown).toContain('확인 불가');
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

  it('완료한 업무는 처음 해낸 날을 같이 보인다', () => {
    const html = render({ tasks: views('COMPLETED'), loading: false, failed: false });
    expect(html).toMatch(/완료 · \d{4}\.\d{2}\.\d{2}/);
    expect(html).toContain('화면 보기');
    expect(progressLabel({ progress: 'done', completedAt: null })).toBe('완료');
    expect(progressLabel({ progress: 'done', completedAt: 'not-a-date' })).toBe('완료');
    expect(progressLabel({ progress: 'todo', completedAt: '2026-10-01T00:00:00Z' })).toBe(
      '아직 하지 않음',
    );
  });

  describe('키보드', () => {
    const both = render(
      {
        tasks: taskViewsOf({
          tours: [],
          tasks: ['PRODUCT_REGISTERED', 'SHIPPING_POLICY_SAVED'].map((taskKey) => ({
            taskKey,
            storeStatus: { code: 'READY' },
            myStatus: { code: 'NOT_COMPLETED' },
            myCompletedAt: null,
          })),
        }),
        loading: false,
        failed: false,
      },
      'open',
    );

    it('조작은 모두 버튼 · 링크라 Tab 으로 닿고, 포커스 링이 보이며, Tab 에서 빼는 요소가 없다', () => {
      expect(both).not.toContain('tabindex="-1"');
      expect(both).not.toMatch(/<(div|li|p|span)[^>]*onclick/i);
      for (const link of both.match(/<a [^>]*>/g) ?? []) {
        expect(link).toContain('focus-visible:outline-2');
      }
      expect(both.match(/<a /g)).toHaveLength(2);
    });

    it('업무마다 "바로 하기" · "안내와 함께 열기" 의 접근 이름이 달라 화면 낭독기 목록에서 구분된다', () => {
      expect(both).toContain('aria-label="첫 상품 등록 — 바로 하기"');
      expect(both).toContain('aria-label="국내 배송비 정책 저장 — 바로 하기"');
      expect(both).toContain('aria-label="첫 상품 등록 — 안내와 함께 열기"');
      expect(both).toContain('aria-label="국내 배송비 정책 저장 — 안내와 함께 열기"');
    });

    it('나중에 · 다시 보기 뒤에 포커스를 새로 생긴 버튼으로 옮긴다', () => {
      const show = { focus: vi.fn() };
      const first = { focus: vi.fn() };
      const actions = { querySelector: vi.fn(() => first) };
      focusAfterSwap('hidden', show, actions);
      expect(show.focus).toHaveBeenCalledTimes(1);
      expect(first.focus).not.toHaveBeenCalled();
      focusAfterSwap('open', show, actions);
      expect(actions.querySelector).toHaveBeenCalledWith('button');
      expect(first.focus).toHaveBeenCalledTimes(1);
      // 아직 그려지지 않았으면 아무것도 하지 않는다
      expect(() => focusAfterSwap('open', null, null)).not.toThrow();
    });
  });

  describe('모바일', () => {
    it('좁은 화면에서는 업무 설명과 버튼이 위아래로 쌓이고, 넓으면 나란히 둔다', () => {
      const html = render({ tasks: views('NOT_COMPLETED'), loading: false, failed: false });
      expect(html).toMatch(/<li class="[^"]*flex-col[^"]*sm:flex-row/);
      // 업무 버튼 묶음 — 좁으면 줄을 바꾸고, 넓으면 설명 옆에서 줄어들지 않는다
      expect(html).toContain('<div class="flex shrink-0 flex-wrap gap-2">');
    });

    it('누를 곳은 모두 40px 이상 높이다(숨긴 뒤의 "시작 체크리스트 보기" 포함)', () => {
      const open = render({ tasks: views('NOT_COMPLETED'), loading: false, failed: false });
      for (const control of open.match(/<(a|button) [^>]*>/g) ?? []) {
        expect(control).toMatch(/\b(h-10|min-h-10)\b/);
      }
      const hidden = render(
        { tasks: views('NOT_COMPLETED'), loading: false, failed: false },
        'hidden',
      );
      expect(hidden).toContain('min-h-10');
    });
  });

  it('동작 줄이기 — 카드에는 애니메이션이 없고, 버튼 전환 효과는 동작 줄이기에서 꺼진다', () => {
    const html = render({ tasks: views('NOT_COMPLETED'), loading: true, failed: true });
    expect(html).not.toMatch(/\banimate-/);
    for (const cls of html.match(/class="[^"]*\btransition\b[^"]*"/g) ?? []) {
      expect(cls).toContain('motion-reduce:transition-none');
    }
  });
});
