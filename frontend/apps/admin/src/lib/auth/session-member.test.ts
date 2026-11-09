import { describe, expect, it } from 'vitest';
import { memberIdOfToken, operatorRoleOfToken } from './session-member';

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

describe('operatorRoleOfToken', () => {
  it('payload 의 role 이 운영자 역할이면 그대로 돌려준다', () => {
    expect(operatorRoleOfToken(token({ sub: '1', role: 'MANAGER' }))).toBe('MANAGER');
    expect(operatorRoleOfToken(token({ sub: '1', role: 'ADMIN' }))).toBe('ADMIN');
  });

  it('고객 · 모르는 값 · 대소문자 다름 · 읽을 수 없음은 null 이다', () => {
    expect(operatorRoleOfToken(token({ role: 'CUSTOMER' }))).toBeNull();
    expect(operatorRoleOfToken(token({ role: 'manager' }))).toBeNull();
    expect(operatorRoleOfToken(token({ role: ['OWNER'] }))).toBeNull();
    expect(operatorRoleOfToken(token({}))).toBeNull();
    expect(operatorRoleOfToken(null)).toBeNull();
    expect(operatorRoleOfToken('opaque-token')).toBeNull();
  });
});
