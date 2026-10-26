import { ApiError, type EventStreamOptions } from '@cf/api-client';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Visibility } from '@/lib/job/detail-controller';
import {
  COUNT_SYNC_DELAY_MS,
  createNotificationCenterController,
  MARK_READ_FAILED,
  RECONNECT_BACKOFF_MS,
  RECONNECT_DELAY_MS,
  reconnectDelay,
  STABLE_STREAM_MS,
  TOAST_DURATION_MS,
} from './center-controller';
import type { NotificationItem } from './types';

const { apiFetch, readEventStream } = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  readEventStream: vi.fn(),
}));
vi.mock('@cf/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@cf/api-client')>()),
  apiFetch,
  readEventStream,
}));

const BASE = '/api/v1/admin/notifications';
const COUNT = `${BASE}/unread-count`;
const page = (n: number) => `${BASE}?page=${n}&size=20`;

function item(id: string, overrides: Partial<NotificationItem> = {}): NotificationItem {
  return {
    notificationId: id,
    kind: { code: 'JOB_SUCCEEDED', name: '작업 완료', description: '' },
    severity: { code: 'INFO', name: '안내', description: '' },
    title: `알림 ${id}`,
    linkPath: `/admin/jobs/${id}`,
    read: false,
    createdAt: '2026-10-26T01:00:00Z',
    ...overrides,
  };
}

const pageOf = (contents: NotificationItem[], totalPages = 1) => ({
  contents,
  page: 0,
  size: 20,
  totalElements: contents.length,
  totalPages,
});

/** 테스트가 여닫는 스트림 하나 */
type FakeStream = {
  options: EventStreamOptions;
  open: () => void;
  emit: (data: unknown) => void;
  end: () => void;
  fail: (cause: unknown) => void;
};
let streams: FakeStream[] = [];

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

/** 경로별 응답. 값이 함수면 부를 때마다 계산한다 */
let routes: Record<string, unknown> = {};
const calls = (path: string) => apiFetch.mock.calls.filter(([p]) => p === path).length;
const flush = () => vi.advanceTimersByTimeAsync(0);

beforeEach(() => {
  vi.useFakeTimers();
  streams = [];
  routes = { [COUNT]: { count: 0 }, [page(0)]: pageOf([]) };
  apiFetch.mockReset();
  apiFetch.mockImplementation((path: string) => {
    const value = routes[path];
    const resolved = typeof value === 'function' ? (value as () => unknown)() : value;
    return resolved instanceof Error ? Promise.reject(resolved) : Promise.resolve(resolved);
  });
  readEventStream.mockReset();
  readEventStream.mockImplementation(
    (_path: string, options: EventStreamOptions) =>
      new Promise<void>((resolve, reject) => {
        options.signal.addEventListener('abort', () => resolve());
        streams.push({
          options,
          open: () => options.onOpen?.(),
          emit: (data) =>
            options.onEvent({ event: 'notification', data: JSON.stringify(data), id: null }),
          end: () => resolve(),
          fail: reject,
        });
      }),
  );
});

afterEach(() => {
  vi.useRealTimers();
});

async function started(visibility = fakeVisibility().visibility) {
  const controller = createNotificationCenterController(visibility);
  controller.start();
  await flush();
  return controller;
}

describe('reconnectDelay', () => {
  it('정상 종료는 1초, 실패는 2초부터 두 배씩 최대 30초', () => {
    expect(reconnectDelay(0)).toBe(RECONNECT_DELAY_MS);
    expect(reconnectDelay(1)).toBe(2000);
    expect(reconnectDelay(3)).toBe(8000);
    expect(reconnectDelay(10)).toBe(30_000);
  });
});

