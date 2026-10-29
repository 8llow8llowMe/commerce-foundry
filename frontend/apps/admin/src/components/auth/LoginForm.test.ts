import { describe, expect, it } from 'vitest';
import { submitLabel } from './LoginForm';

describe('submitLabel', () => {
  it('보내는 중이면 진행 문구가 남은 시간보다 먼저다', () => {
    expect(submitLabel(true, 30)).toBe('로그인하는 중…');
  });

  it('시도 제한 중이면 남은 시간을 보인다 — 1분 이상은 분으로 올린다', () => {
    expect(submitLabel(false, 840)).toBe('14분 뒤에 다시 시도할 수 있습니다');
    expect(submitLabel(false, 9)).toBe('9초 뒤에 다시 시도할 수 있습니다');
  });

  it('막혀 있지 않으면 기본 문구다', () => {
    expect(submitLabel(false, 0)).toBe('로그인');
  });
});
