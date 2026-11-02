import { describe, expect, it } from 'vitest';
import { memberIdOfToken } from './session-member';

const base64url = (value: string) =>
  btoa(String.fromCharCode(...new TextEncoder().encode(value)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');

const token = (payload: unknown) =>
  `${base64url('{"alg":"RS256"}')}.${base64url(JSON.stringify(payload))}.signature`;

describe('memberIdOfToken', () => {
  it('payload 의 sub(memberId 문자열)를 글자 그대로 돌려준다', () => {
    expect(
      memberIdOfToken(token({ sub: '312345678901234567', role: 'ADMIN', name: '관리자' })),
    ).toBe('312345678901234567');
  });

  it('쿠키가 없거나 모양이 다르면 null 이다', () => {
    expect(memberIdOfToken(null)).toBeNull();
    expect(memberIdOfToken(undefined)).toBeNull();
    expect(memberIdOfToken('')).toBeNull();
    expect(memberIdOfToken('opaque-token')).toBeNull();
    expect(memberIdOfToken('a.%%%.c')).toBeNull();
    expect(memberIdOfToken(`a.${base64url('not json')}.c`)).toBeNull();
  });

  it('sub 가 숫자 글자가 아니면 null 이다', () => {
    expect(memberIdOfToken(token({ sub: 12 }))).toBeNull();
    expect(memberIdOfToken(token({ sub: 'abc' }))).toBeNull();
    expect(memberIdOfToken(token({}))).toBeNull();
  });
});
