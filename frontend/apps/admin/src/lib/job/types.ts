/**
 * 어드민 백그라운드 작업 API(#212 PR-C, operations-service `JobAdminController`). 모두 ADMIN 이다.
 * - 목록 `GET /api/v1/admin/jobs?status=&type=&page=&size=` — `PageResponse<JobListItem>`, 등록 최신순 고정
 * - 상세 `GET /api/v1/admin/jobs/{jobId}` — `JobDetail`. 작업 실패도 200 이고 status FAILED · errorCode 로 알린다
 * - 취소 `POST /api/v1/admin/jobs/{jobId}/cancel` — 응답은 상세와 같다
 */
export const ADMIN_JOBS_PATH = '/api/v1/admin/jobs';

/** 서버 `JobStatusFilter` · `JobStatus` 와 같은 값 */
export const JOB_STATUSES = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'] as const;
export type JobStatusCode = (typeof JOB_STATUSES)[number];

/**
 * 필터 선택지의 이름. 표 · 상세의 상태는 서버가 준 `status.name` 을 그대로 보인다 —
 * 필터는 목록을 읽기 전에 그려야 해서 서버 `JobStatus` 의 이름을 여기 옮겨 둔다.
 */
export const JOB_STATUS_LABELS: Record<JobStatusCode, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '성공',
  FAILED: '실패',
  CANCELLED: '취소',
};

/** 서버 `CodeNameDescriptionMetadata` */
export type StatusMeta = { code: string; name: string; description: string };

/** `JobAdminItem` — 입력 · 결과 JSON 은 상세에서 본다. 아이디는 문자열, 시각은 UTC ISO 다 */
export type JobListItem = {
  jobId: string;
  /** 핸들러 이름 (예: SALES_REPORT_EXPORT) */
  type: string;
  status: StatusMeta;
  /** 0 ~ 100. 핸들러가 보고하기 전이면 null */
  progressPercent: number | null;
  /** 가져간 시도 수(0 부터). RUNNING 이면 실행 중인 시도 번호 */
  attempt: number;
  /** 시도 횟수 상한(첫 실행 포함) */
  maxAttempts: number;
  cancelRequested: boolean;
  /** 마지막 실패 코드 — 재시도 대기 중에도 남고 성공하면 비운다 */
  errorCode: string | null;
  /** 시스템 등록이면 null */
  requestedByMemberId: string | null;
  /** 이 시각부터 가져갈 수 있다(재시도면 백오프가 더해진 시각) */
  scheduledAt: string;
  startedAt: string | null;
  /** SUCCEEDED · FAILED · CANCELLED 일 때만 */
  finishedAt: string | null;
};

/** `JobAdminResponse` — 목록 항목 + 진행 문구 · 입력 · 결과 · 오류 설명 */
export type JobDetail = JobListItem & {
  priority: number;
  progressMessage: string | null;
  cancelRequestedByMemberId: string | null;
  dedupKey: string | null;
  /** 핸들러 입력 JSON 원문 */
  payloadJson: string;
  /** 결과 JSON 원문. 성공 전이거나 결과가 없으면 null */
  resultJson: string | null;
  errorMessage: string | null;
};
