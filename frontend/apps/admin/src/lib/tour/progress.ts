import { ApiError } from '@cf/api-client';
import {
  guideOf,
  isFinished,
  isTourStatus,
  onboardingApi,
  type OnboardingApi,
  type OnboardingMe,
  type TourStatus,
  type TourUpdate,
} from './onboarding-api';

/** 로컬 저장소 키. 본 투어 아이디만 둔다(토큰 · 개인정보가 아니다) */
export const TOUR_PROGRESS_KEY = 'cf_admin_tour_seen';
/** 보내지 못한 기록을 다시 보내기까지 기다리는 시간 · 연달아 실패하면 그만두는 횟수(다음 기록 때 다시 보낸다) */
export const RETRY_MS = 30_000;
export const MAX_RETRIES = 3;

type StorageLike = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** 서버에 있는 투어 기록. 키는 서버 안내 키(`console`)다 */
export type ServerTour = { version: number; status: TourStatus };

/**
 * 서버 기록을 불러온 상태.
 * - `loading` — 아직 모른다(처음 그리기 · 로그인 직후). 자동 시작하지 않는다
 * - `unavailable` — 불러오지 못했다(5xx · 네트워크 · 401). 자동 시작하지 않는다 — 운영자 입력을 막지 않게
 * - `local` — 서버에 온보딩 API 가 없다(404). 예전처럼 이 브라우저 기록으로 정한다
 * - `ready` — 서버 기록이 정본이다. 서버가 모르는 투어 · 버전이 다른 투어만 이 브라우저 기록으로 정한다
 */
export type ServerProgress =
  | { mode: 'loading' }
  | { mode: 'unavailable' }
  | { mode: 'local' }
  | { mode: 'ready'; tours: ReadonlyMap<string, ServerTour> };

export type TourState = {
  /**
   * 이 브라우저에서 끝냈거나 건너뛴 투어 아이디 — **힌트**다. 서버 기록으로 올리지 않고, 서버가 정하지 못하는 투어에만 쓴다.
   * 로그아웃 · 로그인 화면에서 지운다(다른 운영자에게 넘어가지 않게).
   */
  seen: ReadonlySet<string>;
  /** 도움말에서 다시 보기를 누른 투어. 투어가 끝날 때(`finish`) 비운다 */
  requested: string | null;
  /** 다시 보기를 누른 횟수 — 같은 투어를 또 눌러도(이미 고른 투어라 바뀐 게 없어도) 실행기가 다시 시작하게 한다 */
  requestSeq: number;
  server: ServerProgress;
};

export type TourProgressStore = {
  /** 같은 객체를 돌려준다(바뀔 때만 새 객체) — `useSyncExternalStore` 스냅숏 */
  get: () => TourState;
  subscribe: (listener: () => void) => () => void;
  /** 서버 기록을 새로 불러온다(콘솔 화면 틀이 붙을 때 — 로그인 직후). 보내지 못한 이전 기록은 버린다 */
  refresh: () => Promise<void>;
  /** 로그아웃 · 로그인 화면 — 이 운영자의 기록을 메모리 · 이 브라우저에서 지우고 보내지 못한 기록도 버린다 */
  signOut: () => void;
  /** 투어를 시작했다 — 진행 중으로 기록한다 */
  start: (tourId: string) => void;
  /**
   * 투어를 끝냈거나(`COMPLETED`) 중간에 닫았다(`SKIPPED`) — 다시 자동으로 뜨지 않게 남기고 다시 보기 요청도 지운다
   * (한 번에 바꿔 다음 투어 고르기가 한 번만 일어나게). `step` 은 마지막으로 본 단계(0부터)다.
   */
  finish: (tourId: string, status?: 'COMPLETED' | 'SKIPPED', step?: number | null) => void;
  /** 이 투어를 지금 다시 본다 — 기록을 지우고(서버는 재실행) 시작한다. 중간에 닫아도 다시 본 것으로 남는다 */
  request: (tourId: string) => void;
  /** 요청만 지운다(시작하지 못했거나 화면을 옮겼다). 저장소는 건드리지 않는다 */
  clearRequest: () => void;
  /** 모든 투어를 처음처럼 — 들어가는 화면마다 다시 뜬다 */
  reset: () => void;
};

