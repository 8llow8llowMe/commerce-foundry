import { describe, expect, it } from 'vitest';
import { isSocialProvider, SOCIAL_PROVIDERS } from './social-provider';

describe('isSocialProvider', () => {
  it('kakao · naver 만 받는다', () => {
    expect(SOCIAL_PROVIDERS).toEqual(['kakao', 'naver']);
    expect(isSocialProvider('kakao')).toBe(true);
    expect(isSocialProvider('naver')).toBe(true);
    expect(isSocialProvider('Naver')).toBe(false);
    expect(isSocialProvider('google')).toBe(false);
    expect(isSocialProvider(undefined)).toBe(false);
  });
});
