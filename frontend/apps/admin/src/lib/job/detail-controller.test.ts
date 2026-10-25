import { ApiError } from '@cf/api-client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CANCEL_CONFLICT_NOTICE, JOB_POLL_INTERVAL_MS, JOB_POLL_MAX_INTERVAL_MS } from './detail';
import { createJobDetailController, type Visibility } from './detail-controller';
import { jobItem as job } from './test-fixtures';
import type { JobItem } from './types';

const { apiFetch } = vi.hoisted(() => ({ apiFetch: vi.fn() }));
vi.mock('@cf/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@cf/api-client')>()),
  apiFetch,
}));

const JOB_ID = '42';
const DETAIL = `/api/v1/admin/jobs/${JOB_ID}`;
const CANCEL = `${DETAIL}/cancel`;

/** 손으로 푸는 응답 */
function pending<T>() {
  let resolve!: (value: T) => void;
  let reject!: (cause: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/** 테스트가 바꾸는 탭 보임 상태 */
function fakeVisibility() {
  let hidden = false;
  const listeners = new Set<() => void>();
  const visibility: Visibility = {
    hidden: () => hidden,
    subscribe: (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
  return {
    visibility,
    setHidden(next: boolean) {
      hidden = next;
      for (const listener of listeners) listener();
    },
  };
}

const getCalls = () => apiFetch.mock.calls.filter(([path]) => path === DETAIL).length;
const flush = () => vi.advanceTimersByTimeAsync(0);

beforeEach(() => {
  vi.useFakeTimers();
  apiFetch.mockReset();
});

afterEach(() => {
  vi.useRealTimers();
});

describe('createJobDetailController — 주기 갱신', () => {
  it('RUNNING 이면 간격마다 다시 읽고, SUCCEEDED 가 되면 더 읽지 않는다', async () => {
    apiFetch.mockResolvedValueOnce(job('RUNNING')).mockResolvedValueOnce(job('SUCCEEDED'));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    expect(controller.getState().view).toMatchObject({
      kind: 'ready',
      job: { status: { code: 'RUNNING' } },
    });
    expect(getCalls()).toBe(1);

    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    expect(getCalls()).toBe(2);
    expect(controller.getState().view).toMatchObject({ job: { status: { code: 'SUCCEEDED' } } });
    expect(vi.getTimerCount()).toBe(0);

    await vi.advanceTimersByTimeAsync(JOB_POLL_MAX_INTERVAL_MS * 2);
    expect(getCalls()).toBe(2);
  });

  it('끝난 작업은 처음 한 번만 읽는다', async () => {
    apiFetch.mockResolvedValueOnce(job('FAILED', { notice: '외부 연결이 늦어 실패했습니다.' }));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    expect(vi.getTimerCount()).toBe(0);
    expect(getCalls()).toBe(1);
  });

  it('화면에서 떨어지면(stop) 예약한 읽기가 없고, 진행 중 응답도 버린다', async () => {
    apiFetch.mockResolvedValueOnce(job('RUNNING'));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    expect(vi.getTimerCount()).toBe(1);

    const late = pending<JobItem>();
    apiFetch.mockReturnValueOnce(late.promise);
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    controller.stop();
    late.resolve(job('SUCCEEDED'));
    await flush();

    expect(vi.getTimerCount()).toBe(0);
    expect(controller.getState().view).toMatchObject({ job: { status: { code: 'RUNNING' } } });
    await vi.advanceTimersByTimeAsync(JOB_POLL_MAX_INTERVAL_MS);
    expect(getCalls()).toBe(2);
  });

  it('잘못된 작업 아이디는 읽지 않고 찾을 수 없음이다', async () => {
    const controller = createJobDetailController('abc', fakeVisibility().visibility);
    controller.start();
    await flush();
    expect(apiFetch).not.toHaveBeenCalled();
    expect(controller.getState().view).toEqual({ kind: 'notFound' });
  });
});

describe('createJobDetailController — 읽기 실패', () => {
  it('이미 보인 뒤 실패하면 마지막 상태를 두고 안내하며, 간격을 늘려(최대 30초) 계속 읽는다', async () => {
    const unavailable = new ApiError('unavailable', 503, 'BFF_002', null);
    apiFetch.mockResolvedValueOnce(job('RUNNING')).mockRejectedValue(unavailable);
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();

    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    expect(getCalls()).toBe(2);
    expect(controller.getState()).toMatchObject({
      pollFailed: true,
      view: { kind: 'ready', job: { status: { code: 'RUNNING' } } },
    });

    // 실패 1번 → 6초 뒤
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS * 2 - 1);
    expect(getCalls()).toBe(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(getCalls()).toBe(3);
    // 실패 2번 → 12초, 3번 → 24초, 4번 → 30초(상한)
    await vi.advanceTimersByTimeAsync(12_000);
    expect(getCalls()).toBe(4);
    await vi.advanceTimersByTimeAsync(24_000);
    expect(getCalls()).toBe(5);
    await vi.advanceTimersByTimeAsync(JOB_POLL_MAX_INTERVAL_MS - 1);
    expect(getCalls()).toBe(5);
    await vi.advanceTimersByTimeAsync(1);
    expect(getCalls()).toBe(6);

    // 다시 성공하면 안내를 지우고 기본 간격으로 돌아간다
    apiFetch.mockReset();
    apiFetch.mockResolvedValue(job('RUNNING'));
    await vi.advanceTimersByTimeAsync(JOB_POLL_MAX_INTERVAL_MS);
    expect(controller.getState().pollFailed).toBe(false);
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    expect(getCalls()).toBe(2);
    controller.stop();
  });

  it('처음 읽기 실패는 다시 불러오기 화면이고 예약하지 않는다', async () => {
    apiFetch.mockRejectedValueOnce(new ApiError('unavailable', 503, 'BFF_002', null));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    expect(controller.getState().view).toEqual({ kind: 'failed' });
    expect(vi.getTimerCount()).toBe(0);

    apiFetch.mockResolvedValueOnce(job('SUCCEEDED'));
    controller.reload();
    expect(controller.getState().view).toEqual({ kind: 'loading' });
    await flush();
    expect(controller.getState().view).toMatchObject({ kind: 'ready' });
  });

  it('보인 뒤라도 404 는 찾을 수 없음으로 바꾸고 멈춘다', async () => {
    apiFetch
      .mockResolvedValueOnce(job('QUEUED'))
      .mockRejectedValueOnce(new ApiError('notFound', 404, 'JOB_001', null));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    expect(controller.getState().view).toEqual({ kind: 'notFound' });
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe('createJobDetailController — 숨은 탭', () => {
  it('숨으면 예약을 지우고, 다시 보이면 곧바로 한 번 읽은 뒤 이어 간다', async () => {
    const tab = fakeVisibility();
    apiFetch.mockResolvedValue(job('RUNNING'));
    const controller = createJobDetailController(JOB_ID, tab.visibility);
    controller.start();
    await flush();
    expect(vi.getTimerCount()).toBe(1);

    tab.setHidden(true);
    expect(vi.getTimerCount()).toBe(0);
    await vi.advanceTimersByTimeAsync(JOB_POLL_MAX_INTERVAL_MS * 2);
    expect(getCalls()).toBe(1);

    tab.setHidden(false);
    await flush();
    expect(getCalls()).toBe(2);
    expect(vi.getTimerCount()).toBe(1);
    controller.stop();
  });

  it('숨은 동안 도착한 응답은 다음 읽기를 예약하지 않고 보일 때로 미룬다', async () => {
    const tab = fakeVisibility();
    const first = pending<JobItem>();
    apiFetch.mockReturnValueOnce(first.promise).mockResolvedValue(job('RUNNING'));
    const controller = createJobDetailController(JOB_ID, tab.visibility);
    controller.start();
    tab.setHidden(true);
    first.resolve(job('RUNNING'));
    await flush();
    expect(vi.getTimerCount()).toBe(0);

    tab.setHidden(false);
    await flush();
    expect(getCalls()).toBe(2);
    controller.stop();
  });

  it('끝난 작업은 다시 보여도 읽지 않는다', async () => {
    const tab = fakeVisibility();
    apiFetch.mockResolvedValue(job('SUCCEEDED'));
    const controller = createJobDetailController(JOB_ID, tab.visibility);
    controller.start();
    await flush();
    tab.setHidden(true);
    tab.setHidden(false);
    await flush();
    expect(getCalls()).toBe(1);
  });
});

describe('createJobDetailController — 취소', () => {
  it('취소 중에 늦게 온 이전 읽기(QUEUED)가 취소 결과(CANCELLED)를 덮지 않는다', async () => {
    apiFetch.mockResolvedValueOnce(job('QUEUED'));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();

    // 다음 주기 읽기가 나간 채로 취소한다
    const staleGet = pending<JobItem>();
    apiFetch.mockReturnValueOnce(staleGet.promise);
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    const cancelled = pending<JobItem>();
    apiFetch.mockReturnValueOnce(cancelled.promise);
    const done = controller.cancel();
    expect(controller.getState().cancelling).toBe(true);

    cancelled.resolve(job('CANCELLED'));
    await done;
    staleGet.resolve(job('QUEUED'));
    await flush();

    expect(apiFetch).toHaveBeenCalledWith(CANCEL, { method: 'POST' });
    expect(controller.getState()).toMatchObject({
      cancelling: false,
      cancelError: '',
      view: { kind: 'ready', job: { status: { code: 'CANCELLED' } } },
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it('취소 중에는 주기 읽기를 시작하지 않는다', async () => {
    apiFetch.mockResolvedValueOnce(job('RUNNING'));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    const cancelled = pending<JobItem>();
    apiFetch.mockReturnValueOnce(cancelled.promise);
    const done = controller.cancel();
    await vi.advanceTimersByTimeAsync(JOB_POLL_MAX_INTERVAL_MS);
    expect(getCalls()).toBe(1);
    cancelled.resolve(job('RUNNING', { cancellable: false, notice: '취소를 요청했습니다' }));
    await done;
    expect(vi.getTimerCount()).toBe(1);
    controller.stop();
  });

  it('RUNNING 취소는 응답 본문(상태 그대로 · 서버 안내 · cancellable=false)으로 바로 갱신하고 주기 읽기를 이어 간다', async () => {
    const requested = job('RUNNING', { cancellable: false, notice: '취소를 요청했습니다' });
    apiFetch
      .mockResolvedValueOnce(job('RUNNING', { progressPercent: 40 }))
      .mockResolvedValueOnce(requested)
      .mockResolvedValueOnce(job('CANCELLED', { cancellable: false, notice: null }));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    await controller.cancel();
    expect(controller.getState()).toMatchObject({
      cancelError: '',
      view: { kind: 'ready', job: requested },
    });
    expect(getCalls()).toBe(1);
    expect(vi.getTimerCount()).toBe(1);

    // 서버 안내는 다시 읽은 작업의 값이 그대로 바꾼다 — 클라이언트가 고쳐 쓰지 않는다
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    expect(controller.getState().view).toMatchObject({
      job: { status: { code: 'CANCELLED' }, notice: null, cancellable: false },
    });
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each([
    ['denied', new ApiError('forbidden', 403, null, null), { kind: 'denied', denied: 'forbidden' }],
    ['notFound', new ApiError('notFound', 404, 'JOB_001', null), { kind: 'notFound' }],
  ])('취소가 %s 로 끝나면 진행 중 읽기를 버리고 멈춘다', async (_, error, view) => {
    apiFetch.mockResolvedValueOnce(job('RUNNING'));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    const staleGet = pending<JobItem>();
    apiFetch.mockReturnValueOnce(staleGet.promise);
    await vi.advanceTimersByTimeAsync(JOB_POLL_INTERVAL_MS);
    apiFetch.mockRejectedValueOnce(error);
    await controller.cancel();
    staleGet.resolve(job('RUNNING'));
    await flush();
    expect(controller.getState().view).toEqual(view);
    expect(vi.getTimerCount()).toBe(0);
  });

  it('409 JOB_021 은 안내만 하고 주기 읽기를 이어 간다', async () => {
    apiFetch
      .mockResolvedValueOnce(job('RUNNING'))
      .mockRejectedValueOnce(new ApiError('conflict', 409, 'JOB_021', null));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    await controller.cancel();
    expect(controller.getState()).toMatchObject({
      cancelError: CANCEL_CONFLICT_NOTICE,
      view: { kind: 'ready' },
    });
    expect(vi.getTimerCount()).toBe(1);
    controller.stop();
  });

  it('409 JOB_022 · 504 는 자동으로 다시 보내지 않고 상태를 곧바로 다시 읽는다', async () => {
    apiFetch
      .mockResolvedValueOnce(job('RUNNING'))
      .mockRejectedValueOnce(new ApiError('conflict', 409, 'JOB_022', null))
      .mockResolvedValueOnce(job('SUCCEEDED'));
    const controller = createJobDetailController(JOB_ID, fakeVisibility().visibility);
    controller.start();
    await flush();
    await controller.cancel();
    await flush();
    expect(getCalls()).toBe(2);
    expect(apiFetch.mock.calls.filter(([path]) => path === CANCEL)).toHaveLength(1);
    expect(controller.getState().view).toMatchObject({ job: { status: { code: 'SUCCEEDED' } } });
  });
});
