import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { JobDetail } from '@/lib/job/types';
import { JobFields } from './JobFields';

const base: JobDetail = {
  jobId: '42',
  type: 'SALES_REPORT_EXPORT',
  status: { code: 'SUCCEEDED', name: '성공', description: '실행을 마쳤습니다.' },
  progressPercent: 100,
  attempt: 1,
  maxAttempts: 3,
  cancelRequested: false,
  errorCode: null,
  requestedByMemberId: null,
  scheduledAt: '2026-10-24T03:00:00Z',
  startedAt: '2026-10-24T03:00:02Z',
  finishedAt: '2026-10-24T03:01:10Z',
  priority: 0,
  progressMessage: '3/3 구간 집계',
  cancelRequestedByMemberId: null,
  dedupKey: 'SALES_REPORT_EXPORT@2026-09',
  payloadJson: '{"month":"2026-09"}',
  resultJson: '{"rows":3}',
  errorMessage: null,
};

describe('JobFields', () => {
  it('상태는 서버 name · description 을, 진행률 · 문구 · 시도 · 결과 JSON 을 보인다', () => {
    const html = renderToStaticMarkup(<JobFields job={base} />);
    expect(html).toContain('성공');
    expect(html).toContain('실행을 마쳤습니다.');
    expect(html).not.toContain('SUCCEEDED');
    expect(html).toContain('aria-valuenow="100"');
    expect(html).toContain('3/3 구간 집계');
    expect(html).toContain('1 / 3');
    expect(html).toContain('시스템');
    // 결과 JSON 은 들여 쓴다(따옴표는 HTML 이스케이프)
    expect(html).toContain('{\n  &quot;rows&quot;: 3\n}');
    // 시각은 한국 시각이다(UTC 03:01 → 12:01)
    expect(html).toContain('12:01:10');
  });

  it('실패 작업은 오류 코드 · 설명을, 결과가 없으면 안내를 보인다', () => {
    const html = renderToStaticMarkup(
      <JobFields
        job={{
          ...base,
          status: { code: 'FAILED', name: '실패', description: '재시도 상한에 닿았습니다.' },
          progressPercent: null,
          resultJson: null,
          errorCode: 'UPSTREAM_TIMEOUT',
          errorMessage: '외부 호출 시간 초과',
        }}
      />,
    );
    expect(html).toContain('실패');
    expect(html).toContain('UPSTREAM_TIMEOUT');
    expect(html).toContain('외부 호출 시간 초과');
    expect(html).toContain('결과가 없습니다.');
    expect(html).not.toContain('aria-valuenow');
  });
});
