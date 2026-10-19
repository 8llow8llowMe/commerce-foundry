import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Checkbox } from './Checkbox';

describe('Checkbox', () => {
  it('글자를 눌러도 체크되게 label 과 checkbox 를 연결한다', () => {
    const html = renderToStaticMarkup(<Checkbox label="이용약관 동의" name="terms" />);
    const inputId = html.match(/<input[^>]*id="([^"]+)"/)?.[1];
    expect(inputId).toBeTruthy();
    expect(html).toContain(`for="${inputId}"`);
    expect(html).toContain('type="checkbox"');
    expect(html).not.toContain('aria-invalid');
  });

  it('오류가 있으면 aria-invalid 와 aria-describedby 로 오류 목록을 가리킨다', () => {
    const html = renderToStaticMarkup(
      <Checkbox label="이용약관 동의" name="terms" errors={['동의가 필요합니다']} />,
    );
    const describedBy = html.match(/aria-describedby="([^"]+)"/)?.[1];
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain(`id="${describedBy}"`);
    expect(html).toContain('동의가 필요합니다');
  });
});
