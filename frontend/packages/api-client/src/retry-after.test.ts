import { describe, expect, it } from 'vitest';
import { parseRetryAfter } from './retry-after';

const NOW = Date.parse('2026-10-19T00:00:00Z');

describe('parseRetryAfter', () => {
  it('delta-seconds 는 그대로 초다', () => {
    expect(parseRetryAfter('3', NOW)).toBe(3);
    expect(parseRetryAfter(' 0 ', NOW)).toBe(0);
  });

  it('HTTP-date 는 지금부터 남은 초로 올림한다', () => {
    expect(parseRetryAfter('Mon, 19 Oct 2026 00:00:02 GMT', NOW)).toBe(2);
    expect(parseRetryAfter(new Date(NOW + 1500).toUTCString(), NOW - 400)).toBe(2);
  });

  it('지난 날짜는 0 이다', () => {
    expect(parseRetryAfter('Sun, 18 Oct 2026 23:59:00 GMT', NOW)).toBe(0);
  });

  it.each([null, '', '  ', '-1', '1.5', 'soon'])('읽을 수 없는 값 %j 은 null 이다', (value) => {
    expect(parseRetryAfter(value, NOW)).toBeNull();
  });
});
