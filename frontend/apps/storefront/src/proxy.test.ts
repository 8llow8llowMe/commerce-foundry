import { NextRequest } from 'next/server';
import { describe, expect, it } from 'vitest';
import { proxy } from './proxy';

// 테스트 환경은 ADMIN_ORIGIN 이 없어 개발 기본값(http://localhost:3001)이다
const run = (path: string) => proxy(new NextRequest(`http://localhost:3000${path}`));

describe('proxy — 메인 전시 미리보기 iframe 허용', () => {
  it('미리보기 경로에만 어드민 origin 의 frame-ancestors 를 붙인다', () => {
    expect(run('/preview/main').headers.get('content-security-policy')).toBe(
      'frame-ancestors http://localhost:3001',
    );
    expect(run('/en/preview/main').headers.get('content-security-policy')).toBe(
      'frame-ancestors http://localhost:3001',
    );
  });

  it('다른 경로는 헤더를 바꾸지 않는다', () => {
    expect(run('/').headers.get('content-security-policy')).toBeNull();
    expect(run('/shop').headers.get('content-security-policy')).toBeNull();
  });
});
