import { describe, expect, it } from 'vitest';
import { clientIpHeaderFromEnv, resolveClientIp, setClientIp } from './client-ip';

const request = (headers: Record<string, string>) =>
  new Request('http://localhost:3000/api/v1/x', { headers });

describe('resolveClientIp', () => {
  it('기본은 x-real-ip 를 읽고, 브라우저가 보낸 x-forwarded-for 는 보지 않는다', () => {
    expect(
      resolveClientIp(request({ 'x-real-ip': '203.0.113.7', 'x-forwarded-for': '6.6.6.6' })),
    ).toBe('203.0.113.7');
    expect(resolveClientIp(request({ 'x-forwarded-for': '6.6.6.6' }))).toBeNull();
  });

  it('쉼표 목록이면 맨 오른쪽(바로 앞 프록시가 붙인 값)만 쓴다', () => {
    const req = request({ 'x-forwarded-for': '6.6.6.6, 203.0.113.7' });
    expect(resolveClientIp(req, 'x-forwarded-for')).toBe('203.0.113.7');
  });

  it('IPv6 주소를 받는다', () => {
    expect(resolveClientIp(request({ 'x-real-ip': '2001:db8::1' }))).toBe('2001:db8::1');
  });

  it.each(['example.com', '203.0.113.7:443', '[2001:db8::1]', 'unknown', ''])(
    '주소가 아닌 값(%s)은 버린다',
    (value) => {
      expect(resolveClientIp(request({ 'x-real-ip': value }))).toBeNull();
    },
  );

  it('헤더를 null 로 끄면 읽지 않는다', () => {
    expect(resolveClientIp(request({ 'x-real-ip': '203.0.113.7' }), null)).toBeNull();
  });
});

describe('setClientIp', () => {
  it('값이 있으면 x-forwarded-for 를 그 한 값으로 덮어쓰고, 없으면 지운다', () => {
    const headers = new Headers({ 'x-forwarded-for': '6.6.6.6' });
    expect(setClientIp(headers, '203.0.113.7').get('x-forwarded-for')).toBe('203.0.113.7');
    expect(setClientIp(headers, null).has('x-forwarded-for')).toBe(false);
  });
});

describe('clientIpHeaderFromEnv', () => {
  it('비면 x-real-ip, none 이면 끔, 그 밖은 소문자 헤더 이름', () => {
    expect(clientIpHeaderFromEnv(undefined)).toBe('x-real-ip');
    expect(clientIpHeaderFromEnv(' ')).toBe('x-real-ip');
    expect(clientIpHeaderFromEnv('none')).toBeNull();
    expect(clientIpHeaderFromEnv('CF-Connecting-IP')).toBe('cf-connecting-ip');
  });
});
