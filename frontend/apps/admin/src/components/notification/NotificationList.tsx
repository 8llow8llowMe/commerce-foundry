import Link from 'next/link';
import { formatJobTime } from '@/lib/job/format';
import { consolePathOf } from '@/lib/notification/link';
import type { NotificationItem } from '@/lib/notification/types';

/**
 * 알림 목록(패널 안). 제목 · 종류 · 심각도(서버 name 그대로) · 시각, 읽지 않은 알림 표시와 "읽음" 버튼.
 * 링크가 있으면 제목이 그 화면으로 가는 링크이고, 누르면 읽음으로 바꾼다(`onOpen`).
 */
export function NotificationList({
  items,
  onOpen,
  onMarkRead,
}: {
  items: NotificationItem[];
  /** 링크를 눌렀다 — 읽음으로 바꾸고 패널을 닫는다 */
  onOpen: (item: NotificationItem) => void;
  onMarkRead: (item: NotificationItem) => void;
}) {
  if (items.length === 0) {
    return <p className="px-4 py-6 text-center text-sm text-muted-fg">알림이 없습니다.</p>;
  }
  return (
    <ul className="divide-y divide-border">
      {items.map((item) => {
        const href = consolePathOf(item.linkPath);
        return (
          <li key={item.notificationId} className="flex items-start gap-3 px-4 py-3">
            <span
              aria-hidden
              className={`mt-1.5 size-2 shrink-0 rounded-full ${item.read ? 'bg-transparent' : 'bg-primary'}`}
            />
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              {href ? (
                <Link
                  href={href}
                  onClick={() => onOpen(item)}
                  className="break-words text-sm font-medium underline-offset-4 hover:underline"
                >
                  {item.title}
                </Link>
              ) : (
                <p className="break-words text-sm font-medium">{item.title}</p>
              )}
              <p className="text-xs text-muted-fg">
                {!item.read && <span className="sr-only">읽지 않음 · </span>}
                {item.kind.name}
                {item.severity.code !== 'INFO' && (
                  <span
                    className={
                      item.severity.code === 'CRITICAL'
                        ? 'font-semibold text-danger'
                        : 'font-semibold'
                    }
                  >
                    {' · '}
                    {item.severity.name}
                  </span>
                )}
                {' · '}
                <time dateTime={item.createdAt}>{formatJobTime(item.createdAt)}</time>
              </p>
            </div>
            {!item.read && (
              <button
                type="button"
                onClick={() => onMarkRead(item)}
                aria-label={`${item.title} 읽음으로 표시`}
                className="shrink-0 rounded-md px-2 py-1 text-xs text-muted-fg hover:bg-muted hover:text-fg"
              >
                읽음
              </button>
            )}
          </li>
        );
      })}
    </ul>
  );
}
