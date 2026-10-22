import { unstable_doesMiddlewareMatch } from 'next/experimental/testing/server';
import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';
import { config, proxy } from './proxy';

describe('proxy matcher', () => {
  it.each(['/', '/stocks', '/stocks/12', '/shipping-policies/domestic', '/apix', '/login-history'])(
    '%s 는 진입을 확인한다',
    (url) => {
      expect(unstable_doesMiddlewareMatch({ config, url })).toBe(true);
    },
  );

  it.each(['/login', '/api/v1/admin/stocks', '/api/health', '/_next/static/a.js', '/favicon.ico'])(
    '%s 는 로그인 없이 연다',
    (url) => {
      expect(unstable_doesMiddlewareMatch({ config, url })).toBe(false);
    },
  );
});

describe('proxy', () => {
  it('access 쿠키가 없으면 지금 경로를 next 로 붙여 로그인 화면으로 보낸다', () => {
    const res = proxy(new NextRequest('http://localhost:3001/stocks/12?page=2'));
    expect(res.status).toBe(307);
    expect(res.headers.get('location')).toBe(
      'http://localhost:3001/login?next=%2Fstocks%2F12%3Fpage%3D2',
    );
  });

  it('콘솔 홈이면 next 를 붙이지 않는다', () => {
    const res = proxy(new NextRequest('http://localhost:3001/'));
    expect(res.headers.get('location')).toBe('http://localhost:3001/login');
  });

  it('access 쿠키가 있으면 통과시킨다(토큰 유효성은 API 가 본다)', () => {
    const res = proxy(
      new NextRequest('http://localhost:3001/stocks', { headers: { cookie: 'cf_admin_at=t' } }),
    );
    expect(res.headers.get('location')).toBeNull();
    expect(res.headers.get('x-middleware-next')).toBe('1');
  });
});