type Scheduler = (run: () => void, ms: number) => () => void;

const LOADING: ServerProgress = Object.freeze({ mode: 'loading' });
const EMPTY: TourState = Object.freeze({
  seen: new Set<string>(),
  requested: null,
  requestSeq: 0,
  server: LOADING,
});

function readSeen(storage: StorageLike | null): Set<string> {
  try {
    const raw = storage?.getItem(TOUR_PROGRESS_KEY);
    const parsed: unknown = raw ? JSON.parse(raw) : [];
    if (Array.isArray(parsed)) {
      return new Set(parsed.filter((v): v is string => typeof v === 'string' && v.length <= 64));
    }
  } catch {
    // 읽을 수 없으면 처음처럼 본다
  }
  return new Set();
}

/** GET 응답 → 안내 키별 기록. 모양이 이상한 항목은 버린다(그 투어는 이 브라우저 기록으로 정한다) */
export function serverToursOf(me: OnboardingMe): Map<string, ServerTour> {
  const tours = new Map<string, ServerTour>();
  for (const item of Array.isArray(me?.tours) ? me.tours : []) {
    const status = item?.status?.code;
    if (
      typeof item?.guideKey === 'string' &&
      Number.isSafeInteger(item.guideVersion) &&
      isTourStatus(status)
    ) {
      tours.set(item.guideKey, { version: item.guideVersion, status });
    }
  }
  return tours;
}

/** 서버가 이 투어(같은 버전)를 기록하고 있으면 그 기록 */
function serverTourOf(tourId: string, server: ServerProgress) {
  const guide = guideOf(tourId);
  if (!guide || server.mode !== 'ready') return null;
  const tour = server.tours.get(guide.key);
  return tour && tour.version === guide.version ? { key: guide.key, tour } : null;
}

/**
 * 이 투어를 끝냈는가. 모르면(서버 기록을 아직 · 끝내 못 불러왔다) null — 자동으로 시작하지 않는다.
 * 서버 기록이 있으면 그것이 정본이고, 이 브라우저 기록(`seen`)은 보지 않는다 — 같은 브라우저를 쓴 다른 운영자의 기록이 섞이지 않게.
 */
export function isTourDone(tourId: string, state: TourState): boolean | null {
  const { server } = state;
  if (server.mode === 'loading' || server.mode === 'unavailable') return null;
  const recorded = serverTourOf(tourId, server);
  return recorded ? isFinished(recorded.tour.status) : state.seen.has(tourId);
}

/** 서버가 이 투어의 배포 버전을 번들보다 새로 냈다 — 새로 고침하면 바뀐 안내를 본다 */
export function hasNewerGuide(tourIds: readonly string[], state: TourState): boolean {
  if (state.server.mode !== 'ready') return false;
  const { tours } = state.server;
  return tourIds.some((tourId) => {
    const guide = guideOf(tourId);
    const tour = guide && tours.get(guide.key);
    return !!guide && !!tour && tour.version > guide.version;
  });
}

type FailureAction = 'untrack' | 'stale' | 'dropAll' | 'retry';

/** 계약의 FE 오류 대응(api-design-guide.md "운영자 온보딩 계약" · FE 폴백) */
function failureAction(cause: unknown): FailureAction {
  if (!(cause instanceof ApiError)) return 'retry';
  if (cause.kind === 'unauthorized' || cause.kind === 'forbidden') return 'dropAll';
  // 허용 목록에 없는 투어 · 서버가 없는 안내 버전 · 그 밖의 입력 오류 — 이 투어는 이 브라우저 기록으로만 간다
  if (cause.kind === 'notFound' || cause.kind === 'validation') return 'untrack';
  if (cause.status === 400) return 'untrack';
  if (cause.code === 'ONBOARDING_020') return 'stale';
  // ONBOARDING_021(다른 기기와 겹침) · 5xx · 네트워크 — 나중에 다시 보낸다
  return 'retry';
}

