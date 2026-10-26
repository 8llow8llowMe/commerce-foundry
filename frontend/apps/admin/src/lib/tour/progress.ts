/** 로컬 저장소 키. 본 투어 아이디만 둔다(토큰 · 개인정보가 아니다) */
export const TOUR_PROGRESS_KEY = 'cf_admin_tour_seen';

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

export type TourState = {
  /** 끝냈거나 건너뛴 투어 아이디 */
  seen: ReadonlySet<string>;
  /** 도움말에서 다시 보기를 누른 투어. 투어가 끝날 때(`finish`) 비운다 */
  requested: string | null;
  /** 다시 보기를 누른 횟수 — 같은 투어를 또 눌러도(이미 고른 투어라 바뀐 게 없어도) 실행기가 다시 시작하게 한다 */
  requestSeq: number;
};

export type TourProgressStore = {
  /** 같은 객체를 돌려준다(바뀔 때만 새 객체) — `useSyncExternalStore` 스냅숏 */
  get: () => TourState;
  subscribe: (listener: () => void) => () => void;
  /** 투어를 끝냈거나 닫았다 — 본 것으로 남기고 다시 보기 요청도 지운다(한 번에 바꿔 다음 투어 고르기가 한 번만 일어나게) */
  finish: (tourId: string) => void;
  /** 이 투어를 지금 다시 본다 — 본 표시도 지운다(중간에 닫아도 다시 본 것으로 남긴다) */
  request: (tourId: string) => void;
  /** 요청만 지운다(시작하지 못했거나 화면을 옮겼다). 저장소는 건드리지 않는다 */
  clearRequest: () => void;
  /** 모든 투어를 처음처럼 — 들어가는 화면마다 다시 뜬다 */
  reset: () => void;
};

const EMPTY: TourState = Object.freeze({ seen: new Set<string>(), requested: null, requestSeq: 0 });

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

/**
 * 온보딩 투어 진행 상태(#228). 처음 한 번 자동으로 보이고, 끝내거나 건너뛰면 다시 뜨지 않는다.
 * 운영자별 저장 API 가 아직 없어 **이 브라우저**의 로컬 저장소에 둔다 — 다른 컴퓨터 · 브라우저에서는 다시 보인다.
 * 본 표시는 저장 직전에 저장소를 다시 읽어 합친다 — 여러 탭이 서로의 기록을 덮어쓰지 않게.
 * 저장소를 못 쓰면(사생활 보호 모드 등) 메모리에만 둬 이 탭 동안만 기억한다.
 */
export function createTourProgressStore(storage: () => StorageLike | null): TourProgressStore {
  let state: TourState | null = null;
  const listeners = new Set<() => void>();

  const load = (): TourState => {
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

  return {
    get: load,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    finish: (tourId) => {
      const current = load();
      if (current.seen.has(tourId) && current.requested === null) return;
      const seen = new Set([...current.seen, ...readSeen(storage()), tourId]);
      set({ ...current, seen, requested: null }, true);
    },
    request: (tourId) => {
      const current = load();
      const seen = new Set([...current.seen, ...readSeen(storage())]);
      seen.delete(tourId);
      set({ seen, requested: tourId, requestSeq: current.requestSeq + 1 }, true);
    },
    clearRequest: () => {
      const current = load();
      if (current.requested !== null) set({ ...current, requested: null }, false);
    },
    reset: () => set({ ...load(), seen: new Set(), requested: null }, true),
  };
}

/** 이 브라우저의 진행 상태. 서버 렌더에서는 저장소가 없다 */
export const tourProgress = createTourProgressStore(() =>
  typeof window === 'undefined' ? null : window.localStorage,
);

/** 서버 렌더 스냅숏 — 아무것도 보지 않은 것으로 그리되, 투어는 브라우저에서만 시작한다 */
export const serverTourState = (): TourState => EMPTY;

/**
 * 이 화면에서 지금 시작할 투어. 다시 보기 요청이 이 화면 투어면 그것, 아니면 아직 안 본 첫 투어.
 * 요청은 투어가 끝날 때(`finish`) 지운다 — 도는 동안 고른 투어가 바뀌어 스스로 닫히지 않게.
 * 이 화면에 없는 투어 요청은 무시한다.
 */
export function nextTour<T extends { id: string }>(
  tours: readonly T[],
  state: TourState,
): T | null {
  const requested = tours.find((tour) => tour.id === state.requested);
  if (requested) return requested;
  return tours.find((tour) => !state.seen.has(tour.id)) ?? null;
}
