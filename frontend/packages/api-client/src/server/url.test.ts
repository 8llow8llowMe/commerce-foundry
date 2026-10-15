import { describe, expect, it } from 'vitest';
import { joinUrl } from './url';

describe('joinUrl', () => {
  it('base 끝 슬래시를 하나도 남기지 않고 붙인다', () => {
    expect(joinUrl('http://localhost:8000', '/api/v1/a?x=1')).toBe(
      'http://localhost:8000/api/v1/a?x=1',
    );
    expect(joinUrl('http://localhost:8000/', '/api/v1/a')).toBe('http://localhost:8000/api/v1/a');
    expect(joinUrl('http://localhost:8000//', '/api/v1/a')).toBe('http://localhost:8000/api/v1/a');
  });

  it('base 의 경로 접두사를 보존한다', () => {
    expect(joinUrl('https://gw.example.com/edge/', '/api/v1/a')).toBe(
      'https://gw.example.com/edge/api/v1/a',
    );
  });
});
