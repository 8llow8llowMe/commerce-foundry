import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { cooldownMsFor, DEFAULT_COOLDOWN_SEC, MAX_COOLDOWN_SEC } from './retry-cooldown';

const rateLimited = (retryAfterSec: number | null) =>
  new ApiError('rateLimited', 429, 'GATEWAY_006', null, [], retryAfterSec);

describe('cooldownMsFor', () => {
  it('429 면 Retry-After 초만큼 막는다', () => {
    expect(cooldownMsFor(rateLimited(4))).toBe(4000);
  });

  it('Retry-After 가 없으면 기본값만큼 막는다', () => {
    expect(cooldownMsFor(rateLimited(null))).toBe(DEFAULT_COOLDOWN_SEC * 1000);
  });

  it('0 초여도 최소 1초는 막고, 상한을 넘지 않는다', () => {
    expect(cooldownMsFor(rateLimited(0))).toBe(1000);
    expect(cooldownMsFor(rateLimited(3600))).toBe(MAX_COOLDOWN_SEC * 1000);
  });

  it('429 가 아닌 오류는 막지 않는다', () => {
    expect(cooldownMsFor(new ApiError('unavailable', 503, 'GATEWAY_002', null))).toBe(0);
    expect(cooldownMsFor(new Error('boom'))).toBe(0);
    expect(cooldownMsFor(null)).toBe(0);
  });
});
