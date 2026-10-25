import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  ALREADY_FINISHED_NOTICE,
  CANCEL_CONFLICT_NOTICE,
  CANCEL_FAILED,
  CANCEL_REQUESTED_NOTICE,
  CANCEL_UNKNOWN_NOTICE,
  CANCELLED_NOTICE,
  cancelFailure,
  cancelJobApiPath,
  cancelNotice,
  canCancel,
  isActiveJob,
  isCancelPending,
  isJobId,
  jobApiPath,
  loadFailure,
  prettyJson,
  progressText,
} from './detail';
import type { JobDetail } from './types';

const status = (code: string) => ({ code, name: code, description: '' });

const job = (overrides: Partial<JobDetail> = {}): JobDetail => ({
  jobId: '4611686018427387904',
  type: 'SALES_REPORT_EXPORT',
  status: status('RUNNING'),
  progressPercent: 40,
  attempt: 1,
  maxAttempts: 3,
  cancelRequested: false,
  errorCode: null,
  requestedByMemberId: '1',
  scheduledAt: '2026-10-24T03:00:00Z',
  startedAt: '2026-10-24T03:00:02Z',
  finishedAt: null,
  priority: 0,
  progressMessage: null,
  cancelRequestedByMemberId: null,
  dedupKey: null,
  payloadJson: '{}',
  resultJson: null,
  errorMessage: null,
  ...overrides,
});

describe('isJobId', () => {
  it('Long 범위 양의 정수 문자열만 받는다', () => {
    expect(isJobId('1')).toBe(true);
    expect(isJobId('9223372036854775807')).toBe(true);
    expect(isJobId('9223372036854775808')).toBe(false);
    expect(isJobId('0')).toBe(false);
    expect(isJobId('01')).toBe(false);
    expect(isJobId('-1')).toBe(false);
    expect(isJobId('abc')).toBe(false);
  });
});

describe('경로', () => {
  it('상세 · 취소 경로', () => {
    expect(jobApiPath('12')).toBe('/api/v1/admin/jobs/12');
    expect(cancelJobApiPath('12')).toBe('/api/v1/admin/jobs/12/cancel');
  });
});

describe('isActiveJob · 취소 가능', () => {
  it('QUEUED · RUNNING 만 실행 중이다 — 끝난 상태 · 모르는 상태는 다시 읽지 않는다', () => {
    expect(isActiveJob(job({ status: status('QUEUED') }))).toBe(true);
    expect(isActiveJob(job({ status: status('RUNNING') }))).toBe(true);
    for (const code of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'ARCHIVED']) {
      expect(isActiveJob(job({ status: status(code) }))).toBe(false);
    }
  });

  it('실행 중이고 취소 요청을 받았으면 "취소 요청됨" 이고 버튼은 없다', () => {
    const pending = job({ status: status('RUNNING'), cancelRequested: true });
    expect(isCancelPending(pending)).toBe(true);
    expect(canCancel(pending)).toBe(false);
  });

  it('대기 · 실행 중이면 취소할 수 있고, 끝난 작업은 없다', () => {
    expect(canCancel(job({ status: status('QUEUED') }))).toBe(true);
    expect(canCancel(job({ status: status('RUNNING') }))).toBe(true);
    // 재시도 대기로 돌아간 작업에 남은 취소 요청 — 다시 보내면 바로 취소된다
    expect(canCancel(job({ status: status('QUEUED'), cancelRequested: true }))).toBe(true);
    expect(canCancel(job({ status: status('SUCCEEDED') }))).toBe(false);
  });
});

describe('progressText · prettyJson', () => {
  it('보고 전 진행률은 대시다', () => {
    expect(progressText(null)).toBe('—');
    expect(progressText(0)).toBe('0%');
    expect(progressText(100)).toBe('100%');
  });

  it('JSON 은 들여 쓰고, JSON 이 아니면 원문 그대로다', () => {
    expect(prettyJson('{"rows":3}')).toBe('{\n  "rows": 3\n}');
    expect(prettyJson('not json')).toBe('not json');
  });
});

describe('loadFailure', () => {
  it('404 JOB_001 은 찾을 수 없음', () => {
    expect(
      loadFailure(new ApiError('notFound', 404, 'JOB_001', '존재하지 않는 작업입니다.')),
    ).toEqual({ kind: 'notFound' });
  });

  it('401 · 403 은 접근 안내', () => {
    expect(loadFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'denied',
      denied: 'unauthorized',
    });
    expect(loadFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      kind: 'denied',
      denied: 'forbidden',
    });
  });

  it('그 밖은 일시 오류', () => {
    expect(loadFailure(new ApiError('unavailable', 503, 'BFF_002', null))).toEqual({
      kind: 'failed',
    });
    expect(loadFailure(new TypeError('boom'))).toEqual({ kind: 'failed' });
  });
});

describe('cancelFailure', () => {
  it('409 JOB_022(이미 끝남)은 최종 상태를 다시 읽는다', () => {
    expect(
      cancelFailure(new ApiError('conflict', 409, 'JOB_022', '이미 끝난 작업입니다.')),
    ).toEqual({
      kind: 'refresh',
      notice: ALREADY_FINISHED_NOTICE,
    });
  });

  it('409 JOB_021(워커와 겹침)은 다시 시도하라고 안내한다', () => {
    expect(cancelFailure(new ApiError('conflict', 409, 'JOB_021', '바뀌었습니다.'))).toEqual({
      kind: 'notice',
      notice: CANCEL_CONFLICT_NOTICE,
    });
  });

  it('404 JOB_001 은 찾을 수 없음', () => {
    expect(cancelFailure(new ApiError('notFound', 404, 'JOB_001', null))).toEqual({
      kind: 'notFound',
    });
  });

  it('502 · 504 는 자동으로 다시 보내지 않고 상태를 다시 읽는다', () => {
    expect(cancelFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'refresh',
      notice: CANCEL_UNKNOWN_NOTICE,
    });
  });

  it('401 · 403 은 접근 안내', () => {
    expect(cancelFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'denied',
      denied: 'unauthorized',
    });
  });

  it('400 은 서버 문구, 그 밖 · 네트워크는 일시 오류 문구', () => {
    expect(
      cancelFailure(new ApiError('server', 400, 'JOB_104', '작업 아이디는 양수여야 합니다.')),
    ).toEqual({ kind: 'notice', notice: '작업 아이디는 양수여야 합니다.' });
    expect(cancelFailure(new ApiError('unavailable', 503, 'BFF_002', null))).toEqual({
      kind: 'notice',
      notice: CANCEL_FAILED,
    });
    expect(cancelFailure(new TypeError('boom'))).toEqual({ kind: 'notice', notice: CANCEL_FAILED });
  });
});

describe('cancelNotice', () => {
  it('QUEUED 는 바로 취소, RUNNING 은 취소 요청됨', () => {
    expect(cancelNotice(job({ status: status('CANCELLED') }))).toBe(CANCELLED_NOTICE);
    expect(cancelNotice(job({ status: status('RUNNING'), cancelRequested: true }))).toBe(
      CANCEL_REQUESTED_NOTICE,
    );
  });
});
