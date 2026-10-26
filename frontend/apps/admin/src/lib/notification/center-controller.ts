import { ApiError, apiFetch, readEventStream, type PageResponse } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import { documentVisibility, type Visibility } from '@/lib/job/detail-controller';
import {
  ADMIN_NOTIFICATIONS_PATH,
  isNotificationItem,
  NOTIFICATION_EVENT,
  NOTIFICATION_READ_ALL_PATH,
  NOTIFICATION_STREAM_PATH,
  NOTIFICATION_UNREAD_COUNT_PATH,
  readNotificationPath,
  type NotificationItem,
  type UnreadCount,
} from './types';

/** 패널 한 쪽 크기. 따라잡기도 첫 쪽(최신 20건)으로 한다 */
export const NOTIFICATION_PAGE_SIZE = 20;
/** 서버가 스트림을 정상으로 닫았을 때(기본 5분 수명) 다시 여는 간격 */
export const RECONNECT_DELAY_MS = 1000;
/** 연결 실패가 이어질 때 늘린 간격의 시작 · 상한 */
export const RECONNECT_BACKOFF_MS = 2000;
export const RECONNECT_MAX_DELAY_MS = 30_000;
/** 이보다 짧게 열렸다 닫힌 스트림은 실패로 센다 — 열리자마자 닫히는 설정 오류에서 1초마다 다시 열지 않게 */
export const STABLE_STREAM_MS = 10_000;
/** 알림이 오면 읽지 않은 수를 서버 값으로 맞추기까지 기다리는 시간(몰려오는 알림을 한 번에) */
export const COUNT_SYNC_DELAY_MS = 500;
/** 토스트가 저절로 닫히는 시간. 긴급(CRITICAL)은 직접 닫을 때까지 둔다 */
export const TOAST_DURATION_MS = 8000;
/** 한 번에 보이는 토스트 수. 넘치면 오래된 것부터 닫는다 */
export const MAX_TOASTS = 3;

export const NOTIFICATIONS_LOAD_FAILED = '알림을 불러오지 못했습니다.';
export const MARK_READ_FAILED = '읽음으로 바꾸지 못했습니다. 잠시 후 다시 시도해 주세요.';

/** 연결 상태. `paused` 는 탭이 숨어 일부러 끊은 것, `stopped` 는 401 · 403 으로 멈춘 것이다 */
export type ConnectionState = 'idle' | 'connecting' | 'open' | 'waiting' | 'paused' | 'stopped';

export type NotificationToast =
  | { key: string; kind: 'item'; item: NotificationItem }
  /** 끊긴 사이 온 알림이 많을 때 한 장으로 */
  | { key: string; kind: 'summary'; count: number };

export type NotificationCenterState = {
  /** 아직 모르면 null */
  unreadCount: number | null;
  /** 최신순. 첫 쪽과 "더 보기" 로 읽은 쪽, 스트림으로 온 알림을 합친 것 */
  items: NotificationItem[];
  listStatus: 'loading' | 'ready' | 'failed';
  hasMore: boolean;
  loadingMore: boolean;
  /** 읽음 · 모두 읽음 실패 안내. 없으면 '' */
  actionError: string;
  toasts: NotificationToast[];
  connection: ConnectionState;
  /** 401 · 403 — 알림을 멈춘다. 로그인 화면으로 보내지는 않는다(쓰던 폼을 지키려고. 화면 자신의 API 가 401 을 처리한다) */
  denied: AccessDenied | null;
};

export type NotificationCenterController = {
  getState: () => NotificationCenterState;
  subscribe: (listener: () => void) => () => void;
  /** 레이아웃에 붙을 때. 다시 불러도 된다(StrictMode) */
  start: () => void;
  stop: () => void;
  /** 패널을 열 때 — 첫 쪽을 다시 읽는다 */
  refresh: () => void;
  loadMore: () => void;
  markRead: (notificationId: string) => Promise<void>;
  markAllRead: () => Promise<void>;
  dismissToast: (key: string) => void;
  /** 토스트 위에 마우스 · 초점이 있는 동안 저절로 닫히지 않게 멈춘다(WCAG 2.2.1). 떠나면 처음부터 다시 센다 */
  holdToasts: (hold: boolean) => void;
};

