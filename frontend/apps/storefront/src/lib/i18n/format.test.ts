import { describe, expect, it } from 'vitest';
import { fill } from './format';

describe('fill', () => {
  it('자리표시자를 값으로 바꾸고 없는 값은 그대로 둔다', () => {
    expect(fill('{name} 이미지 {count}장', { name: '드레스', count: 3 })).toBe('드레스 이미지 3장');
    expect(fill('{name} 전체 {missing}', { name: '원피스' })).toBe('원피스 전체 {missing}');
  });
});
