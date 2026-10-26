import { apiFetch } from '@cf/api-client';
import type { AccessDenied } from '@/lib/api-errors';
import {
  cancelFailure,
  cancelJobApiPath,
  isActiveJob,
  isJobId,
  jobApiPath,
  loadFailure,
  pollDelay,
} from './detail';
import type { JobItem } from './types';

export type JobView =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'failed' }
  | { kind: 'ready'; job: JobItem };

export type JobDetailState = {
  view: JobView;
  /** 이미 보인 작업을 다시 읽다 일시 오류가 났다 — 마지막 상태를 둔 채 안내하고 간격을 늘려 계속 읽는다 */
  pollFailed: boolean;
  cancelling: boolean;
  /**
   * 취소 요청이 실패했을 때의 안내(JOB_022 · JOB_021 · 결과 모름 등). 없으면 ''.
   * 성공 결과는 따로 만들지 않는다 — 응답의 상태 · 서버 안내 문구(`notice`)가 곧 결과다.
   */
  cancelError: string;
};

/** 탭이 보이는지. 기본은 `document.visibilityState` 다(서버 · 테스트처럼 document 가 없으면 늘 보인다) */
export type Visibility = {
  hidden: () => boolean;
  /** 보임 · 숨김이 바뀌면 부른다. 구독을 끊는 함수를 돌려준다 */
  subscribe: (listener: () => void) => () => void;
};

export const documentVisibility: Visibility = {
  hidden: () => typeof document !== 'undefined' && document.visibilityState === 'hidden',
  subscribe: (listener) => {
    if (typeof document === 'undefined') return () => {};
    document.addEventListener('visibilitychange', listener);
    return () => document.removeEventListener('visibilitychange', listener);
  },
};

export type JobDetailController = {
  getState: () => JobDetailState;
  subscribe: (listener: () => void) => () => void;
  /** 화면에 붙을 때(effect). 다시 불러도 된다(StrictMode 의 start → stop → start) */
  start: () => void;
  /** 화면에서 떨어질 때. 예약한 읽기를 지우고 진행 중 응답을 버린다 */
  stop: () => void;
  /** 처음 읽기 실패 뒤 "다시 불러오기" */
  reload: () => void;
  cancel: () => Promise<void>;
};

/**
 * 작업 상세(#272)의 읽기 · 주기 갱신 · 취소. 화면(`JobDetailView`)은 상태를 그리기만 한다.
 *
 * - 실행 중(QUEUED · RUNNING)이면 `pollDelay` 뒤 다시 읽고, 끝나면(모르는 상태 포함) 멈춘다.
 * - 이미 작업을 보인 뒤 읽기가 일시 오류면 마지막 상태를 두고 안내하며, 연속 실패마다 간격을 두 배로(최대 30초) 늘려 계속 읽는다.
 *   401 · 403 · 404 는 화면을 바꾸고 멈춘다.
 * - 탭이 숨으면 예약한 읽기를 지우고, 다시 보이면 곧바로 한 번 읽은 뒤 이어 간다.
 * - 읽기마다 세대 번호를 붙여 늦게 온 이전 응답을 버린다. 취소를 보내는 순간 세대를 올려 그 전에 나간 읽기가
 *   취소 결과(CANCELLED)를 예전 상태(QUEUED)로 덮지 못하게 하고, 취소 중에는 새 읽기를 시작하지 않는다.
 */
export function createJobDetailController(
  jobId: string,
  visibility: Visibility = documentVisibility,
): JobDetailController {
  const valid = isJobId(jobId);
  let state: JobDetailState = {
    view: valid ? { kind: 'loading' } : { kind: 'notFound' },
    pollFailed: false,
    cancelling: false,
    cancelError: '',
  };
  const listeners = new Set<() => void>();
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let running = false;
  let failures = 0;
  /** 숨은 탭에서 예약을 건너뛰었다 — 다시 보이면 곧바로 읽는다 */
  let deferred = false;
  let unsubscribeVisibility: (() => void) | null = null;

  const set = (patch: Partial<JobDetailState>) => {
    state = { ...state, ...patch };
    for (const listener of listeners) listener();
  };

  const clearTimer = () => {
    clearTimeout(timer);
    timer = undefined;
  };

  const shownJob = () => (state.view.kind === 'ready' ? state.view.job : null);

  /** 다음 읽기를 예약한다. 끝난 작업 · 멈춤 · 취소 중이면 하지 않고, 숨은 탭이면 보일 때로 미룬다 */
  const schedule = () => {
    clearTimer();
    const job = shownJob();
    if (!running || state.cancelling || !job || !isActiveJob(job)) return;
    if (visibility.hidden()) {
      deferred = true;
      return;
    }
    timer = setTimeout(load, pollDelay(failures));
  };

  function load() {
    clearTimer();
    if (!running || !valid || state.cancelling) return;
    deferred = false;
    const mine = ++generation;
    apiFetch<JobItem>(jobApiPath(jobId)).then(
      (job) => {
        if (mine !== generation || !running) return;
        failures = 0;
        set({ view: { kind: 'ready', job }, pollFailed: false });
        schedule();
      },
      (cause: unknown) => {
        if (mine !== generation || !running) return;
        const failure = loadFailure(cause);
        if (failure.kind === 'failed' && shownJob()) {
          failures += 1;
          set({ pollFailed: true });
          schedule();
          return;
        }
        set({ view: failure, pollFailed: false });
      },
    );
  }

  const onVisibilityChange = () => {
    if (visibility.hidden()) {
      // 예약을 지운다 — 보일 때 곧바로 읽는다
      if (timer !== undefined) {
        clearTimer();
        deferred = true;
      }
      return;
    }
    if (deferred) load();
  };

  return {
    getState: () => state,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    start: () => {
      if (running) return;
      running = true;
      unsubscribeVisibility = visibility.subscribe(onVisibilityChange);
      // 처음이거나 실행 중인 작업이면 읽는다(끝난 작업을 다시 붙일 때는 읽지 않는다)
      const job = shownJob();
      if (!job || isActiveJob(job)) load();
    },
    stop: () => {
      running = false;
      generation += 1;
      deferred = false;
      clearTimer();
      unsubscribeVisibility?.();
      unsubscribeVisibility = null;
    },
    reload: () => {
      failures = 0;
      set({ view: { kind: 'loading' }, pollFailed: false });
      load();
    },
    cancel: async () => {
      if (state.cancelling || !valid) return;
      // 그 전에 나간 읽기 · 예약을 버린다 — 늦게 온 응답이 취소 결과를 덮지 못하게
      generation += 1;
      clearTimer();
      set({ cancelling: true, cancelError: '' });
      const mine = generation;
      try {
        const job = await apiFetch<JobItem>(cancelJobApiPath(jobId), { method: 'POST' });
        if (mine !== generation || !running) return;
        failures = 0;
        // 응답 본문으로 바로 갱신한다 — QUEUED 는 CANCELLED, RUNNING 은 상태 그대로 notice · cancellable=false
        set({ view: { kind: 'ready', job }, pollFailed: false });
      } catch (cause) {
        if (mine !== generation || !running) return;
        const failure = cancelFailure(cause);
        if (failure.kind === 'denied') set({ view: { kind: 'denied', denied: failure.denied } });
        else if (failure.kind === 'notFound') set({ view: { kind: 'notFound' } });
        else {
          set({ cancelError: failure.notice });
          if (failure.kind === 'refresh') {
            set({ cancelling: false });
            load();
            return;
          }
        }
      } finally {
        if (state.cancelling) set({ cancelling: false });
      }
      schedule();
    },
  };
}
