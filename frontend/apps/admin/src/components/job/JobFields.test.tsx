import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { jobItem } from '@/lib/job/test-fixtures';
import { JobFields } from './JobFields';

describe('JobFields', () => {
  it('작업 이름 · 상태 name · 설명 · 진행률 · 진행 문구 · 시각을 보인다(종류 코드 · 상태 코드는 보이지 않는다)', () => {
    const html = renderToStaticMarkup(
      <JobFields
        job={jobItem('SUCCEEDED', {
          status: { code: 'SUCCEEDED', name: '성공', description: '실행을 마쳤습니다.' },
          progressPercent: 100,
          progressMessage: '3/3 구간 집계',
          finishedAt: '2026-10-24T03:01:10Z',
        })}
      />,
    );
    expect(html).toContain('월 매출 리포트 내보내기');
    expect(html).toContain('성공');
    expect(html).toContain('실행을 마쳤습니다.');
    expect(html).not.toContain('SUCCEEDED');
    expect(html).not.toContain('SALES_REPORT_EXPORT');
    expect(html).toContain('aria-valuenow="100"');
    expect(html).toContain('3/3 구간 집계');
    // 시각은 한국 시각이다(UTC 03:00 → 12:00, 03:01 → 12:01)
    expect(html).toContain('12:00:00');
    expect(html).toContain('12:01:10');
  });

  it('서버 안내 문구(notice)를 그대로 보인다', () => {
    const html = renderToStaticMarkup(
      <JobFields
        job={jobItem('FAILED', {
          notice: '외부 연결이 늦어 작업을 마치지 못했습니다. 다시 요청해 주세요.',
        })}
      />,
    );
    expect(html).toContain('외부 연결이 늦어 작업을 마치지 못했습니다. 다시 요청해 주세요.');
    expect(html).not.toContain('aria-valuenow');
  });

  it('안내가 없으면 안내 칸은 비어 있다(live region 은 남긴다)', () => {
    const html = renderToStaticMarkup(<JobFields job={jobItem('RUNNING')} />);
    expect(html).toMatch(/<p role="status"[^>]*><\/p>/);
  });
});
