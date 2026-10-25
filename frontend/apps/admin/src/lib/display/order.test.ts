import { describe, expect, it } from 'vitest';
import { moveItem, orderRequest, sameOrder } from './order';

describe('moveItem', () => {
  it('항목을 새 자리로 옮긴 새 배열을 돌려준다(원본은 그대로)', () => {
    const list = ['a', 'b', 'c', 'd'];
    expect(moveItem(list, 0, 2)).toEqual(['b', 'c', 'a', 'd']);
    expect(moveItem(list, 3, 0)).toEqual(['d', 'a', 'b', 'c']);
    expect(moveItem(list, 1, 2)).toEqual(['a', 'c', 'b', 'd']);
    expect(list).toEqual(['a', 'b', 'c', 'd']);
  });

  it('같은 자리 · 범위 밖이면 그대로다', () => {
    expect(moveItem(['a', 'b'], 1, 1)).toEqual(['a', 'b']);
    expect(moveItem(['a', 'b'], 0, -1)).toEqual(['a', 'b']);
    expect(moveItem(['a', 'b'], 0, 2)).toEqual(['a', 'b']);
  });
});

describe('sameOrder', () => {
  it('길이 · 순서가 모두 같을 때만 같다', () => {
    expect(sameOrder(['1', '2'], ['1', '2'])).toBe(true);
    expect(sameOrder(['1', '2'], ['2', '1'])).toBe(false);
    expect(sameOrder(['1'], ['1', '2'])).toBe(false);
  });
});

describe('orderRequest', () => {
  it('섹션 아이디 전체를 새 순서대로 문자열 그대로 보낸다(큰 아이디도 잃지 않는다)', () => {
    const ids = ['4611686018427387905', '4611686018427387904'];
    const request = orderRequest(ids);
    expect(request).toEqual({ sectionIds: ['4611686018427387905', '4611686018427387904'] });
    expect(JSON.stringify(request)).toBe(
      '{"sectionIds":["4611686018427387905","4611686018427387904"]}',
    );
    expect(request.sectionIds).not.toBe(ids);
  });
});
