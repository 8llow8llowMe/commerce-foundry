import { describe, expect, it } from 'vitest';
import { craftStep } from './CraftZoom';

describe('craftStep', () => {
  it('스크롤 위치(0~1)를 단계 수로 나눈다 — 처음은 전신, 끝은 마지막 포인트', () => {
    expect(craftStep(0, 4)).toBe(0);
    expect(craftStep(0.24, 4)).toBe(0);
    expect(craftStep(0.25, 4)).toBe(1);
    expect(craftStep(0.99, 4)).toBe(3);
    expect(craftStep(1, 4)).toBe(3);
  });

  it('범위 밖 값은 양 끝으로 붙이고, 단계가 하나면 늘 전신이다', () => {
    expect(craftStep(-0.3, 4)).toBe(0);
    expect(craftStep(1.7, 4)).toBe(3);
    expect(craftStep(0.8, 1)).toBe(0);
  });
});
