import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { FieldError } from './FieldError';

describe('FieldError', () => {
  it('메시지가 없으면 아무것도 그리지 않는다', () => {
    expect(renderToStaticMarkup(<FieldError messages={[]} />)).toBe('');
  });

  it('메시지를 role=alert 목록으로 그린다 (중복 메시지 포함)', () => {
    const html = renderToStaticMarkup(
      <FieldError id="phone-error" messages={['형식 오류', '형식 오류']} />,
    );
    expect(html).toContain('id="phone-error"');
    expect(html).toContain('role="alert"');
    expect(html.match(/<li>형식 오류<\/li>/g)).toHaveLength(2);
  });
});
