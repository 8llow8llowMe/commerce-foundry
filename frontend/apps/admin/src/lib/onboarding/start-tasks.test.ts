import { describe, expect, it } from 'vitest';
import type { ServerTaskItem, TourStatus } from '@/lib/tour/onboarding-api';
import type { ServerProgress, TourState } from '@/lib/tour/progress';
import { SHIPPING_DOMESTIC_TOUR } from '@/lib/tour/tours';
import { START_TASKS, taskViewsOf, tourNoteOf } from './start-tasks';

const task = (
  taskKey: string,
  my: string,
  myCompletedAt: string | null = null,
  store = 'NOT_READY',
): ServerTaskItem => ({
  taskKey,
  storeStatus: { code: store },
  myStatus: { code: my },
  myCompletedAt,
});

describe('taskViewsOf', () => {
  it('서버가 준 업무만 서버 순서로 그리고, 모르는 taskKey 는 숨긴다', () => {
    const views = taskViewsOf({
      tours: [],
      tasks: [task('SOMETHING_NEW', 'COMPLETED'), task('SHIPPING_POLICY_SAVED', 'NOT_COMPLETED')],
    });
    expect(views.map((view) => view.key)).toEqual(['SHIPPING_POLICY_SAVED']);
    expect(views[0]!.task).toBe(START_TASKS.SHIPPING_POLICY_SAVED);
  });

  it('상품 등록 업무는 서버가 주기 시작하면 보인다(표시 이름만 미리 둔다)', () => {
    expect(taskViewsOf({ tours: [], tasks: [] })).toEqual([]);
    const views = taskViewsOf({ tours: [], tasks: [task('PRODUCT_REGISTERED', 'NOT_COMPLETED')] });
    expect(views[0]?.task.title).toBe('첫 상품 등록');
  });

  it('내 상태를 완료 · 스토어만 · 미완료 · 확인 불가로 나눈다', () => {
    const at = '2026-10-01T00:00:00Z';
    const progress = (my: string, completedAt: string | null = null) =>
      taskViewsOf({ tours: [], tasks: [task('SHIPPING_POLICY_SAVED', my, completedAt)] })[0];
    expect(progress('COMPLETED', at)).toMatchObject({ progress: 'done', completedAt: at });
    // 스토어에는 있지만 내 기록이 없다 — 완료가 아니다
    expect(progress('NO_EVIDENCE')?.progress).toBe('storeOnly');
    expect(progress('NOT_COMPLETED')?.progress).toBe('todo');
    // 원천 장애 · 모르는 코드는 완료 · 미완료로 추정하지 않는다
    expect(progress('UNAVAILABLE')?.progress).toBe('unknown');
    expect(progress('WHAT')?.progress).toBe('unknown');
    expect(progress('NOT_COMPLETED', at)?.completedAt).toBeNull();
  });

  it('tasks 가 없는 옛 응답 · 깨진 항목 · 겹친 항목은 버린다', () => {
    expect(taskViewsOf({ tours: [] })).toEqual([]);
    expect(taskViewsOf(null)).toEqual([]);
    const broken = { tours: [], tasks: [null, { taskKey: 1 }] } as unknown as Parameters<
      typeof taskViewsOf
    >[0];
    expect(taskViewsOf(broken)).toEqual([]);
    const twice = taskViewsOf({
      tours: [],
      tasks: [
        task('SHIPPING_POLICY_SAVED', 'COMPLETED'),
        task('SHIPPING_POLICY_SAVED', 'NOT_COMPLETED'),
      ],
    });
    expect(twice).toHaveLength(1);
    expect(taskViewsOf({ tours: [], tasks: [task('toString', 'COMPLETED')] })).toEqual([]);
  });
});

describe('tourNoteOf', () => {
  const tourId = SHIPPING_DOMESTIC_TOUR.id;
  const state = (server: ServerProgress, seen: string[] = []): TourState => ({
    seen: new Set(seen),
    requested: null,
    requestSeq: 0,
    server,
  });
  const ready = (status: TourStatus, version = 1): ServerProgress => ({
    mode: 'ready',
    tours: new Map([['shipping-domestic', { version, status }]]),
  });

  it('서버 투어 기록을 업무 완료와 다른 문구로 보인다', () => {
    expect(tourNoteOf(tourId, state(ready('COMPLETED')))).toBe('안내를 끝까지 봤습니다');
    expect(tourNoteOf(tourId, state(ready('SKIPPED')))).toBe('안내를 건너뛰었습니다');
    expect(tourNoteOf(tourId, state(ready('IN_PROGRESS')))).toBe('안내를 보다 멈췄습니다');
    expect(tourNoteOf(tourId, state(ready('NOT_STARTED'), [tourId]))).toBeNull();
  });

  it('서버가 모르는 투어 · 버전이면 이 브라우저 기록을 힌트로 쓴다', () => {
    expect(tourNoteOf(tourId, state({ mode: 'ready', tours: new Map() }, [tourId]))).toBe(
      '안내를 봤습니다',
    );
    expect(tourNoteOf(tourId, state(ready('COMPLETED', 2), [tourId]))).toBe('안내를 봤습니다');
    expect(tourNoteOf(tourId, state({ mode: 'local' }))).toBeNull();
  });

  it('투어 기록을 모르면(불러오는 중 · 실패) · 투어가 없으면 아무것도 보이지 않는다', () => {
    expect(tourNoteOf(tourId, state({ mode: 'loading' }, [tourId]))).toBeNull();
    expect(tourNoteOf(tourId, state({ mode: 'unavailable' }, [tourId]))).toBeNull();
    expect(tourNoteOf(null, state(ready('COMPLETED')))).toBeNull();
  });
});
