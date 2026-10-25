/**
 * 어드민 백그라운드 작업 API(#212 PR-C · #305, operations-service `JobAdminController`). 모두 ADMIN 이다.
 * 사용자는 쇼핑몰 운영자라 응답에 기술 정보(시도 횟수 · 오류 코드 · JSON 원문 등)가 없다(`backend/docs/api-design-guide.md` §2-8 · §7-1).
 * - 목록 `GET /api/v1/admin/jobs?status=&type=&page=&size=` — `PageResponse<JobItem>`, 등록 최신순 고정
 * - 상세 `GET /api/v1/admin/jobs/{jobId}` — `JobItem`. 작업 실패도 200 이고 status FAILED 와 서버 안내 문구(notice)로 알린다
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

/**
 * 작업 하나 — 목록 항목(`JobAdminItem`) · 상세 · 취소 응답(`JobAdminResponse`)이 같은 칸이다.
 * 아이디는 문자열, 시각은 UTC ISO 다.
 */
export type JobItem = {
  jobId: string;
  /** 종류 코드 (예: SALES_REPORT_EXPORT) — 목록 거르기에만 쓰고 화면에는 `typeName` 을 보인다 */
  type: string;
  /** 작업 이름 (예: 월 매출 리포트 내보내기) */
  typeName: string;
  status: StatusMeta;
  /** 0 ~ 100. 아직 알 수 없으면 null */
  progressPercent: number | null;
  progressMessage: string | null;
  /** 운영자 안내 문구 — 실패 이유 · 재시도 대기 · 취소 요청됨. 서버 문장을 그대로 보인다. 없으면 null */
  notice: string | null;
  /** 지금 취소할 수 있다(대기 · 실행 중이고 아직 취소를 요청하지 않았다). 취소 버튼은 이것 하나로 보인다 */
  cancellable: boolean;
  requestedAt: string;
  /** 성공 · 실패 · 취소일 때만 */
  finishedAt: string | null;
};
