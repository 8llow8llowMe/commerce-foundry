import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  LOGIN_DEFAULT_COOLDOWN_SEC,
  LOGIN_MAX_COOLDOWN_SEC,
  formatWait,
  loginCooldownSec,
  remainingSecOf,
} from './login-cooldown';

const limited = (retryAfterSec: number | null) =>
  new ApiError('rateLimited', 429, 'AUTH_040', '많음', [], retryAfterSec);

describe('loginCooldownSec', () => {
  it('429 면 Retry-After 초만큼 막는다 — 관리자 창(15분)을 다 덮는다', () => {
    expect(loginCooldownSec(limited(840))).toBe(840);
  });

  it('Retry-After 가 없으면 기본값, 0 이면 최소 1초, 너무 크면 상한이다', () => {
    expect(loginCooldownSec(limited(null))).toBe(LOGIN_DEFAULT_COOLDOWN_SEC);
    expect(loginCooldownSec(limited(0))).toBe(1);
    expect(loginCooldownSec(limited(999_999))).toBe(LOGIN_MAX_COOLDOWN_SEC);
  });

  it('429 가 아니면(503 AUTH_013 · 400 · 일반 오류) 막지 않는다', () => {
    expect(loginCooldownSec(new ApiError('unavailable', 503, 'AUTH_013', '장애'))).toBe(0);
    expect(loginCooldownSec(new ApiError('validation', 400, 'AUTH_030', '틀림'))).toBe(0);
    expect(loginCooldownSec(new Error('boom'))).toBe(0);
  });
});

describe('remainingSecOf', () => {
  it('남은 시간을 초로 올리고, 지났거나 막지 않았으면 0 이다', () => {
    expect(remainingSecOf(10_000, 0)).toBe(10);
    expect(remainingSecOf(10_000, 9_001)).toBe(1);
    expect(remainingSecOf(10_000, 10_000)).toBe(0);
    expect(remainingSecOf(10_000, 12_000)).toBe(0);
    expect(remainingSecOf(null, 0)).toBe(0);
  });
});

describe('formatWait', () => {
  it('1분 미만은 초, 1분 이상은 분으로 올린다', () => {
    expect(formatWait(59)).toBe('59초');
    expect(formatWait(60)).toBe('1분');
    expect(formatWait(61)).toBe('2분');
    expect(formatWait(840)).toBe('14분');
  });
});
