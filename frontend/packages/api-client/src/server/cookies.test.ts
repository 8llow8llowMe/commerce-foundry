import { describe, expect, it } from 'vitest';
import { readCookie, serializeCookie } from './cookies';

describe('readCookie', () => {
  it('이름이 정확히 같은 쿠키 값을 디코딩해 읽는다', () => {
    const header = 'theme=dark; cf_at=abc%2Edef; cf_at_old=zzz';
    expect(readCookie(header, 'cf_at')).toBe('abc.def');
  });

  it('없거나 헤더가 없으면 null', () => {
    expect(readCookie('a=1', 'cf_at')).toBeNull();
    expect(readCookie(null, 'cf_at')).toBeNull();
  });

  it('값에 = 가 있어도 첫 = 뒤 전체를 값으로 본다', () => {
    expect(readCookie('cf_at=a=b==', 'cf_at')).toBe('a=b==');
  });

  it('깨진 퍼센트 인코딩은 없는 쿠키로 본다', () => {
    expect(readCookie('cf_at=%E0%A4%A', 'cf_at')).toBeNull();
  });
});

describe('serializeCookie', () => {
  it('HttpOnly · SameSite=Lax · Path=/ 와 Max-Age 를 붙인다', () => {
    expect(serializeCookie('cf_at', 'a.b', 1800, false)).toBe(
      'cf_at=a.b; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
    );
  });

  it('secure 면 Secure 를 붙인다', () => {
    expect(serializeCookie('cf_at', 'v', 0, true)).toBe(
      'cf_at=v; Path=/; HttpOnly; SameSite=Lax; Max-Age=0; Secure',
    );
  });
});
