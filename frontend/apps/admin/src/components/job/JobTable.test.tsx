import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { jobItem } from '@/lib/job/test-fixtures';
import { JobTable } from './JobTable';

describe('JobTable', () => {
  it('작업 이름(상세 링크) · 상태 name · 서버 안내 문구 · 진행률을 보인다', () => {
    const html = renderToStaticMarkup(
      <JobTable
        jobs={[
          jobItem('QUEUED', {
            jobId: '7',
            status: { code: 'QUEUED', name: '대기', description: '실행 차례를 기다립니다.' },
            notice: '일시적인 오류로 잠시 뒤 다시 시도합니다.',
          }),
          jobItem('RUNNING', { jobId: '8', progressPercent: 40, notice: null }),
        ]}
      />,
    );
    expect(html).toContain('href="/jobs/7"');
    expect(html).toContain('월 매출 리포트 내보내기');
    expect(html).toContain('title="실행 차례를 기다립니다."');
    expect(html).toContain('>대기<');
    expect(html).toContain('일시적인 오류로 잠시 뒤 다시 시도합니다.');
    expect(html).toContain('40%');
    expect(html).not.toContain('SALES_REPORT_EXPORT');
  });
});
