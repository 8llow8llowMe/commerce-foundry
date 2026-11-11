import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { MAIN_ID, SkipLink } from './SkipLink';

describe('SkipLink', () => {
  it('본문으로 가는 링크이고, 평소에는 숨었다가 포커스를 받으면 보인다', () => {
    const html = renderToStaticMarkup(<SkipLink />);
    expect(html).toContain(`href="#${MAIN_ID}"`);
    expect(html).toContain('본문으로 건너뛰기');
    expect(html).toContain('sr-only');
    expect(html).toContain('focus:not-sr-only');
  });
});
