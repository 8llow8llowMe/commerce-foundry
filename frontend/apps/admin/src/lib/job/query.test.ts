import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUERY,
  hasFilter,
  jobsApiPath,
  jobsHref,
  normalizeJobType,
  parseJobQuery,
  type JobQuery,
} from './query';

const FULL: JobQuery = { status: 'RUNNING', type: 'SALES_REPORT_EXPORT', page: 2 };

describe('parseJobQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseJobQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(parseJobQuery({ status: 'RUNNING', type: 'SALES_REPORT_EXPORT', page: '2' })).toEqual(
      FULL,
    );
    expect(parseJobQuery({ status: 'CANCELLED' }).status).toBe('CANCELLED');
  });

  it('허용하지 않는 값은 전체(기본값)로 본다 — 서버 400(JOB_103 · 198)을 만들지 않는다', () => {
    expect(parseJobQuery({ status: 'running', type: 'sales', page: '-1' })).toEqual(DEFAULT_QUERY);
    expect(parseJobQuery({ status: 'DONE', type: 'A-B', page: '1.5' })).toEqual(DEFAULT_QUERY);
    expect(parseJobQuery({ type: 'A'.repeat(65), page: '1234567' })).toEqual(DEFAULT_QUERY);
    expect(parseJobQuery({ type: 'A'.repeat(64) }).type).toBe('A'.repeat(64));
  });

  it('여러 값이면 첫 값을 쓴다', () => {
    expect(parseJobQuery({ status: ['FAILED', 'QUEUED'], page: ['1', '2'] })).toEqual({
      status: 'FAILED',
      type: null,
      page: 1,
    });
  });
});

describe('normalizeJobType', () => {
  it('비우면 전체다', () => {
    expect(normalizeJobType('  ')).toEqual({ ok: true, type: null });
  });

  it('앞뒤 공백을 빼고 대문자로 바꾼다', () => {
    expect(normalizeJobType(' sales_report_export ')).toEqual({
      ok: true,
      type: 'SALES_REPORT_EXPORT',
    });
  });

  it('서버 패턴에 맞지 않으면 거절한다', () => {
    expect(normalizeJobType('sales-report')).toEqual({ ok: false });
    expect(normalizeJobType('리포트')).toEqual({ ok: false });
    expect(normalizeJobType('A'.repeat(65))).toEqual({ ok: false });
  });
});

describe('jobsHref', () => {
  it('기본값은 주소에서 뺀다', () => {
    expect(jobsHref(DEFAULT_QUERY)).toBe('/jobs');
  });

  it('조건을 주소로 쓰고, 다시 읽으면 같은 조건이다', () => {
    const href = jobsHref(FULL);
    expect(href).toBe('/jobs?status=RUNNING&type=SALES_REPORT_EXPORT&page=2');
    const params = Object.fromEntries(new URL(href, 'http://admin.test').searchParams);
    expect(parseJobQuery(params)).toEqual(FULL);
  });
});

describe('jobsApiPath', () => {
  it('페이지 · 크기는 늘 보내고, 전체(null) 조건은 보내지 않는다', () => {
    expect(jobsApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/jobs?page=0&size=20');
  });

  it('조건을 서버 파라미터 이름 그대로 보낸다', () => {
    expect(jobsApiPath(FULL)).toBe(
      '/api/v1/admin/jobs?status=RUNNING&type=SALES_REPORT_EXPORT&page=2&size=20',
    );
  });
});

describe('hasFilter', () => {
  it('상태 · 종류 중 하나라도 있으면 조건이 있다(페이지는 조건이 아니다)', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, status: 'QUEUED' })).toBe(true);
    expect(hasFilter({ ...DEFAULT_QUERY, type: 'X' })).toBe(true);
  });
});
