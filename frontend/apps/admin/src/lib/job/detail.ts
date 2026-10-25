import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import { ADMIN_JOBS_PATH, type JobItem } from './types';

/** 실행 중(QUEUED · RUNNING) 작업 상세를 다시 읽는 간격 */
export const JOB_POLL_INTERVAL_MS = 3000;
/** 다시 읽기가 연달아 실패할 때 늘린 간격의 상한 */
export const JOB_POLL_MAX_INTERVAL_MS = 30_000;

/** 다음 읽기까지 기다릴 시간. 연속 실패 n 번이면 기본 간격을 2^n 배로 늘리되 30초를 넘지 않는다 */
export function pollDelay(failures: number): number {
  return Math.min(JOB_POLL_MAX_INTERVAL_MS, JOB_POLL_INTERVAL_MS * 2 ** Math.max(0, failures));
}

/** 서버 오류 코드 — `JobErrorCode` */
export const JOB_NOT_FOUND = 'JOB_001';
export const JOB_CONFLICT = 'JOB_021';
export const JOB_ALREADY_FINISHED = 'JOB_022';

export const JOB_NOT_FOUND_TEXT = '작업을 찾을 수 없습니다. 작업 번호를 확인해 주세요.';
export const POLL_FAILED_NOTICE =
  '최신 상태를 불러오지 못했습니다. 마지막으로 읽은 상태를 보여 주며 잠시 뒤 다시 읽습니다.';
export const ALREADY_FINISHED_NOTICE =
  '이미 끝난 작업이라 취소하지 않았습니다. 최종 상태를 다시 불러왔습니다.';
export const CANCEL_CONFLICT_NOTICE =
  '작업이 그 사이 실행 상태가 바뀌어 취소를 반영하지 못했습니다. 다시 시도해 주세요.';
export const CANCEL_UNKNOWN_NOTICE =
  '취소 결과를 확인하지 못해 최신 상태를 다시 불러왔습니다. 반영됐는지 확인해 주세요.';
export const CANCEL_FAILED = '취소하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/**
 * 작업 아이디는 Long 범위 양의 정수 문자열이다. 아니면 서버에 보내지 않고 "찾을 수 없음" 으로 그린다
 * — 손으로 고친 주소가 400(JOB_104 · 198)이 되지 않게.
 */
export function isJobId(value: string): boolean {
  return /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9_223_372_036_854_775_807n;
}

export function jobApiPath(jobId: string): string {
  return `${ADMIN_JOBS_PATH}/${encodeURIComponent(jobId)}`;
}

export function cancelJobApiPath(jobId: string): string {
  return `${jobApiPath(jobId)}/cancel`;
}

/** 아직 끝나지 않았다 — 상세를 계속 다시 읽는다. 모르는 상태(서버에 새로 생긴 값)는 끝난 것으로 보고 멈춘다 */
export function isActiveJob(job: Pick<JobItem, 'status'>): boolean {
  return job.status.code === 'QUEUED' || job.status.code === 'RUNNING';
}

/** 진행률 표시. 보고 전(null)은 대시 */
export function progressText(percent: number | null): string {
  return percent === null ? '—' : `${percent}%`;
}

export type LoadFailure =
  { kind: 'denied'; denied: AccessDenied } | { kind: 'notFound' } | { kind: 'failed' };

/** 상세 읽기 실패. 404(JOB_001)는 "찾을 수 없음", 401 · 403 은 `AccessNotice`, 그 밖은 일시 오류 */
export function loadFailure(cause: unknown): LoadFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'denied', denied };
  if (cause instanceof ApiError && cause.kind === 'notFound') return { kind: 'notFound' };
  return { kind: 'failed' };
}

export type CancelFailure =
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  /** 최신 상태를 다시 읽고 안내한다 — 이미 끝남(JOB_022) · 결과 모름(502 · 504) */
  | { kind: 'refresh'; notice: string }
  /** 상태는 그대로 두고 안내만 — 다시 누르면 된다 */
  | { kind: 'notice'; notice: string };

/**
 * 취소(POST) 실패를 화면 처리로 가른다.
 * - 409 JOB_022 — 그 사이 끝났다. 최종 상태를 다시 읽는다.
 * - 409 JOB_021 — 워커와 계속 겹쳐 반영하지 못했다(서버가 3번 시도). 다시 누르라고 안내한다.
 * - 404 JOB_001 — 없는 작업.
 * - 502 · 504 — 반영됐는지 모른다. 자동으로 다시 보내지 않고 상태를 다시 읽는다(취소는 멱등이라 사용자가 다시 눌러도 된다).
 * - 401 · 403 — 입력할 폼이 없으니 읽기와 같이 `AccessNotice` 로 보낸다.
 */
export function cancelFailure(cause: unknown): CancelFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'denied', denied };
  if (!(cause instanceof ApiError)) return { kind: 'notice', notice: CANCEL_FAILED };
  if (cause.code === JOB_ALREADY_FINISHED)
    return { kind: 'refresh', notice: ALREADY_FINISHED_NOTICE };
  if (cause.code === JOB_CONFLICT) return { kind: 'notice', notice: CANCEL_CONFLICT_NOTICE };
  if (cause.kind === 'notFound') return { kind: 'notFound' };
  if (cause.kind === 'unknownOutcome') return { kind: 'refresh', notice: CANCEL_UNKNOWN_NOTICE };
  // 400 등 서버가 이유를 준 오류는 그 문구, 그 밖(503 · 네트워크 · 5xx)은 일시 오류
  if (cause.status !== null && cause.status < 500 && cause.code)
    return { kind: 'notice', notice: cause.message };
  return { kind: 'notice', notice: CANCEL_FAILED };
}
