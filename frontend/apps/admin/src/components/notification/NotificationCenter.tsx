'use client';

import { useEffect, useState, useSyncExternalStore } from 'react';
import { HeaderMenu } from '@/components/HeaderMenu';
import {
  createNotificationCenterController,
  NOTIFICATIONS_LOAD_FAILED,
} from '@/lib/notification/center-controller';
import type { NotificationItem } from '@/lib/notification/types';
import { NotificationList } from './NotificationList';
import { NotificationToasts } from './NotificationToasts';

/** 읽지 않은 수 표시. 100 이상은 99+ */
export function unreadBadgeText(count: number | null): string {
  if (count === null || count <= 0) return '';
  return count > 99 ? '99+' : String(count);
}

/**
 * 상단 바 알림 센터(#214) — 종 아이콘 · 읽지 않은 수 · 목록 패널(읽음, 모두 읽음, 더 보기) · 새 알림 토스트.
 * 읽기 · 스트림 · 다시 연결 · 따라잡기는 `createNotificationCenterController` 가 하고 여기는 그 상태를 그린다.
 * 콘솔 레이아웃에 붙어 화면을 옮겨도 연결이 이어진다(App Router 레이아웃은 화면 이동에 다시 그려지지 않는다).
 */
export function NotificationCenter() {
  const [controller] = useState(() => createNotificationCenterController());
  const state = useSyncExternalStore(
    controller.subscribe,
    controller.getState,
    controller.getState,
  );
  const [open, setOpen] = useState(false);

  useEffect(() => {
    controller.start();
    return () => controller.stop();
  }, [controller]);

  const badge = unreadBadgeText(state.unreadCount);
  const openItem = (item: NotificationItem) => {
    setOpen(false);
    void controller.markRead(item.notificationId);
  };
  const showAll = () => {
    controller.refresh();
    setOpen(true);
  };

  return (
    <>
      <HeaderMenu
        title="알림"
        buttonLabel={badge ? `알림, 읽지 않은 알림 ${badge}건` : '알림'}
        open={open}
        onOpenChange={setOpen}
        onOpen={controller.refresh}
        label={
          <>
            <BellIcon />
            {badge && (
              <span className="min-w-5 rounded-full bg-primary-fg px-1.5 text-center text-xs font-semibold leading-5 text-primary tabular-nums">
                {badge}
              </span>
            )}
          </>
        }
      >
        {() => (
          <div className="flex max-h-[min(32rem,calc(100dvh-6rem))] flex-col">
            <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
              <h2 className="text-sm font-semibold">알림</h2>
              <button
                type="button"
                onClick={() => void controller.markAllRead()}
                disabled={!state.unreadCount}
                className="rounded-md px-2 py-1 text-xs text-muted-fg hover:bg-muted hover:text-fg disabled:opacity-50 disabled:hover:bg-transparent"
              >
                모두 읽음
              </button>
            </div>
            {/* 연결 · 읽음 안내. live region 은 늘 두고 내용만 바꾼다 */}
            <p role="status" className="px-4 pt-3 text-xs text-danger empty:hidden">
              {state.denied === 'unauthorized'
                ? '로그인이 만료되어 알림을 받지 못합니다. 다시 로그인해 주세요.'
                : state.denied === 'forbidden'
                  ? '관리자 권한이 없어 알림을 받지 못합니다.'
                  : state.actionError}
            </p>
            <div className="min-h-0 flex-1 overflow-y-auto">
              {state.listStatus === 'loading' && state.items.length === 0 && (
                <p className="px-4 py-6 text-center text-sm text-muted-fg">불러오는 중…</p>
              )}
              {state.listStatus === 'failed' && state.items.length === 0 && (
                <div className="flex flex-col items-center gap-2 px-4 py-6">
                  <p className="text-sm text-danger">{NOTIFICATIONS_LOAD_FAILED}</p>
                  <button
                    type="button"
                    onClick={controller.refresh}
                    className="text-sm underline underline-offset-4"
                  >
                    다시 불러오기
                  </button>
                </div>
              )}
              {state.listStatus === 'ready' || state.items.length > 0 ? (
                <NotificationList
                  items={state.items}
                  onOpen={openItem}
                  onMarkRead={(item) => void controller.markRead(item.notificationId)}
                />
              ) : null}
              {state.hasMore && (
                <div className="border-t border-border px-4 py-2 text-center">
                  <button
                    type="button"
                    onClick={controller.loadMore}
                    disabled={state.loadingMore}
                    className="text-sm underline underline-offset-4 disabled:opacity-50"
                  >
                    {state.loadingMore ? '불러오는 중…' : '더 보기'}
                  </button>
                </div>
              )}
            </div>
            {state.connection === 'waiting' && (
              <p className="border-t border-border px-4 py-2 text-xs text-muted-fg">
                실시간 알림에 다시 연결하는 중입니다. 연결되면 놓친 알림을 불러옵니다.
              </p>
            )}
          </div>
        )}
      </HeaderMenu>
      <NotificationToasts
        toasts={state.toasts}
        onOpen={openItem}
        onDismiss={controller.dismissToast}
        onShowAll={showAll}
        onHold={controller.holdToasts}
      />
    </>
  );
}

function BellIcon() {
  return (
    <svg
      aria-hidden
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
      className="size-5"
    >
      <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
    </svg>
  );
}
