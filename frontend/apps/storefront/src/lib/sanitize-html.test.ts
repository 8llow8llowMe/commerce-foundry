import { describe, expect, it } from 'vitest';
import { sanitizeProductHtml } from './sanitize-html';

describe('sanitizeProductHtml', () => {
  it('허용한 서식 · 이미지 · 표는 남긴다', () => {
    const html = sanitizeProductHtml(
      '<h3>소재</h3><p><strong>실크</strong> 100%</p><img src="https://img.test/a.jpg" alt="디테일"><table><tr><td>S</td></tr></table>',
    );
    expect(html).toContain('<h3>소재</h3>');
    expect(html).toContain('<strong>실크</strong>');
    expect(html).toContain(
      '<img src="https://img.test/a.jpg" alt="디테일" loading="lazy" decoding="async" />',
    );
    expect(html).toContain('<td>S</td>');
  });

  it('script · iframe · 이벤트 속성 · style 을 지운다', () => {
    const html = sanitizeProductHtml(
      '<p onclick="alert(1)" style="color:red">안녕</p><script>alert(1)</script><iframe src="https://evil.test"></iframe><img src="https://img.test/a.jpg" onerror="alert(1)">',
    );
    expect(html).toBe(
      '<p>안녕</p><img src="https://img.test/a.jpg" loading="lazy" decoding="async" />',
    );
  });

  it('javascript: · data: URL 과 프로토콜 상대 URL 을 지운다', () => {
    expect(sanitizeProductHtml('<a href="javascript:alert(1)">x</a>')).not.toContain('javascript');
    expect(sanitizeProductHtml('<img src="data:image/svg+xml;base64,AAAA">')).not.toContain(
      'data:',
    );
    expect(sanitizeProductHtml('<a href="//evil.test">x</a>')).not.toContain('evil.test');
  });

  it('링크는 새 창 + opener 차단으로 바꾼다 (원문 target 은 무시)', () => {
    expect(sanitizeProductHtml('<a href="https://brand.test" target="_self">브랜드</a>')).toBe(
      '<a href="https://brand.test" target="_blank" rel="noopener noreferrer">브랜드</a>',
    );
  });
});
