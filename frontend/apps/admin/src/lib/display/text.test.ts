import { describe, expect, it } from 'vitest';
import {
  hasRejectedCharacter,
  isAllowedLinkUrl,
  isoToLocalInput,
  localInputToIso,
  normalizeText,
  periodLabel,
} from './text';

describe('normalizeText', () => {
  it('앞뒤 공백을 빼고 비면 null 이다(BE TextValues.normalize)', () => {
    expect(normalizeText('  봄 신상 ')).toBe('봄 신상');
    expect(normalizeText('   ')).toBeNull();
    expect(normalizeText('')).toBeNull();
    expect(normalizeText('　가　')).toBe('가');
  });
});

describe('hasRejectedCharacter', () => {
  it('한 줄 칸은 개행 · 탭 · 제어 문자를 거절한다', () => {
    expect(hasRejectedCharacter('제목')).toBe(false);
    expect(hasRejectedCharacter('제목\n둘째 줄')).toBe(true);
    expect(hasRejectedCharacter('제목\t')).toBe(true);
    expect(hasRejectedCharacter('a\u0000b')).toBe(true);
  });

  it('본문은 개행 · 탭만 허용한다', () => {
    expect(hasRejectedCharacter('첫 줄\r\n둘째 줄\t끝', true)).toBe(false);
    expect(hasRejectedCharacter('a\u0007b', true)).toBe(true);
  });

  it('짝 없는 서로게이트는 거절하고 이모지 · ZWJ 시퀀스는 받는다', () => {
    expect(hasRejectedCharacter('a\uD800b')).toBe(true);
    expect(hasRejectedCharacter('가족 👨‍👩‍👧')).toBe(false);
  });
});

describe('isAllowedLinkUrl', () => {
  it.each([
    '/',
    '/shop',
    '/products?categoryId=11',
    '/a%20b',
    '/한복',
    'https://example.test/x',
    'HTTPS://example.test',
    'https://example.test:8443/a',
  ])('%s 는 받는다', (url) => expect(isAllowedLinkUrl(url)).toBe(true));

  it.each([
    '',
    '//evil.test',
    'http://example.test',
    'javascript:alert(1)',
    'shop',
    '/a b',
    '/a\\b',
    'https://user@example.test',
    'https://',
    'https://:443/a',
    '/a%zz',
    '/a<b>',
    '/a​b',
  ])('%s 는 거절한다', (url) => expect(isAllowedLinkUrl(url)).toBe(false));
});

describe('기간 변환', () => {
  it('datetime-local 글자 ↔ ISO 가 이 컴퓨터 시간대로 왕복한다', () => {
    const iso = localInputToIso('2026-11-01T09:30');
    expect(iso).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:00\.000Z$/);
    expect(isoToLocalInput(iso)).toBe('2026-11-01T09:30');
  });

  it('형식이 틀리거나 연도가 0001 ~ 9999 밖이면 null 이다', () => {
    expect(localInputToIso('2026-11-01')).toBeNull();
    expect(localInputToIso('0000-01-01T00:00')).toBeNull();
    expect(localInputToIso('10000-01-01T00:00')).toBeNull();
    expect(localInputToIso('2026-13-01T00:00')).toBeNull();
  });

  it('없거나 읽을 수 없는 ISO 는 빈 글자다', () => {
    expect(isoToLocalInput(null)).toBe('');
    expect(isoToLocalInput('nope')).toBe('');
  });
});

describe('periodLabel', () => {
  const start = localInputToIso('2026-11-01T00:00');
  const end = localInputToIso('2026-12-01T00:00');

  it('기간이 없으면 제한 없음, 한쪽만 있으면 부터 · 까지다', () => {
    expect(periodLabel(null, null)).toBe('기간 제한 없음');
    expect(periodLabel(start, null)).toBe('2026.11.01 00:00부터');
    expect(periodLabel(null, end)).toBe('2026.12.01 00:00까지');
    expect(periodLabel(start, end)).toBe('2026.11.01 00:00 ~ 2026.12.01 00:00');
  });
});