describe('createNotificationCenterController — 첫 읽기 · 스트림', () => {
  it('읽지 않은 수와 첫 쪽을 읽고 스트림을 연다. 첫 읽기는 토스트를 띄우지 않는다', async () => {
    routes[COUNT] = { count: 2 };
    routes[page(0)] = pageOf([item('5'), item('4', { read: true }), item('3')], 2);

    const controller = await started();
    streams[0]!.open();

    const state = controller.getState();
    expect(state.unreadCount).toBe(2);
    expect(state.items.map((i) => i.notificationId)).toEqual(['5', '4', '3']);
    expect(state.hasMore).toBe(true);
    expect(state.toasts).toEqual([]);
    expect(state.connection).toBe('open');
    expect(readEventStream).toHaveBeenCalledWith(
      `${BASE}/stream`,
      expect.objectContaining({ signal: expect.any(AbortSignal) }),
    );
  });

  it('알림이 오면 맨 위에 넣고 수를 올리고 토스트를 띄운 뒤, 잠시 뒤 서버 수로 맞춘다', async () => {
    routes[page(0)] = pageOf([item('5', { read: true })]);
    const controller = await started();
    streams[0]!.open();

    routes[COUNT] = { count: 7 };
    streams[0]!.emit(item('6'));
    expect(controller.getState().items[0]!.notificationId).toBe('6');
    expect(controller.getState().unreadCount).toBe(1);
    expect(controller.getState().toasts).toMatchObject([
      { kind: 'item', item: { notificationId: '6' } },
    ]);

    await vi.advanceTimersByTimeAsync(COUNT_SYNC_DELAY_MS);
    expect(controller.getState().unreadCount).toBe(7);

    // 같은 알림이 다시 와도 수 · 토스트를 늘리지 않는다
    streams[0]!.emit(item('6'));
    expect(controller.getState().toasts).toHaveLength(1);
  });

  it('토스트는 저절로 닫히고, 긴급은 직접 닫을 때까지 남는다', async () => {
    const controller = await started();
    streams[0]!.open();
    streams[0]!.emit(item('1'));
    streams[0]!.emit(item('2', { severity: { code: 'CRITICAL', name: '긴급', description: '' } }));
    expect(controller.getState().toasts).toHaveLength(2);

    await vi.advanceTimersByTimeAsync(TOAST_DURATION_MS);
    const left = controller.getState().toasts;
    expect(left).toMatchObject([{ item: { notificationId: '2' } }]);

    controller.dismissToast(left[0]!.key);
    expect(controller.getState().toasts).toEqual([]);
  });

  it('토스트 위에 머무는 동안은 저절로 닫히지 않고, 떠나면 다시 센다', async () => {
    const controller = await started();
    streams[0]!.open();
    streams[0]!.emit(item('1'));
    controller.holdToasts(true);
    await vi.advanceTimersByTimeAsync(TOAST_DURATION_MS * 2);
    expect(controller.getState().toasts).toHaveLength(1);
    controller.holdToasts(false);
    await vi.advanceTimersByTimeAsync(TOAST_DURATION_MS);
    expect(controller.getState().toasts).toEqual([]);
  });

  it('모양이 다른 data 는 버리고 목록으로 맞춘다', async () => {
    const controller = await started();
    streams[0]!.open();
    const before = calls(page(0));
    streams[0]!.options.onEvent({ event: 'notification', data: '{"x":1}', id: null });
    await flush();
    expect(controller.getState().toasts).toEqual([]);
    expect(calls(page(0))).toBe(before + 1);
  });
});

