import { guideOf, type OnboardingMe } from '@/lib/tour/onboarding-api';
import type { TourState } from '@/lib/tour/progress';
import { SHIPPING_DOMESTIC_TOUR } from '@/lib/tour/tours';

/**
 * 어드민 시작 체크리스트의 업무(#358). 서버(`GET /api/v1/admin/onboarding/me` 의 `tasks`)가 준 업무만 그린다 —
 * 표시 이름 · 설명 · 이동할 화면은 여기(FE)가 가지고, 서버가 모르는 업무를 지어내지 않는다.
 * 여기에 없는 `taskKey` 는 숨긴다(새 업무가 서버에 먼저 배포돼도 화면이 깨지지 않게).
 */
export type StartTask = {
  title: string;
  description: string;
  /** 업무를 하는 화면 */
  href: string;
  /** 그 화면의 안내 투어. null 이면 "안내와 함께 열기" 를 두지 않는다 */
  tourId: string | null;
};

export const START_TASKS: Readonly<Record<string, StartTask>> = {
  /**
   * 상품 1건 등록 — 서버는 상품 등록 API(BE #9) 뒤에 이 업무를 내기 시작한다(#357 PR-C).
   * 키는 서버가 정하면 맞춘다. 그 전에는 서버가 주지 않으므로 보이지 않는다.
   */
  PRODUCT_REGISTERED: {
    title: '첫 상품 등록',
    description:
      '판매할 상품을 하나 등록합니다. 등록한 상품은 스토어에 보이기 전에 목록에서 확인할 수 있습니다.',
    href: '/products',
    tourId: null,
  },
  SHIPPING_POLICY_SAVED: {
    title: '국내 배송비 정책 저장',
    description:
      '기본 배송비와 무료배송 기준을 정해 저장합니다. 도서산간 추가 배송비도 여기서 정합니다.',
    href: '/shipping-policies/domestic',
    tourId: SHIPPING_DOMESTIC_TOUR.id,
  },
};

/**
 * 내 업무 상태(`myStatus`) → 화면 상태.
 * - `done` — 내가 저장(등록)한 기록이 있다. 체크박스 · 투어 완료로는 되지 않는다
 * - `storeOnly` — 스토어에는 설정돼 있지만 내 기록은 없다(다른 운영자 · 이력 전 설정). **완료가 아니다**
 * - `todo` — 아직 하지 않았다
 * - `unknown` — 원천 서비스 장애로 확인하지 못했다(`UNAVAILABLE`) · 모르는 코드. 완료 · 미완료로 추정하지 않는다
 */
export type TaskProgress = 'done' | 'storeOnly' | 'todo' | 'unknown';

export type TaskView = {
  key: string;
  task: StartTask;
  progress: TaskProgress;
  /** 내가 처음 해낸 시각(UTC ISO). `done` 일 때만 있다 */
  completedAt: string | null;
};

function progressOf(code: unknown): TaskProgress {
  switch (code) {
    case 'COMPLETED':
      return 'done';
    case 'NO_EVIDENCE':
      return 'storeOnly';
    case 'NOT_COMPLETED':
      return 'todo';
    default:
      return 'unknown';
  }
}

/** GET 응답 → 그릴 업무(서버 순서). 모르는 `taskKey` · 모양이 이상한 항목은 뺀다 */
export function taskViewsOf(me: OnboardingMe | null | undefined): TaskView[] {
  const items = Array.isArray(me?.tasks) ? me.tasks : [];
  const views: TaskView[] = [];
  for (const item of items) {
    const key = item?.taskKey;
    if (typeof key !== 'string' || !Object.hasOwn(START_TASKS, key)) continue;
    if (views.some((view) => view.key === key)) continue;
    const progress = progressOf(item.myStatus?.code);
    const completedAt =
      progress === 'done' && typeof item.myCompletedAt === 'string' ? item.myCompletedAt : null;
    views.push({ key, task: START_TASKS[key]!, progress, completedAt });
  }
  return views;
}

/**
 * 업무 화면 안내(투어)를 본 기록 — 업무 완료와 다른 문구로 보인다. 서버 기록이 있으면 그것, 없으면 이 브라우저 기록(힌트)이다.
 * 아직 모르거나(불러오는 중 · 실패) 본 적이 없으면 null.
 */
export function tourNoteOf(tourId: string | null, state: TourState): string | null {
  if (!tourId) return null;
  const { server } = state;
  if (server.mode === 'loading' || server.mode === 'unavailable') return null;
  const guide = guideOf(tourId);
  const recorded = guide && server.mode === 'ready' ? server.tours.get(guide.key) : undefined;
  if (guide && recorded && recorded.version === guide.version) {
    if (recorded.status === 'COMPLETED') return '안내를 끝까지 봤습니다';
    if (recorded.status === 'SKIPPED') return '안내를 건너뛰었습니다';
    if (recorded.status === 'IN_PROGRESS') return '안내를 보다 멈췄습니다';
    return null;
  }
  return state.seen.has(tourId) ? '안내를 봤습니다' : null;
}