/** 다음 연결까지 기다릴 시간. 정상 종료면 짧게, 연속 실패 n 번이면 2초 × 2^(n-1), 최대 30초 */
export function reconnectDelay(failures: number): number {
  if (failures <= 0) return RECONNECT_DELAY_MS;
  return Math.min(RECONNECT_MAX_DELAY_MS, RECONNECT_BACKOFF_MS * 2 ** (failures - 1));
}

/** 알림 아이디(Long 문자열) 비교. 읽을 수 없는 값은 가장 작게 본다 */
function idOf(value: string): bigint {
  try {
    return BigInt(value);
  } catch {
    return -1n;
  }
}

function listPath(page: number): string {
  return `${ADMIN_NOTIFICATIONS_PATH}?page=${page}&size=${NOTIFICATION_PAGE_SIZE}`;
}

/** 아이디로 합치고 최신순(아이디 내림차순)으로 둔다. 같은 아이디는 뒤에 온 값이 이긴다 */
function mergeItems(current: NotificationItem[], incoming: NotificationItem[]): NotificationItem[] {
  const byId = new Map(current.map((item) => [item.notificationId, item]));
  for (const item of incoming) byId.set(item.notificationId, item);
  return [...byId.values()].sort((a, b) => {
    const diff = idOf(b.notificationId) - idOf(a.notificationId);
    return diff > 0n ? 1 : diff < 0n ? -1 : 0;
  });
}

/**
 * 어드민 알림 센터(#214) — 읽지 않은 수 · 목록 · 읽음 · SSE 수신 · 토스트. 화면(`NotificationCenter`)은 상태를 그리기만 한다.
 *
 * - **스트림** — BFF `GET /api/v1/admin/notifications/stream`(SSE)을 `fetch` 로 연다. 알림이 오면 목록 맨 위에 넣고,
 *   읽지 않은 알림이면 토스트를 띄우고 읽지 않은 수를 하나 올린 뒤 잠시 뒤 서버 값으로 맞춘다.
 * - **다시 연결 · 따라잡기** — 서버가 닫으면(스트림 수명 5분) 1초 뒤, 연결 실패면 2초부터 두 배씩 최대 30초 뒤 다시 연다.
 *   열린 지 10초가 안 돼 닫힌 스트림은 실패로 센다(열리자마자 닫히는 설정 오류에서 1초마다 다시 열지 않게).
 *   끊겼다가 다시 열리면 읽지 않은 수와 첫 쪽을 다시 읽어 **놓친 알림을 따라잡는다** — 마지막으로 본 알림보다 새 읽지 않은 알림은 토스트로 알린다
 *   (3건까지 한 장씩, 넘치면 "새 알림 N건" 한 장). 첫 읽기는 기준만 잡고 토스트를 띄우지 않는다.
 * - **숨은 탭에서는 끊는다** — 탭마다 연결을 붙잡지 않게(HTTP/1.1 의 origin 당 연결 수 한도). 다시 보이면 열고 따라잡는다.
 * - 401 · 403 이면 연결과 읽기를 멈춘다(`denied`). 로그인 화면으로 보내지 않는다 — 헤더는 모든 화면에 있고, 쓰던 폼을 지켜야 한다.
 */