const defaultScheduler: Scheduler = (run, ms) => {
  const timer = setTimeout(run, ms);
  return () => clearTimeout(timer);
};

/**
 * 온보딩 투어 진행 상태(#228). 처음 한 번 자동으로 보이고, 끝내거나 건너뛰면 다시 뜨지 않는다.
 * **운영자별 서버 기록(BE #357)이 정본이다** — 다른 컴퓨터 · 브라우저에서도 이어진다. 기록은 화면에 먼저 반영하고(낙관적) 서버에 보낸다.
 * 서버는 같은 투어 기록을 합친다(끝난 상태는 진행 중으로 돌아가지 않는다) — 그래서 보낸 결과로 화면 상태를 바꾸지 않는다(돌고 있는 투어가 닫히지 않게).
 *
 * 이 브라우저의 로컬 기록(예전 저장 방식)은 힌트로만 둔다. 서버 기록으로 올리지 않고, 서버에 온보딩 API 가 없거나(404)
 * 서버가 모르는 투어 · 버전일 때만 자동 시작 여부를 정한다. 로그아웃하면(`signOut`) 지운다.
 *
 * 투어마다 보낼 기록을 차례로 보낸다 — 다시 보기(재실행 `NOT_STARTED`)가 그 뒤 진행 기록보다 먼저 닿게.
 * 운영자가 바뀌었을 수 있는 때(`refresh` · `signOut`)는 보내지 못한 기록을 버린다 — 다른 운영자 계정으로 보내지 않게.
 */