describe('createNotificationCenterController — 다시 연결 · 따라잡기', () => {
  it('서버가 닫으면 1초 뒤 다시 열고, 열리면 놓친 읽지 않은 알림을 토스트로 따라잡는다', async () => {
    routes[page(0)] = pageOf([item('5', { read: true })]);
    const controller = await started();
    streams[0]!.open();
    await vi.advanceTimersByTimeAsync(STABLE_STREAM_MS);

    streams[0]!.end();
    await flush();
    expect(controller.getState().connection).toBe('waiting');

    routes[COUNT] = { count: 2 };
    routes[page(0)] = pageOf([item('7'), item('6'), item('5', { read: true })]);
    await vi.advanceTimersByTimeAsync(RECONNECT_DELAY_MS);
    expect(streams).toHaveLength(2);
    streams[1]!.open();
    await flush();

    const state = controller.getState();
    expect(state.unreadCount).toBe(2);
    expect(state.items.map((i) => i.notificationId)).toEqual(['7', '6', '5']);
    // 오래된 것부터 쌓아 최신이 맨 아래
    expect(state.toasts.map((t) => (t.kind === 'item' ? t.item.notificationId : ''))).toEqual([
      '6',
      '7',
    ]);
  });

  it('놓친 알림이 많으면 한 장으로 알린다', async () => {
    routes[page(0)] = pageOf([item('1', { read: true })]);
    const controller = await started();
    streams[0]!.open();
    await vi.advanceTimersByTimeAsync(STABLE_STREAM_MS);
    streams[0]!.end();
    routes[page(0)] = pageOf([item('5'), item('4'), item('3'), item('2')]);
    await vi.advanceTimersByTimeAsync(RECONNECT_DELAY_MS);
    streams[1]!.open();
    await flush();
    expect(controller.getState().toasts).toMatchObject([{ kind: 'summary', count: 4 }]);
  });

  it('연결 실패가 이어지면 간격을 두 배로 늘리고, 열리면 처음 간격으로 돌아간다', async () => {
    const controller = await started();
    streams[0]!.fail(new ApiError('network', null, null, null));
    await flush();
    expect(controller.getState().connection).toBe('waiting');

    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS - 1);
    expect(streams).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams).toHaveLength(2);

    streams[1]!.fail(new ApiError('unavailable', 503, 'BFF_002', null));
    await flush();
    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS * 2 - 1);
    expect(streams).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams).toHaveLength(3);

    // 충분히 열려 있다 닫히면 정상 종료 — 처음 간격(1초)으로 돌아간다
    streams[2]!.open();
    await vi.advanceTimersByTimeAsync(STABLE_STREAM_MS);
    streams[2]!.end();
    await flush();
    await vi.advanceTimersByTimeAsync(RECONNECT_DELAY_MS);
    expect(streams).toHaveLength(4);
  });

  it('열리자마자 닫히는 스트림은 실패로 세어 간격을 늘린다', async () => {
    await started();
    streams[0]!.open();
    streams[0]!.end();
    await flush();
    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS - 1);
    expect(streams).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams).toHaveLength(2);

    streams[1]!.open();
    streams[1]!.end();
    await flush();
    await vi.advanceTimersByTimeAsync(RECONNECT_BACKOFF_MS * 2 - 1);
    expect(streams).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams).toHaveLength(3);
  });

  it('429 는 Retry-After 이상 기다린다', async () => {
    await started();
    streams[0]!.fail(new ApiError('rateLimited', 429, 'GATEWAY_429', null, [], 10));
    await flush();
    await vi.advanceTimersByTimeAsync(9999);
    expect(streams).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams).toHaveLength(2);
  });

  it('따라잡기 응답을 기다리는 사이 스트림으로 새 알림이 와도 끊긴 사이 알림을 토스트로 알린다', async () => {
    routes[page(0)] = pageOf([item('5', { read: true })]);
    const controller = await started();
    streams[0]!.open();
    await vi.advanceTimersByTimeAsync(STABLE_STREAM_MS);
    streams[0]!.end();
    await flush();

    // 첫 쪽 응답을 손으로 푼다
    let resolvePage!: (value: unknown) => void;
    routes[page(0)] = () => new Promise((resolve) => (resolvePage = resolve));
    await vi.advanceTimersByTimeAsync(RECONNECT_DELAY_MS);
    streams[1]!.open();
    streams[1]!.emit(item('8'));
    resolvePage(pageOf([item('8'), item('7'), item('6'), item('5', { read: true })]));
    await flush();

    const ids = controller
      .getState()
      .toasts.map((t) => (t.kind === 'item' ? t.item.notificationId : t.kind));
    expect(ids).toEqual(['8', '6', '7']);
  });

  it.each([
    [new ApiError('unauthorized', 401, 'SECURITY_001', null), 'unauthorized'],
    [new ApiError('forbidden', 403, 'SECURITY_002', null), 'forbidden'],
  ])('401 · 403 이면 멈추고 다시 열지 않는다 (%#)', async (cause, denied) => {
    const controller = await started();
    streams[0]!.fail(cause);
    await flush();
    await vi.advanceTimersByTimeAsync(60_000);
    expect(streams).toHaveLength(1);
    expect(controller.getState()).toMatchObject({ denied, connection: 'stopped' });
  });

  it('탭이 숨으면 끊고, 다시 보이면 열어 그 사이를 따라잡는다', async () => {
    const { visibility, setHidden } = fakeVisibility();
    const controller = await started(visibility);
    streams[0]!.open();

    setHidden(true);
    await flush();
    expect(streams[0]!.options.signal.aborted).toBe(true);
    expect(controller.getState().connection).toBe('paused');
    await vi.advanceTimersByTimeAsync(60_000);
    expect(streams).toHaveLength(1);

    routes[page(0)] = pageOf([item('9')]);
    setHidden(false);
    expect(streams).toHaveLength(2);
    streams[1]!.open();
    await flush();
    expect(controller.getState().toasts).toMatchObject([{ item: { notificationId: '9' } }]);
  });

  it('stop 하면 스트림을 끊고 다시 열지 않는다', async () => {
    const controller = await started();
    controller.stop();
    await flush();
    expect(streams[0]!.options.signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(60_000);
    expect(streams).toHaveLength(1);
  });
});

