import { describe, expect, it } from 'vitest';
import { loginHref, safeReturnPath } from './return-path';

describe('safeReturnPath', () => {
  it('같은 사이트 경로는 쿼리까지 그대로 둔다', () => {
    expect(safeReturnPath('/stocks/12?page=2')).toBe('/stocks/12?page=2');
    expect(safeReturnPath(['/stocks', '/x'])).toBe('/stocks');
  });

  it.each([
    undefined,
    '',
    'stocks',
    'https://evil.test',
    '//evil.test',
    '/\\evil.test',
    '/\tevil',
    '/login',
    '/login?next=/stocks',
    '/login/',
    '/LOGIN',
    '/login;x',
    '/api/v1/auth/logout',
    `/${'a'.repeat(600)}`,
  ])('%s 는 콘솔 홈으로 바꾼다', (value) => {
    expect(safeReturnPath(value)).toBe('/');
  });
});

describe('loginHref', () => {
  it('돌아갈 경로를 next 로 인코딩해 붙인다', () => {
    expect(loginHref('/stocks/12?page=2')).toBe('/login?next=%2Fstocks%2F12%3Fpage%3D2');
  });

  it('콘솔 홈 · 안전하지 않은 경로면 next 를 붙이지 않는다', () => {
    expect(loginHref('/')).toBe('/login');
    expect(loginHref('//evil.test')).toBe('/login');
  });
});
