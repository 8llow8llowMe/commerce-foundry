import { describe, expect, it } from 'vitest';
import { safeReturnPath } from './return-path';

describe('safeReturnPath', () => {
  it('같은 사이트의 앱 경로는 쿼리까지 그대로 둔다', () => {
    expect(safeReturnPath('/shop?category=2&sort=NEWEST')).toBe('/shop?category=2&sort=NEWEST');
    expect(safeReturnPath(['/products/1', '/x'])).toBe('/products/1');
  });

  it('없거나 외부 · 프로토콜 상대 · 역슬래시 · 제어 문자 주소는 메인으로', () => {
    for (const next of [
      undefined,
      '',
      'https://evil.test',
      '//evil.test',
      '/\\evil.test',
      '/\t/evil.test',
      'shop',
      `/${'a'.repeat(600)}`,
    ]) {
      expect(safeReturnPath(next)).toBe('/');
    }
  });

  it('로그인 · 회원가입 화면으로는 되돌아가지 않는다', () => {
    expect(safeReturnPath('/login?next=/shop')).toBe('/');
    expect(safeReturnPath('/signup')).toBe('/');
  });
});
