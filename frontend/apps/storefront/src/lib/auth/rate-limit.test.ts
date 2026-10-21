import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  MAX_LINKED_RETRY_AFTER_SEC,
  rateLimitText,
  rateLimitTextOf,
  readLoginRateLimit,
} from './rate-limit';

describe('rateLimitText', () => {
  it('코드로 이유를 고르고 Retry-After 를 남은 시간으로 붙인다 — 1분 이상은 분 단위로 올린다', () => {
    expect(rateLimitText('AUTH_040', 840, ko.auth)).toBe(
      '로그인 시도가 너무 많습니다. 14분 뒤에 다시 시도해 주세요.',
    );
    expect(rateLimitText('AUTH_040', 841, ko.auth)).toContain('15분 뒤');
    expect(rateLimitText('AUTH_041', 30, ko.auth)).toBe(
      '가입 시도가 너무 많습니다. 30초 뒤에 다시 시도해 주세요.',
    );
  });

  it('영문 스토어는 영문 문구다', () => {
    expect(rateLimitText('AUTH_040', 900, en.auth)).toBe(
      'Too many sign-in attempts. Please try again in 15 min.',
    );
  });

  it('남은 시간을 모르면 잠시 후, 모르는 코드는 일반 요청 제한 문구다', () => {
    expect(rateLimitText('AUTH_040', null, ko.auth)).toBe(
      '로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.',
    );
    expect(rateLimitText('GATEWAY_006', 5, ko.auth)).toBe(
      '요청이 너무 많습니다. 5초 뒤에 다시 시도해 주세요.',
    );
  });
});

describe('rateLimitTextOf', () => {
  it('429 rateLimited 만 문구를 내고 그 밖은 null 이다', () => {
    expect(
      rateLimitTextOf(new ApiError('rateLimited', 429, 'AUTH_040', null, [], 60), ko.auth),
    ).toBe('로그인 시도가 너무 많습니다. 1분 뒤에 다시 시도해 주세요.');
    expect(rateLimitTextOf(new ApiError('server', 400, 'AUTH_001', null), ko.auth)).toBeNull();
    expect(rateLimitTextOf(new Error('boom'), ko.auth)).toBeNull();
  });
});

describe('readLoginRateLimit', () => {
  it('가입 완료 안내와 함께 온 limited · reason 을 읽는다', () => {
    expect(
      readLoginRateLimit({ signedUp: '1', limited: '840', reason: 'AUTH_040' }, ko.auth),
    ).toEqual({ code: 'AUTH_040', retryAfterSec: 840 });
  });

  it('가입 완료 안내가 아니거나 limited 가 없으면 무시한다', () => {
    expect(readLoginRateLimit({ limited: '840' }, ko.auth)).toBeNull();
    expect(readLoginRateLimit({ signedUp: '1' }, ko.auth)).toBeNull();
  });

  it('빈 값 · 잘못된 값 · 상한 초과는 모름(null), 사전에 없는 코드는 null — 안내는 잠시 후 · 일반 문구가 된다', () => {
    for (const limited of ['', 'abc', '0', '-5', String(MAX_LINKED_RETRY_AFTER_SEC + 1)]) {
      expect(readLoginRateLimit({ signedUp: '1', limited }, ko.auth)?.retryAfterSec).toBeNull();
    }
    const limit = readLoginRateLimit({ signedUp: '1', limited: '', reason: '<script>' }, ko.auth)!;
    expect(limit.code).toBeNull();
    expect(rateLimitText(limit.code, limit.retryAfterSec, ko.auth)).toBe(
      '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
    );
  });
});