export function createNotificationCenterController(
  visibility: Visibility = documentVisibility,
): NotificationCenterController {
  let state: NotificationCenterState = {
    unreadCount: null,
    items: [],
    listStatus: 'loading',
    hasMore: false,
    loadingMore: false,
    actionError: '',
    toasts: [],
    connection: 'idle',
    denied: null,
  };
  const listeners = new Set<() => void>();
  let running = false;
  let abort: AbortController | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
  let countTimer: ReturnType<typeof setTimeout> | undefined;
  const toastTimers = new Map<string, ReturnType<typeof setTimeout>>();
  let failures = 0;
  /** 끊긴 적이 있다 — 다음에 열리면 따라잡는다 */
  let needsCatchUp = false;
  /** 마지막으로 본 알림 아이디(알림이 하나도 없으면 null). 따라잡기 토스트는 이보다 새 것만 */
  let lastSeenId: bigint | null = null;
  /** 첫 쪽을 한 번이라도 읽었다 — 그 전에는 따라잡기 기준이 없어 토스트를 띄우지 않는다 */
  let baselineReady = false;
  /** 첫 쪽 읽기 세대 — 늦게 온 이전 응답을 버린다 */
  let listGeneration = 0;
  let countGeneration = 0;
  let loadedPages = 0;
  let toastSeq = 0;
  let unsubscribeVisibility: (() => void) | null = null;
  /** 지금 스트림이 열린 시각. 열리기 전이면 null */
  let openedAt: number | null = null;
  /** 토스트 위에 마우스 · 초점이 있다 — 저절로 닫히지 않게 멈춘다 */
  let toastsHeld = false;

  const set = (patch: Partial<NotificationCenterState>) => {
    state = { ...state, ...patch };
    for (const listener of listeners) listener();
  };

  const see = (items: NotificationItem[]) => {
    for (const item of items) {
      const id = idOf(item.notificationId);
      if (lastSeenId === null || id > lastSeenId) lastSeenId = id;
    }
  };

  const deny = (denied: AccessDenied) => {
    abort?.abort();
    abort = null;
    clearTimeout(reconnectTimer);
    clearTimeout(countTimer);
    set({ denied, connection: 'stopped' });
  };

  /** 실패를 다룬다. 401 · 403 이면 멈추고 true */
  const handleDenied = (cause: unknown): boolean => {
    const denied = accessDeniedOf(cause);
    if (!denied) return false;
    deny(denied);
    return true;
  };

  const removeToast = (key: string) => {
    clearTimeout(toastTimers.get(key));
    toastTimers.delete(key);
    if (state.toasts.some((toast) => toast.key === key)) {
      set({ toasts: state.toasts.filter((toast) => toast.key !== key) });
    }
  };

  const armToast = (toast: NotificationToast) => {
    if (toastsHeld || toastTimers.has(toast.key)) return;
    if (toast.kind === 'item' && toast.item.severity.code === 'CRITICAL') return;
    toastTimers.set(
      toast.key,
      setTimeout(() => removeToast(toast.key), TOAST_DURATION_MS),
    );
  };

  const clearToastTimers = () => {
    for (const timer of toastTimers.values()) clearTimeout(timer);
    toastTimers.clear();
  };

  const pushToasts = (next: NotificationToast[]) => {
    if (next.length === 0) return;
    const toasts = [...state.toasts, ...next];
    const dropped = toasts.slice(0, Math.max(0, toasts.length - MAX_TOASTS));
    for (const toast of dropped) {
      clearTimeout(toastTimers.get(toast.key));
      toastTimers.delete(toast.key);
    }
    set({ toasts: toasts.slice(-MAX_TOASTS) });
    for (const toast of next) {
      if (state.toasts.includes(toast)) armToast(toast);
    }
  };

  const itemToast = (item: NotificationItem): NotificationToast => ({
    key: `t${++toastSeq}`,
    kind: 'item',
    item,
  });

  const loadCount = () => {
    clearTimeout(countTimer);
    const mine = ++countGeneration;
    apiFetch<UnreadCount>(NOTIFICATION_UNREAD_COUNT_PATH).then(
      ({ count }) => {
        if (mine !== countGeneration || !running) return;
        set({ unreadCount: count });
      },
      (cause: unknown) => {
        if (mine !== countGeneration || !running) return;
        // 일시 오류는 마지막 값을 둔다 — 다음 따라잡기 · 알림에서 다시 맞춘다
        handleDenied(cause);
      },
    );
  };

  /** 첫 쪽을 다시 읽어 합친다. `announce` 면 마지막으로 본 알림보다 새 읽지 않은 알림을 토스트로 알린다 */
  const loadFirstPage = (announce: boolean) => {
    const mine = ++listGeneration;
    // 기준은 요청할 때 잡는다 — 응답을 기다리는 사이 스트림으로 온 알림이 기준을 올려 끊긴 사이 알림을 가리지 않게
    const baseline = lastSeenId ?? -1n;
    if (state.items.length === 0) set({ listStatus: 'loading' });
    apiFetch<PageResponse<NotificationItem>>(listPath(0)).then(
      (page) => {
        if (mine !== listGeneration || !running) return;
        if (announce && baselineReady) {
          // 그 사이 스트림으로 와 이미 토스트를 띄운 알림(목록에 있음)은 뺀다
          const known = new Set(state.items.map((item) => item.notificationId));
          const missed = page.contents.filter(
            (item) =>
              !item.read && idOf(item.notificationId) > baseline && !known.has(item.notificationId),
          );
          // 목록은 최신순 — 토스트는 오래된 것부터 쌓아 최신이 맨 아래에 오게 한다
          pushToasts(
            missed.length > MAX_TOASTS
              ? [{ key: `t${++toastSeq}`, kind: 'summary', count: missed.length }]
              : missed.reverse().map(itemToast),
          );
        }
        see(page.contents);
        baselineReady = true;
        loadedPages = Math.max(loadedPages, 1);
        set({
          items: mergeItems(state.items, page.contents),
          listStatus: 'ready',
          hasMore: loadedPages < page.totalPages,
        });
      },
      (cause: unknown) => {
        if (mine !== listGeneration || !running) return;
        if (handleDenied(cause)) return;
        if (state.items.length === 0) set({ listStatus: 'failed' });
      },
    );
  };

  const catchUp = (announce: boolean) => {
    loadCount();
    loadFirstPage(announce);
  };

  const onNotification = (data: string) => {
    let item: unknown;
    try {
      item = JSON.parse(data);
    } catch {
      item = null;
    }
    // 모양이 다른 알림은 버리고 서버 목록으로 맞춘다
    if (!isNotificationItem(item)) {
      catchUp(false);
      return;
    }
    const known = state.items.some((it) => it.notificationId === item.notificationId);
    see([item]);
    set({
      items: mergeItems(state.items, [item]),
      unreadCount:
        !known && !item.read && state.unreadCount !== null
          ? state.unreadCount + 1
          : state.unreadCount,
    });
    if (!known && !item.read) pushToasts([itemToast(item)]);
    // 몰려오는 알림을 한 번에 서버 값으로 맞춘다
    clearTimeout(countTimer);
    countTimer = setTimeout(loadCount, COUNT_SYNC_DELAY_MS);
  };

  const scheduleReconnect = (delay: number) => {
    clearTimeout(reconnectTimer);
    if (!running || state.denied) return;
    if (visibility.hidden()) {
      set({ connection: 'paused' });
      return;
    }
    set({ connection: 'waiting' });
    reconnectTimer = setTimeout(connect, delay);
  };

  /** 이번 스트림이 충분히 오래 열려 있었는지. 열린 시각을 지운다 */
  const settleFailures = (): boolean => {
    const stable = openedAt !== null && Date.now() - openedAt >= STABLE_STREAM_MS;
    openedAt = null;
    return stable;
  };

  function connect() {
    clearTimeout(reconnectTimer);
    if (!running || state.denied || abort) return;
    const controller = new AbortController();
    abort = controller;
    set({ connection: 'connecting' });
    readEventStream(NOTIFICATION_STREAM_PATH, {
      signal: controller.signal,
      onOpen: () => {
        if (abort !== controller) return;
        openedAt = Date.now();
        set({ connection: 'open' });
        if (needsCatchUp) {
          needsCatchUp = false;
          catchUp(true);
        }
      },
      onEvent: (event) => {
        if (abort !== controller || event.event !== NOTIFICATION_EVENT) return;
        onNotification(event.data);
      },
    }).then(
      () => {
        if (abort !== controller) return;
        abort = null;
        needsCatchUp = true;
        // 오래 열려 있다 닫혔으면 정상 종료(스트림 수명)라 곧 다시 연다. 열리자마자 닫혔으면 실패로 세어 간격을 늘린다
        failures = settleFailures() ? 0 : failures + 1;
        scheduleReconnect(reconnectDelay(failures));
      },
      (cause: unknown) => {
        if (abort !== controller) return;
        abort = null;
        needsCatchUp = true;
        if (handleDenied(cause)) return;
        if (settleFailures()) failures = 0;
        failures += 1;
        const retryAfter =
          cause instanceof ApiError && cause.retryAfterSec !== null
            ? cause.retryAfterSec * 1000
            : 0;
        scheduleReconnect(Math.max(retryAfter, reconnectDelay(failures)));
      },
    );
  }

  const disconnect = () => {
    clearTimeout(reconnectTimer);
    if (abort) {
      abort.abort();
      abort = null;
      needsCatchUp = true;
      openedAt = null;
    }
  };

  const onVisibilityChange = () => {
    if (!running || state.denied) return;
    if (visibility.hidden()) {
      disconnect();
      set({ connection: 'paused' });
      return;
    }
    if (!abort) connect();
  };

  const actionFailed = (cause: unknown) => {
    if (handleDenied(cause)) return;
    set({ actionError: MARK_READ_FAILED });
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
      if (state.denied) return;
      // 처음이면 기준만 잡는다. StrictMode 로 다시 붙을 때는 끊긴 사이를 따라잡는다
      catchUp(baselineReady);
      // 숨은 탭이면 열지 않는다 — 보일 때 열고 그 사이를 따라잡는다
      needsCatchUp = visibility.hidden();
      if (needsCatchUp) set({ connection: 'paused' });
      else connect();
    },
    stop: () => {
      running = false;
      listGeneration += 1;
      countGeneration += 1;
      disconnect();
      clearTimeout(countTimer);
      clearToastTimers();
      unsubscribeVisibility?.();
      unsubscribeVisibility = null;
      set({ connection: 'idle' });
    },
    refresh: () => {
      if (!running || state.denied) return;
      set({ actionError: '' });
      catchUp(true);
    },
    loadMore: () => {
      if (!running || state.denied || state.loadingMore || !state.hasMore) return;
      const page = loadedPages;
      set({ loadingMore: true });
      apiFetch<PageResponse<NotificationItem>>(listPath(page)).then(
        (result) => {
          if (!running) return;
          see(result.contents);
          loadedPages = Math.max(loadedPages, page + 1);
          set({
            items: mergeItems(state.items, result.contents),
            hasMore: loadedPages < result.totalPages,
            loadingMore: false,
          });
        },
        (cause: unknown) => {
          if (!running) return;
          set({ loadingMore: false });
          if (!handleDenied(cause)) set({ actionError: NOTIFICATIONS_LOAD_FAILED });
        },
      );
    },
    markRead: async (notificationId) => {
      const before = state.items.find((item) => item.notificationId === notificationId);
      // 토스트에서 누른 알림은 목록에 없을 수 있다 — 그래도 서버에 읽음을 보낸다
      if (state.denied || before?.read) return;
      set({ actionError: '' });
      try {
        const item = await apiFetch<NotificationItem>(readNotificationPath(notificationId), {
          method: 'POST',
        });
        if (!running) return;
        const wasUnread = before !== undefined && !before.read && item.read;
        set({
          items: mergeItems(state.items, [item]),
          unreadCount:
            wasUnread && state.unreadCount !== null
              ? Math.max(0, state.unreadCount - 1)
              : state.unreadCount,
        });
        // 토스트에서 누른 알림처럼 목록에 없던 것도 있어 서버 값으로 맞춘다
        loadCount();
      } catch (cause) {
        if (!running) return;
        // 없는 알림(보존 기간이 지나 지워짐 등)은 목록에서 뺀다
        if (cause instanceof ApiError && cause.kind === 'notFound') {
          set({ items: state.items.filter((item) => item.notificationId !== notificationId) });
          loadCount();
          return;
        }
        // 버전 충돌(NOTIFICATION_020)은 그 사이 다른 곳(모두 읽음 · 다른 탭)에서 읽은 것이다 — 조용히 서버 값으로 맞춘다
        if (cause instanceof ApiError && cause.kind === 'conflict') {
          catchUp(false);
          return;
        }
        actionFailed(cause);
      }
    },
    markAllRead: async () => {
      if (state.denied) return;
      set({ actionError: '' });
      // 그 전에 나간 수 읽기는 버린다(늦게 와 0 을 예전 값으로 덮지 않게). 읽음으로 바꿀 범위는 보낼 때 본 알림까지다
      countGeneration += 1;
      clearTimeout(countTimer);
      const upTo = lastSeenId;
      try {
        const { count } = await apiFetch<UnreadCount>(NOTIFICATION_READ_ALL_PATH, {
          method: 'POST',
        });
        if (!running) return;
        const covered = (item: NotificationItem) =>
          upTo !== null && idOf(item.notificationId) <= upTo;
        set({
          unreadCount: count,
          items: state.items.map((item) =>
            item.read || !covered(item) ? item : { ...item, read: true },
          ),
        });
        // 보내는 사이 새 알림이 왔으면 서버 수로 다시 맞춘다
        if (state.items.some((item) => !item.read)) loadCount();
      } catch (cause) {
        if (!running) return;
        actionFailed(cause);
      }
    },
    dismissToast: removeToast,
    holdToasts: (hold) => {
      if (toastsHeld === hold) return;
      toastsHeld = hold;
      if (hold) clearToastTimers();
      else for (const toast of state.toasts) armToast(toast);
    },
  };
}
