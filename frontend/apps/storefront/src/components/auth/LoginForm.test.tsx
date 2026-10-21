import { ApiError } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { LoginForm, loginErrors } from './LoginForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const t = ko.auth;

describe('LoginForm', () => {
  it('이메일 · 비밀번호 칸은 브라우저 자동 완성과 필수 입력을 쓰고, 회원가입 링크를 둔다', () => {
    const html = renderToStaticMarkup(<LoginForm returnPath="/shop" />);
    expect(html).toMatch(/<input[^>]*type="email" autoComplete="email"[^>]*name="email"/);
    expect(html).toMatch(
      /<input[^>]*type="password" autoComplete="current-password"[^>]*name="password"/,
    );
    expect(html.match(/ required=""/g)).toHaveLength(2);
    expect(html).toContain('<button type="submit"');
    // 돌아갈 경로를 가입 화면에도 실어 보낸다
    expect(html).toContain('href="/signup?next=%2Fshop"');
    expect(renderToStaticMarkup(<LoginForm returnPath="/" />)).toContain('href="/signup"');
  });

  it('가입 직후 자동 로그인이 시도 제한이었으면 처음부터 버튼을 막는다', () => {
    const button = (html: string) => html.match(/<button[^>]*>/)![0];
    expect(button(renderToStaticMarkup(<LoginForm returnPath="/" />))).not.toContain('disabled=""');
    expect(
      button(
        renderToStaticMarkup(<LoginForm returnPath="/" rateLimited={{ retryAfterSec: 840 }} />),
      ),
    ).toContain('disabled=""');
  });
});

describe('loginErrors', () => {
  it('400 AUTH_001 은 칸을 짚지 않고 폼 위에 한 문구로', () => {
    const cause = new ApiError('server', 400, 'AUTH_001', '서버 문구');
    expect(loginErrors(cause, t)).toEqual({
      form: ['이메일 또는 비밀번호가 올바르지 않습니다.'],
      email: [],
      password: [],
    });
  });

  it('영문 스토어는 같은 코드를 영문 사전 문구로 보인다', () => {
    const cause = new ApiError(
      'server',
      400,
      'AUTH_001',
      '이메일 또는 비밀번호가 올바르지 않습니다.',
    );
    expect(loginErrors(cause, en.auth).form).toEqual(['The email or password is incorrect.']);
  });

  it('검증 오류는 칸마다 붙이고, 사전에 없는 코드는 서버 문구를 쓴다', () => {
    const cause = new ApiError('validation', 400, 'AUTH_101', null, [
      { code: 'AUTH_101', field: 'email', message: '서버 이메일 문구' },
      { code: 'AUTH_199', field: 'password', message: '서버 비밀번호 문구' },
    ]);
    expect(loginErrors(cause, t)).toEqual({
      form: [],
      email: ['이메일을 입력해 주세요.'],
      password: ['서버 비밀번호 문구'],
    });
  });

  it('시도 제한(429 AUTH_040)은 범용 실패가 아니라 남은 시간을 담은 전용 안내다(ko · en)', () => {
    const cause = new ApiError('rateLimited', 429, 'AUTH_040', '서버 문구', [], 840);
    expect(loginErrors(cause, t)).toEqual({
      form: ['로그인 시도가 너무 많습니다. 14분 뒤에 다시 시도해 주세요.'],
      email: [],
      password: [],
    });
    expect(loginErrors(cause, en.auth).form).toEqual([
      'Too many sign-in attempts. Please try again in 14 min.',
    ]);
  });

  it('연결 실패 · 일시 장애 · 예상 밖 오류는 다시 시도 안내 한 문구', () => {
    for (const cause of [
      new ApiError('network', null, null, null),
      new ApiError('unavailable', 503, 'BFF_002', null),
      new Error('boom'),
    ]) {
      expect(loginErrors(cause, t)).toEqual({ form: [t.failed], email: [], password: [] });
    }
  });
});
