import Link from 'next/link';
import type { NotificationToast } from '@/lib/notification/center-controller';
import { consolePathOf } from '@/lib/notification/link';
import type { NotificationItem } from '@/lib/notification/types';

/** 스크린 리더에 읽힐 토스트 한 장의 문장 */
function announcement(toast: NotificationToast): string {
  return toast.kind === 'item'
    ? `${toast.item.kind.name}: ${toast.item.title}`
    : `새 알림 ${toast.count}건이 있습니다.`;
}

/**
 * 새 알림 토스트(화면 오른쪽 아래).
 * - 읽어 주는 곳(live region)은 따로 두고 문장만 넣는다 — 링크 · 닫기 버튼까지 읽히지 않게. 늘 두고 내용만 바꿔 처음 뜨는 토스트도 읽힌다.
 *   긴급은 `alert`(바로 읽음), 그 밖은 `status` 다.
 * - 마우스 · 초점이 토스트 위에 있는 동안 저절로 닫히지 않는다(`onHold`).
 * 링크가 있으면 "보기" 로 그 화면에 가고(읽음으로 바꾼다), 끊긴 사이 온 알림이 많으면 "새 알림 N건" 한 장에 알림 패널 열기를 둔다.
 */
export function NotificationToasts({
  toasts,
  onOpen,
  onDismiss,
  onShowAll,
  onHold,
}: {
  toasts: NotificationToast[];
  onOpen: (item: NotificationItem) => void;
  onDismiss: (key: string) => void;
  onShowAll: () => void;
  onHold: (hold: boolean) => void;
}) {
  const critical = (toast: NotificationToast) =>
    toast.kind === 'item' && toast.item.severity.code === 'CRITICAL';
  return (
    <>
      <div role="status" className="sr-only">
        {toasts
          .filter((toast) => !critical(toast))
          .map(announcement)
          .join(' ')}
      </div>
      <div role="alert" className="sr-only">
        {toasts.filter(critical).map(announcement).join(' ')}
      </div>
      <div
        onMouseEnter={() => onHold(true)}
        onMouseLeave={() => onHold(false)}
        onFocus={() => onHold(true)}
        onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) onHold(false);
        }}
        className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[min(22rem,calc(100vw-2rem))] flex-col gap-2"
      >
        {toasts.map((toast) => {
          const critical = toast.kind === 'item' && toast.item.severity.code === 'CRITICAL';
          const href = toast.kind === 'item' ? consolePathOf(toast.item.linkPath) : null;
          return (
            <div
              key={toast.key}
              className={`pointer-events-auto flex items-start gap-3 rounded-md border bg-bg px-4 py-3 text-fg shadow-lg ${
                critical ? 'border-danger' : 'border-border'
              }`}
            >
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                {toast.kind === 'item' ? (
                  <>
                    <p className="text-xs text-muted-fg">
                      {toast.item.kind.name}
                      {toast.item.severity.code !== 'INFO' && (
                        <span className={critical ? 'font-semibold text-danger' : 'font-semibold'}>
                          {' · '}
                          {toast.item.severity.name}
                        </span>
                      )}
                    </p>
                    <p className="break-words text-sm font-medium">{toast.item.title}</p>
                    {href && (
                      <Link
                        href={href}
                        onClick={() => {
                          onOpen(toast.item);
                          onDismiss(toast.key);
                        }}
                        className="self-start text-sm underline underline-offset-4"
                      >
                        보기
                      </Link>
                    )}
                  </>
                ) : (
                  <>
                    <p className="text-sm font-medium">새 알림 {toast.count}건이 있습니다.</p>
                    <button
                      type="button"
                      onClick={() => {
                        onShowAll();
                        onDismiss(toast.key);
                      }}
                      className="self-start text-sm underline underline-offset-4"
                    >
                      알림 보기
                    </button>
                  </>
                )}
              </div>
              <button
                type="button"
                onClick={() => onDismiss(toast.key)}
                aria-label="알림 닫기"
                className="-mr-1 shrink-0 rounded-md px-1.5 text-lg leading-none text-muted-fg hover:bg-muted hover:text-fg"
              >
                ×
              </button>
            </div>
          );
        })}
      </div>
    </>
  );
}
