import { isJobId } from './detail';

/** 세션 저장소 키. 작업 아이디만 둔다(토큰 · 개인정보가 아니다) */
export const TRACKED_JOBS_KEY = 'cf_admin_tracked_jobs';
/** 상단 바에서 지켜보는 작업 수 상한. 넘치면 오래된 것부터 뺀다 */
export const MAX_TRACKED_JOBS = 5;

export type TrackedJobsStore = {
  /** 최근에 더한 것이 앞이다. 같은 배열을 돌려준다(바뀔 때만 새 배열) — `useSyncExternalStore` 스냅숏 */
  get: () => readonly string[];
  subscribe: (listener: () => void) => () => void;
  track: (jobId: string) => void;
  untrack: (jobId: string) => void;
};

type StorageLike = Pick<Storage, 'getItem' | 'setItem'>;

const EMPTY: readonly string[] = Object.freeze([]);

/**
 * 상단 바 작업 진행 표시(#214)가 지켜볼 작업 목록 — 이 탭에서 시작했거나 연 실행 중 작업이다.
 * 작업 목록 API 는 요청자로 거르지 못해(모든 관리자 작업이 보인다) 서버에서 "내 작업" 을 고르지 않고 이 탭이 기억한다.
 * 탭 세션 저장소(sessionStorage)에 둬 새로 고침해도 남고, 다른 탭과 섞이지 않는다. 저장소를 못 쓰면(사생활 보호 모드 등) 메모리에만 둔다.
 */
export function createTrackedJobsStore(storage: () => StorageLike | null): TrackedJobsStore {
  let jobs: readonly string[] | null = null;
  const listeners = new Set<() => void>();

  const load = (): readonly string[] => {
    if (jobs !== null) return jobs;
    jobs = EMPTY;
    try {
      const raw = storage()?.getItem(TRACKED_JOBS_KEY);
      const parsed: unknown = raw ? JSON.parse(raw) : [];
      if (Array.isArray(parsed)) {
        const valid = parsed.filter((v): v is string => typeof v === 'string' && isJobId(v));
        jobs = valid.length > 0 ? valid.slice(0, MAX_TRACKED_JOBS) : EMPTY;
      }
    } catch {
      // 읽을 수 없으면 비운다
    }
    return jobs;
  };

  const save = (next: readonly string[]) => {
    jobs = next.length > 0 ? next : EMPTY;
    try {
      storage()?.setItem(TRACKED_JOBS_KEY, JSON.stringify(jobs));
    } catch {
      // 저장하지 못해도 이 화면 동안은 메모리 값으로 보인다
    }
    for (const listener of listeners) listener();
  };

  return {
    get: load,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    track: (jobId) => {
      const current = load();
      if (!isJobId(jobId) || current[0] === jobId) return;
      save([jobId, ...current.filter((id) => id !== jobId)].slice(0, MAX_TRACKED_JOBS));
    },
    untrack: (jobId) => {
      const current = load();
      if (current.includes(jobId)) save(current.filter((id) => id !== jobId));
    },
  };
}

/** 브라우저 탭 하나의 목록. 서버 렌더에서는 늘 비어 있다 */
export const trackedJobs = createTrackedJobsStore(() =>
  typeof window === 'undefined' ? null : window.sessionStorage,
);

/** 서버 렌더 스냅숏 */
export const noTrackedJobs = (): readonly string[] => EMPTY;
