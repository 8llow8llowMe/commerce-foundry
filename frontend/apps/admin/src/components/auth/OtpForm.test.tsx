import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { OtpForm } from './OtpForm';

describe('OtpForm', () => {
  const html = renderToStaticMarkup(
    createElement(OtpForm, {
      challenge: { token: 'secret-challenge-token', expiresAt: Date.now() + 300_000 },
      onSuccess: () => {},
      onRestart: () => {},
    }),
  );

  it('챌린지를 마크업(숨은 칸 · 속성)에 싣지 않는다 — 요청 본문에만 싣는다', () => {
    expect(html).not.toContain('secret-challenge-token');
  });

  it('6자리 · 복구 코드를 한 칸으로 받고 일회용 코드 자동 완성을 켠다', () => {
    expect(html).toContain('name="code"');
    expect(html).toContain('autoComplete="one-time-code"');
    expect(html).toContain('maxLength="32"');
    expect(html).toContain('처음부터 다시 로그인');
  });
});
