import { ApiError } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { SignUpForm, signUpErrors, signedUpLoginPath } from './SignUpForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const t = ko.auth;

describe('SignUpForm', () => {
  it('새 비밀번호 자동 완성 · 필수 칸 · 약관 체크를 두고, 휴대폰과 마케팅은 선택이다', () => {
    const html = renderToStaticMarkup(<SignUpForm returnPath="/" />);
    expect(html).toMatch(/<input[^>]*type="email" autoComplete="email"[^>]*name="email"/);
    expect(html.match(/autoComplete="new-password"/g)).toHaveLength(2);
    const input = (name: string) => html.match(new RegExp(`<input[^>]*name="${name}"[^>]*>`))?.[0];
    expect(input('phone')).not.toContain('required');
    for (const name of ['termsOfService', 'privacyPolicy']) {
      expect(input(name)).toContain('type="checkbox"');
      expect(input(name)).toContain('required');
    }
    expect(input('marketing')).not.toContain('required');
    // 이메일 · 비밀번호 · 확인 · 이름 · 필수 동의 두 칸
    expect(html.match(/ required=""/g)).toHaveLength(6);
    expect(html).toContain('href="/login"');
  });

  it('돌아갈 경로가 있으면 로그인 링크에도 실어 보낸다', () => {
    const html = renderToStaticMarkup(<SignUpForm returnPath="/shop?category=1" />);
    expect(html).toContain('href="/login?next=%2Fshop%3Fcategory%3D1"');
  });
});

describe('signUpErrors', () => {
  it('검증 오류는 칸마다, 동의 칸은 중첩 경로로 붙인다', () => {
    const cause = new ApiError('validation', 400, 'AUTH_100', null, [
      { code: 'AUTH_103', field: 'email', message: '서버 문구' },
      { code: 'AUTH_111', field: 'agreements.termsOfService', message: '서버 문구' },
      { code: 'AUTH_199', field: 'phone', message: '서버 휴대폰 문구' },
    ]);
    const errors = signUpErrors(cause, t);
    expect(errors.email).toEqual(['이메일 형식이 올바르지 않습니다.']);
    expect(errors.termsOfService).toEqual(['이용약관에 동의해야 가입할 수 있습니다.']);
    // 사전에 없는 코드는 서버 문구
    expect(errors.phone).toEqual(['서버 휴대폰 문구']);
    expect(errors.form).toEqual([]);
  });

  it('모르는 칸의 검증 오류는 폼 위 다시 시도 안내를 함께 낸다', () => {
    const cause = new ApiError('validation', 400, 'AUTH_100', null, [
      { code: 'AUTH_100', field: 'role', message: '서버 문구' },
    ]);
    expect(signUpErrors(cause, t).form).toEqual([t.signUp.failed]);
  });

  it('이메일 중복(409 AUTH_002)은 이메일 칸에 붙인다 — 영문 스토어는 영문 문구', () => {
    const cause = new ApiError('conflict', 409, 'AUTH_002', '이미 가입된 이메일입니다.');
    expect(signUpErrors(cause, t)).toMatchObject({
      email: ['이미 가입된 이메일입니다.'],
      form: [],
    });
    expect(signUpErrors(cause, en.auth).email).toEqual(['This email is already registered.']);
  });

  it('가입 시도 제한(429 AUTH_041)은 남은 시간을 담은 전용 안내다', () => {
    const cause = new ApiError('rateLimited', 429, 'AUTH_041', null, [], 120);
    expect(signUpErrors(cause, t)).toMatchObject({
      form: ['가입 시도가 너무 많습니다. 2분 뒤에 다시 시도해 주세요.'],
      email: [],
    });
  });

  it('연결 실패 · 일시 장애 · 예상 밖 오류는 다시 시도 안내 한 문구', () => {
    for (const cause of [
      new ApiError('network', null, null, null),
      new ApiError('unavailable', 503, 'BFF_002', null),
      new Error('boom'),
    ]) {
      expect(signUpErrors(cause, t)).toMatchObject({ form: [t.signUp.failed], email: [] });
    }
  });
});

describe('signedUpLoginPath', () => {
  const result = { signedIn: false, loginRateLimit: null };

  it('자동 로그인을 못 했으면 가입 완료 안내와 돌아갈 경로를 싣는다', () => {
    expect(signedUpLoginPath(result, '/')).toBe('/login?signedUp=1');
    expect(signedUpLoginPath(result, '/cart')).toBe('/login?signedUp=1&next=%2Fcart');
  });

  it('자동 로그인이 요청 제한이면 Retry-After 초(모르면 빈 값)와 코드를 싣는다', () => {
    expect(
      signedUpLoginPath(
        { ...result, loginRateLimit: { code: 'AUTH_040', retryAfterSec: 840 } },
        '/',
      ),
    ).toBe('/login?signedUp=1&limited=840&reason=AUTH_040');
    expect(
      signedUpLoginPath({ ...result, loginRateLimit: { code: null, retryAfterSec: null } }, '/'),
    ).toBe('/login?signedUp=1&limited=');
  });
});
