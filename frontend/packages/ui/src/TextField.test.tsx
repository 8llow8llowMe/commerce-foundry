import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { TextField } from './TextField';

describe('TextField', () => {
  it('label 과 input 을 연결한다', () => {
    const html = renderToStaticMarkup(<TextField label="휴대폰 번호" name="phone" />);
    const inputId = html.match(/<input[^>]*id="([^"]+)"/)?.[1];
    expect(inputId).toBeTruthy();
    expect(html).toContain(`for="${inputId}"`);
    expect(html).not.toContain('aria-invalid');
  });

  it('오류가 있으면 aria-invalid 와 aria-describedby 로 오류 목록을 가리킨다', () => {
    const html = renderToStaticMarkup(
      <TextField label="휴대폰 번호" name="phone" errors={['형식 오류']} />,
    );
    const describedBy = html.match(/aria-describedby="([^"]+)"/)?.[1];
    expect(html).toContain('aria-invalid="true"');
    expect(describedBy).toBeTruthy();
    expect(html).toContain(`id="${describedBy}"`);
    expect(html).toContain('형식 오류');
  });
});
