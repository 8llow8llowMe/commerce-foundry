import { describe, expect, it, vi } from 'vitest';
import { prefersReducedMotion, tourMotion } from './steps';

const media = (matches: boolean) =>
  vi.fn((query: string) => ({ matches, media: query }) as MediaQueryList);

describe('prefersReducedMotion', () => {
  it('동작 줄이기를 켠 운영자면 true 다', () => {
    const matchMedia = media(true);
    expect(prefersReducedMotion({ matchMedia })).toBe(true);
    expect(matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)');
    expect(prefersReducedMotion({ matchMedia: media(false) })).toBe(false);
  });

  it('matchMedia 가 없거나 실패하면 켜지 않은 것으로 본다', () => {
    expect(prefersReducedMotion(undefined)).toBe(false);
    expect(prefersReducedMotion({})).toBe(false);
    const broken = vi.fn(() => {
      throw new Error('unsupported');
    });
    expect(prefersReducedMotion({ matchMedia: broken })).toBe(false);
  });
});

describe('tourMotion', () => {
  it('동작 줄이기면 투어 애니메이션을 끈다. 스크롤은 늘 즉시다', () => {
    expect(tourMotion(true)).toEqual({ animate: false, smoothScroll: false });
    expect(tourMotion(false)).toEqual({ animate: true, smoothScroll: false });
  });
});