describe('createNotificationCenterController — 읽음', () => {
  it('한 건 읽음은 응답으로 바꾸고 수를 하나 내린 뒤 서버 수로 맞춘다', async () => {
    routes[COUNT] = { count: 2 };
    routes[page(0)] = pageOf([item('2'), item('1')]);
    const controller = await started();
    routes[`${BASE}/2/read`] = item('2', { read: true });
    routes[COUNT] = { count: 1 };

    const pending = controller.markRead('2');
    expect(apiFetch).toHaveBeenLastCalledWith(`${BASE}/2/read`, { method: 'POST' });
    await pending;
    expect(controller.getState().items[0]!.read).toBe(true);
    expect(controller.getState().unreadCount).toBe(1);
  });

  it('없는 알림(404)은 목록에서 빼고, 그 밖 실패는 안내한다', async () => {
    routes[page(0)] = pageOf([item('2'), item('1')]);
    const controller = await started();
    routes[`${BASE}/2/read`] = new ApiError('notFound', 404, 'NOTIFICATION_001', null);
    await controller.markRead('2');
    expect(controller.getState().items.map((i) => i.notificationId)).toEqual(['1']);

    routes[`${BASE}/1/read`] = new ApiError('server', 500, null, null);
    await controller.markRead('1');
    expect(controller.getState().actionError).toBe(MARK_READ_FAILED);
  });

  it('모두 읽음은 응답 수(0)로 바꾸고 목록을 모두 읽음으로', async () => {
    routes[COUNT] = { count: 2 };
    routes[page(0)] = pageOf([item('2'), item('1')]);
    const controller = await started();
    routes[`${BASE}/read-all`] = { count: 0 };

    await controller.markAllRead();
    expect(controller.getState().unreadCount).toBe(0);
    expect(controller.getState().items.every((i) => i.read)).toBe(true);
  });

  it('모두 읽음 사이 늦게 온 수 읽기는 버리고, 보내는 사이 온 알림은 읽음으로 바꾸지 않는다', async () => {
    routes[COUNT] = { count: 2 };
    routes[page(0)] = pageOf([item('2'), item('1')]);
    const controller = await started();
    streams[0]!.open();

    // 패널을 열어 나간 수 읽기가 늦게 온다
    let resolveCount!: (value: unknown) => void;
    routes[COUNT] = () => new Promise((resolve) => (resolveCount = resolve));
    controller.refresh();
    let resolveReadAll!: (value: unknown) => void;
    routes[`${BASE}/read-all`] = () => new Promise((resolve) => (resolveReadAll = resolve));
    const pending = controller.markAllRead();
    streams[0]!.emit(item('3'));
    routes[COUNT] = { count: 1 };
    resolveReadAll({ count: 0 });
    await pending;
    resolveCount({ count: 2 });
    await flush();

    const state = controller.getState();
    expect(state.items.map((i) => [i.notificationId, i.read])).toEqual([
      ['3', false],
      ['2', true],
      ['1', true],
    ]);
    expect(state.unreadCount).toBe(1);
  });

  it('읽음 409(버전 충돌)는 안내 없이 서버 값으로 맞춘다', async () => {
    routes[page(0)] = pageOf([item('1')]);
    const controller = await started();
    routes[`${BASE}/1/read`] = new ApiError('conflict', 409, 'NOTIFICATION_020', null);
    routes[page(0)] = pageOf([item('1', { read: true })]);
    await controller.markRead('1');
    await flush();
    expect(controller.getState().actionError).toBe('');
    expect(controller.getState().items[0]!.read).toBe(true);
  });

  it('더 보기는 다음 쪽을 이어 붙인다', async () => {
    routes[page(0)] = pageOf([item('3'), item('2')], 2);
    routes[page(1)] = { ...pageOf([item('1')], 2), page: 1 };
    const controller = await started();
    controller.loadMore();
    await flush();
    expect(controller.getState().items.map((i) => i.notificationId)).toEqual(['3', '2', '1']);
    expect(controller.getState().hasMore).toBe(false);
  });
});
