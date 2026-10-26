import { unstable_doesMiddlewareMatch } from 'next/experimental/testing/server';
import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';
import { setFramePolicy } from '@/lib/frame-policy';
import { config, proxy } from './proxy';

// 테스트 환경은 ADMIN_ORIGIN 이 없어 개발 기본값(http://localhost:3001)이다
const run = (path: string) => proxy(new NextRequest(`http://localhost:3000${path}`));

describe('proxy — 메인 전시 미리보기 iframe 허용', () => {
  it('미리보기 경로에만 어드민 origin 의 frame-ancestors 를 붙인다', () => {
    for (const path of ['/preview/main', '/en/preview/main']) {
      const headers = run(path).headers;
      expect(headers.get('content-security-policy')).toBe('frame-ancestors http://localhost:3001');
      // XFO 는 특정 출처를 허용할 수 없어 싣지 않는다
      expect(headers.get('x-frame-options')).toBeNull();
    }
  });
});

describe('proxy — iframe 허용 범위(클릭재킹, #345)', () => {
  it.each(['/', '/login', '/cart', '/checkout', '/shop', '/en/shop'])(
    '%s 는 같은 출처만 감쌀 수 있다',
    (path) => {
      const headers = run(path).headers;
      expect(headers.get('content-security-policy')).toBe("frame-ancestors 'self'");
      expect(headers.get('x-frame-options')).toBe('SAMEORIGIN');
    },
  );

  it.each(['/api/v1/products', '/_next/static/a.js', '/favicon.ico'])(
    '%s 는 화면이 아니라 proxy 를 타지 않는다',
    (url) => {
      expect(unstable_doesMiddlewareMatch({ config, url })).toBe(false);
    },
  );
});

describe('setFramePolicy', () => {
  it('기본값이 아닌 정책에는 X-Frame-Options 를 싣지 않는다(특정 출처를 표현할 수 없다)', () => {
    const headers = new Headers({ 'X-Frame-Options': 'SAMEORIGIN' });
    setFramePolicy(headers, 'frame-ancestors https://admin.test');
    expect(headers.get('content-security-policy')).toBe('frame-ancestors https://admin.test');
    expect(headers.get('x-frame-options')).toBeNull();
  });
});
