import type { StatusMeta } from '@/lib/job/types';

/**
 * 어드민 알림 센터 API(BE #213, operations-service `NotificationAdminController`). 모두 ADMIN 이고 요청한 어드민 것만 보인다.
 * 계약 정본은 `backend/docs/api-design-guide.md` §7-2.
 * - 목록 `GET ?page=&size=` — `PageResponse<NotificationItem>`, 아이디 내림차순
 * - 읽지 않은 수 `GET /unread-count` — `{count}`
 * - 읽음 `POST /{id}/read` — 목록 항목과 같다(멱등) · 모두 읽음 `POST /read-all` — `{count}`(0)
 * - 스트림 `GET /stream` — SSE. 이벤트 `notification`, data 는 목록 항목 JSON(봉투 없음). 5초마다 주석 heartbeat
 */
export const ADMIN_NOTIFICATIONS_PATH = '/api/v1/admin/notifications';
export const NOTIFICATION_UNREAD_COUNT_PATH = `${ADMIN_NOTIFICATIONS_PATH}/unread-count`;
export const NOTIFICATION_READ_ALL_PATH = `${ADMIN_NOTIFICATIONS_PATH}/read-all`;
export const NOTIFICATION_STREAM_PATH = `${ADMIN_NOTIFICATIONS_PATH}/stream`;
/** 스트림 이벤트 이름 */
export const NOTIFICATION_EVENT = 'notification';

export function readNotificationPath(notificationId: string): string {
  return `${ADMIN_NOTIFICATIONS_PATH}/${encodeURIComponent(notificationId)}/read`;
}

/**
 * 알림 한 건(`NotificationAdminItem`). 아이디는 문자열, 시각은 UTC ISO 다.
 * 종류(JOB_SUCCEEDED · JOB_FAILED · JOB_CANCELLED · ANOMALY · LOW_STOCK · CS_HANDOFF) · 심각도(INFO · WARNING · CRITICAL)는
 * `{code, name, description}` 이다 — 화면은 서버 `name` 을 그대로 보이고, 코드는 꾸밈(강조 · 자동 닫힘)에만 쓴다.
 */
export type NotificationItem = {
  notificationId: string;
  kind: StatusMeta;
  severity: StatusMeta;
  title: string;
  /** 어드민 화면 경로(`/admin/jobs/{jobId}` 처럼 `/admin` 으로 시작). 없으면 null — `consolePathOf` 로 콘솔 경로로 바꾼다 */
  linkPath: string | null;
  read: boolean;
  createdAt: string;
};

export type UnreadCount = { count: number };

/** 스트림 data 가 알림 모양인지. 모양이 다르면 버리고 목록으로 따라잡는다 */
export function isNotificationItem(value: unknown): value is NotificationItem {
  if (typeof value !== 'object' || value === null) return false;
  const item = value as Record<string, unknown>;
  const meta = (v: unknown) =>
    typeof v === 'object' &&
    v !== null &&
    typeof (v as StatusMeta).code === 'string' &&
    typeof (v as StatusMeta).name === 'string';
  return (
    typeof item.notificationId === 'string' &&
    meta(item.kind) &&
    meta(item.severity) &&
    typeof item.title === 'string' &&
    (item.linkPath === null || typeof item.linkPath === 'string') &&
    typeof item.read === 'boolean' &&
    typeof item.createdAt === 'string'
  );
}