export function createTourProgressStore(
  storage: () => StorageLike | null,
  api: OnboardingApi = onboardingApi,
  schedule: Scheduler = defaultScheduler,
): TourProgressStore {
  let state: TourState | null = null;
  const listeners = new Set<() => void>();
  /** 안내 키별로 보낼 기록(앞에서부터). 재실행이 오면 그 앞 기록은 뺀다 */
  const queue = new Map<string, TourUpdate[]>();
  /** 운영자가 바뀌었을 수 있는 때마다 오른다 — 그 전에 시작한 보내기 · 불러오기는 결과를 버린다 */
  let generation = 0;
  let flushing = false;
  let flushAgain = false;
  let failures = 0;
  let cancelRetry: (() => void) | null = null;
  /** 옛 번들(409 ONBOARDING_020)이면 GET 을 한 번만 다시 부른다 */
  let reloadedForStale = false;

  const current = (): TourState => {
    if (state !== null) return state;
    const seen = readSeen(storage());
    state = seen.size > 0 ? { ...EMPTY, seen } : EMPTY;
    return state;
  };

  const set = (next: TourState, persist: boolean) => {
    state = next;
    if (persist) {
      try {
        storage()?.setItem(TOUR_PROGRESS_KEY, JSON.stringify([...next.seen]));
      } catch {
        // 저장하지 못해도 이 탭 동안은 메모리 값으로 기억한다
      }
    }
    for (const listener of listeners) listener();
  };

  const setServer = (server: ServerProgress) => set({ ...current(), server }, false);

  /** 서버 기록 하나를 화면에 먼저 반영한다(서버 병합 규칙과 같게 — 끝난 기록은 진행 중으로 내리지 않는다) */
  const withTour = (server: ServerProgress, key: string, status: TourStatus): ServerProgress => {
    if (server.mode !== 'ready') return server;
    const before = server.tours.get(key);
    if (!before) return server;
    const keep =
      status === 'IN_PROGRESS' ? isFinished(before.status) : before.status === 'COMPLETED';
    if (status !== 'NOT_STARTED' && keep) return server;
    const tours = new Map(server.tours);
    tours.set(key, { ...before, status });
    return { mode: 'ready', tours };
  };

  /** 서버에 보낼 안내(키 · 버전). 서버가 모르는 투어 · 버전이거나 서버 API 가 없으면 null — 이 브라우저 기록만 남긴다 */
  const writableGuide = (tourId: string) => {
    const guide = guideOf(tourId);
    const { server } = current();
    if (!guide || server.mode === 'local') return null;
    if (server.mode === 'ready' && !serverTourOf(tourId, server)) return null;
    return guide;
  };

  const untrack = (key: string) => {
    queue.delete(key);
    const { server } = current();
    if (server.mode !== 'ready' || !server.tours.has(key)) return;
    const tours = new Map(server.tours);
    tours.delete(key);
    setServer({ mode: 'ready', tours });
  };

  const dropPending = () => {
    queue.clear();
    cancelRetry?.();
    cancelRetry = null;
    failures = 0;
  };

  const newGeneration = () => {
    generation += 1;
    dropPending();
    flushing = false;
    flushAgain = false;
    reloadedForStale = false;
  };

  const loadServer = async (gen: number) => {
    try {
      const me = await api.getMine();
      if (gen !== generation) return;
      const tours = serverToursOf(me);
      // 다시 불러온 것이면(옛 번들 확인) 버전이 같은 투어는 화면에 먼저 반영한 기록을 둔다 — 아직 못 보낸 기록이 되돌려지지 않게
      const before = current().server;
      if (before.mode === 'ready') {
        for (const [key, tour] of before.tours) {
          if (tours.get(key)?.version === tour.version) tours.set(key, tour);
        }
      }
      setServer({ mode: 'ready', tours });
    } catch (cause) {
      if (gen !== generation) return;
      // 404 — 온보딩 API 가 아직 없는 서버다. 예전처럼 이 브라우저 기록으로 간다
      setServer(
        cause instanceof ApiError && cause.kind === 'notFound'
          ? { mode: 'local' }
          : { mode: 'unavailable' },
      );
    }
  };

  const scheduleRetry = () => {
    failures += 1;
    if (failures > MAX_RETRIES || cancelRetry) return;
    cancelRetry = schedule(() => {
      cancelRetry = null;
      void flush();
    }, RETRY_MS);
  };

  /** 보낼 기록을 투어마다 앞에서부터 하나씩 보낸다. 다시 보낼 오류가 나면 거기서 멈추고 나중에 다시 보낸다 */
  const flush = async (): Promise<void> => {
    if (flushing) {
      flushAgain = true;
      return;
    }
    const gen = generation;
    flushing = true;
    try {
      do {
        flushAgain = false;
        for (const [key, updates] of queue) {
          let update = updates[0];
          while (update) {
            try {
              await api.updateTour(key, update);
            } catch (cause) {
              if (gen !== generation) return;
              const action = failureAction(cause);
              if (action === 'retry') {
                scheduleRetry();
                return;
              }
              if (action === 'dropAll') {
                dropPending();
                return;
              }
              untrack(key);
              if (action === 'stale' && !reloadedForStale) {
                reloadedForStale = true;
                void loadServer(gen);
              }
              break;
            }
            if (gen !== generation) return;
            failures = 0;
            const left = queue.get(key);
            if (left?.[0] === update) left.shift();
            if (left?.length === 0) queue.delete(key);
            update = queue.get(key)?.[0];
          }
        }
      } while (flushAgain && gen === generation);
    } finally {
      if (gen === generation) flushing = false;
    }
  };

  const enqueue = (key: string, update: TourUpdate) => {
    const updates = update.status === 'NOT_STARTED' ? [] : [...(queue.get(key) ?? [])];
    // 진행 기록끼리는 나중 것만 보내면 된다(서버가 합친다). 재실행은 그 앞 기록을 지운다
    if (update.status !== 'NOT_STARTED' && updates.at(-1)?.status !== 'NOT_STARTED') updates.pop();
    updates.push(update);
    queue.set(key, updates);
    failures = 0;
    void flush();
  };

  return {
    get: current,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    refresh: async () => {
      newGeneration();
      setServer(LOADING);
      await loadServer(generation);
    },
    signOut: () => {
      newGeneration();
      try {
        storage()?.removeItem(TOUR_PROGRESS_KEY);
      } catch {
        // 지우지 못해도 메모리에서는 지운다
      }
      set({ ...EMPTY, requestSeq: current().requestSeq }, false);
    },
    start: (tourId) => {
      const guide = writableGuide(tourId);
      if (!guide) return;
      enqueue(guide.key, { guideVersion: guide.version, status: 'IN_PROGRESS', currentStep: 0 });
      const next = withTour(current().server, guide.key, 'IN_PROGRESS');
      if (next !== current().server) setServer(next);
    },
    finish: (tourId, status = 'SKIPPED', step = null) => {
      const now = current();
      const guide = writableGuide(tourId);
      if (guide) enqueue(guide.key, { guideVersion: guide.version, status, currentStep: step });
      const server = guide ? withTour(now.server, guide.key, status) : now.server;
      if (now.seen.has(tourId) && now.requested === null && server === now.server) return;
      const seen = new Set([...now.seen, ...readSeen(storage()), tourId]);
      set({ ...now, seen, requested: null, server }, true);
    },
    request: (tourId) => {
      const now = current();
      const guide = writableGuide(tourId);
      if (guide)
        enqueue(guide.key, {
          guideVersion: guide.version,
          status: 'NOT_STARTED',
          currentStep: null,
        });
      const seen = new Set([...now.seen, ...readSeen(storage())]);
      seen.delete(tourId);
      set(
        {
          seen,
          requested: tourId,
          requestSeq: now.requestSeq + 1,
          server: guide ? withTour(now.server, guide.key, 'NOT_STARTED') : now.server,
        },
        true,
      );
    },
    clearRequest: () => {
      const now = current();
      if (now.requested !== null) set({ ...now, requested: null }, false);
    },
    reset: () => {
      const now = current();
      let { server } = now;
      if (server.mode === 'ready') {
        // 일괄 API 가 없어 투어마다 재실행을 보낸다. 재실행은 멱등이라 실패한 것만 다시 보내도 된다
        for (const [key, tour] of server.tours) {
          enqueue(key, { guideVersion: tour.version, status: 'NOT_STARTED', currentStep: null });
          server = withTour(server, key, 'NOT_STARTED');
        }
      }
      set({ ...now, seen: new Set(), requested: null, server }, true);
    },
  };
}

/** 이 브라우저의 진행 상태. 서버 렌더에서는 저장소가 없다 */
export const tourProgress = createTourProgressStore(() =>
  typeof window === 'undefined' ? null : window.localStorage,
);

/** 서버 렌더 스냅숏 — 아무것도 모르는 것으로 그린다. 투어는 브라우저에서 서버 기록을 불러온 뒤에만 시작한다 */
export const serverTourState = (): TourState => EMPTY;

/**
 * 이 화면에서 지금 시작할 투어. 다시 보기 요청이 이 화면 투어면 그것, 아니면 끝내지 않은 첫 투어.
 * 끝냈는지 모르는 투어(서버 기록을 못 불러왔다)는 자동으로 시작하지 않는다 — 다시 보기는 된다.
 * 요청은 투어가 끝날 때(`finish`) 지운다 — 도는 동안 고른 투어가 바뀌어 스스로 닫히지 않게.
 * 이 화면에 없는 투어 요청은 무시한다.
 */
export function nextTour<T extends { id: string }>(
  tours: readonly T[],
  state: TourState,
): T | null {
  const requested = tours.find((tour) => tour.id === state.requested);
  if (requested) return requested;
  return tours.find((tour) => isTourDone(tour.id, state) === false) ?? null;
}
