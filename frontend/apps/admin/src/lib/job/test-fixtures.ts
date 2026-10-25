import type { JobItem } from './types';

/** 테스트용 작업 — 상태 이름은 `<코드>-이름` 이다 */
export function jobItem(code: string, overrides: Partial<JobItem> = {}): JobItem {
  return {
    jobId: '42',
    type: 'SALES_REPORT_EXPORT',
    typeName: '월 매출 리포트 내보내기',
    status: { code, name: `${code}-이름`, description: `${code}-설명` },
    progressPercent: null,
    progressMessage: null,
    notice: null,
    cancellable: code === 'QUEUED' || code === 'RUNNING',
    requestedAt: '2026-10-24T03:00:00Z',
    finishedAt: null,
    ...overrides,
  };
}
