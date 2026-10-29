import { onboardingApi, type OnboardingApi } from '@/lib/tour/onboarding-api';
import { taskViewsOf, type TaskView } from './start-tasks';

/**
 * 시작 체크리스트 업무 상태(#358). 정본은 서버(`GET /api/v1/admin/onboarding/me` 의 `tasks`)다 —
 * 화면에서 완료로 바꾸지 않고, 저장이 끝나면(`invalidate`) 서버에서 다시 읽는다. 조회는 원천 서비스를 거쳐 몇 초 걸릴 수 있다.
 */
export type TasksState = {
  /** 마지막으로 읽은 업무. 한 번도 못 읽었으면 null */
  tasks: readonly TaskView[] | null;
  /** 읽는 중 — 그동안 업무 상태는 "확인 중" 으로 그린다(지난 값을 미완료로 보이지 않게) */
  loading: boolean;
  /** 마지막 읽기가 실패했다(네트워크 · 5xx · 401 · 온보딩 API 가 없는 서버). 미완료와 다르다 */
  failed: boolean;
};

export type OnboardingTasksStore = {
  get: () => TasksState;
  subscribe: (listener: () => void) => () => void;
  /** 서버에서 다시 읽는다. 겹쳐 부르면 마지막 결과만 반영한다 */
  load: () => Promise<void>;
  /** 업무 사실이 바뀌었을 수 있다(저장 성공 등). 보고 있는 화면이 있으면 다시 읽고, 없으면 지난 값을 버린다 */
  invalidate: () => void;
  /** 로그아웃 · 로그인 화면 — 이 운영자의 업무 상태를 버린다(다른 운영자에게 보이지 않게) */
  signOut: () => void;
};

const INITIAL: TasksState = Object.freeze({ tasks: null, loading: false, failed: false });

export function createOnboardingTasksStore(
  api: Pick<OnboardingApi, 'getMine'> = onboardingApi,
): OnboardingTasksStore {
  let state: TasksState = INITIAL;
  let seq = 0;
  const listeners = new Set<() => void>();

  const set = (next: TasksState) => {
    state = next;
    for (const listener of listeners) listener();
  };

  const load = async () => {
    const mine = ++seq;
    set({ ...state, loading: true });
    try {
      const me = await api.getMine();
      if (mine !== seq) return;
      set({ tasks: taskViewsOf(me), loading: false, failed: false });
    } catch {
      if (mine !== seq) return;
      set({ ...state, loading: false, failed: true });
    }
  };

  return {
    get: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    load,
    invalidate: () => {
      if (listeners.size > 0) {
        void load();
        return;
      }
      seq += 1;
      set(INITIAL);
    },
    signOut: () => {
      seq += 1;
      set(INITIAL);
      startCardPref.clear();
    },
  };
}

/** 서버 렌더 스냅숏 — 아직 모른다 */
export const serverTasksState = (): TasksState => INITIAL;

/**
 * 시작 카드 접기 · 나중에(#358). 이 탭 동안만 기억한다(sessionStorage) — 운영자 기록이 아니라 화면 편의라 서버에 두지 않고,
 * 로그아웃하면 지운다. 업무 상태와는 관계없다.
 */
export type StartCardPref = 'open' | 'collapsed' | 'hidden';

export const START_CARD_PREF_KEY = 'cf_admin_start_card';

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

export function createStartCardPref(storage: () => StorageLike | null) {
  const listeners = new Set<() => void>();
  let cached: StartCardPref | null = null;

  const read = (): StartCardPref => {
    try {
      const raw = storage()?.getItem(START_CARD_PREF_KEY);
      return raw === 'collapsed' || raw === 'hidden' ? raw : 'open';
    } catch {
      return 'open';
    }
  };

  const write = (next: StartCardPref) => {
    cached = next;
    try {
      if (next === 'open') storage()?.removeItem(START_CARD_PREF_KEY);
      else storage()?.setItem(START_CARD_PREF_KEY, next);
    } catch {
      // 저장하지 못해도 이 화면 동안은 메모리 값으로 기억한다
    }
    for (const listener of listeners) listener();
  };

  return {
    get: (): StartCardPref => (cached ??= read()),
    subscribe: (listener: () => void) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    set: write,
    clear: () => write('open'),
  };
}

export const startCardPref = createStartCardPref(() =>
  typeof window === 'undefined' ? null : window.sessionStorage,
);

export const serverStartCardPref = (): StartCardPref => 'open';

export const onboardingTasks = createOnboardingTasksStore();
