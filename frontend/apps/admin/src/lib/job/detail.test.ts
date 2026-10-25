import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  ALREADY_FINISHED_NOTICE,
  CANCEL_CONFLICT_NOTICE,
  CANCEL_FAILED,
  CANCEL_UNKNOWN_NOTICE,
  cancelFailure,
  cancelJobApiPath,
  isActiveJob,
  isJobId,
  jobApiPath,
  loadFailure,
  pollDelay,
  progressText,
} from './detail';
import { jobItem } from './test-fixtures';

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

describe('isActiveJob', () => {
  it('QUEUED · RUNNING 만 실행 중이다 — 끝난 상태 · 모르는 상태는 다시 읽지 않는다', () => {
    expect(isActiveJob(jobItem('QUEUED'))).toBe(true);
    expect(isActiveJob(jobItem('RUNNING'))).toBe(true);
    for (const code of ['SUCCEEDED', 'FAILED', 'CANCELLED', 'ARCHIVED']) {
      expect(isActiveJob(jobItem(code))).toBe(false);
    }
  });
});

describe('progressText', () => {
  it('아직 알 수 없는 진행률은 대시다', () => {
    expect(progressText(null)).toBe('—');
    expect(progressText(0)).toBe('0%');
    expect(progressText(100)).toBe('100%');
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

describe('pollDelay', () => {
  it('연속 실패마다 두 배, 30초가 상한이다', () => {
    expect(pollDelay(0)).toBe(3000);
    expect(pollDelay(1)).toBe(6000);
    expect(pollDelay(3)).toBe(24_000);
    expect(pollDelay(4)).toBe(30_000);
    expect(pollDelay(50)).toBe(30_000);
  });
});
