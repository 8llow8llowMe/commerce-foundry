import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { jobItem } from '@/lib/job/test-fixtures';
import { JobProgressBar } from './JobProgressBar';
import { jobTrayLabel } from './JobTray';

describe('jobTrayLabel', () => {
  it('실행 중이 있으면 그 수, 없으면 끝난 작업 수', () => {
    expect(jobTrayLabel(2, 3)).toBe('진행 중 작업 2');
    expect(jobTrayLabel(0, 3)).toBe('끝난 작업 3');
  });
});

describe('JobProgressBar', () => {
  it('진행률을 막대와 숫자로, 보고 전이면 대시로 보인다', () => {
    const html = renderToStaticMarkup(
      <JobProgressBar job={jobItem('RUNNING', { progressPercent: 40 })} label="리포트 진행률" />,
    );
    expect(html).toContain('aria-label="리포트 진행률"');
    expect(html).toContain('aria-valuenow="40"');
    expect(html).toContain('width:40%');
    expect(renderToStaticMarkup(<JobProgressBar job={jobItem('QUEUED')} />)).toContain('—');
  });
});
