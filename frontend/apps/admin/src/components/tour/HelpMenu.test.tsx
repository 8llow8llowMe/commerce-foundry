import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { PracticeEntry } from './HelpMenu';

const noop = () => {};

describe('PracticeEntry', () => {
  it('도움말에서 샘플 주문 연습(#365)으로 가는 링크를 보이고, 연습 화면 안에서는 숨긴다', () => {
    const html = renderToStaticMarkup(<PracticeEntry pathname="/orders" onNavigate={noop} />);
    expect(html).toContain('href="/practice"');
    expect(html).toContain('샘플 주문으로 연습하기');
    expect(renderToStaticMarkup(<PracticeEntry pathname="/practice" onNavigate={noop} />)).toBe('');
  });
});
