import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { formatShipDate, parseDateOnly, preorderNote } from './preorder';

const today = new Date(2026, 9, 25);

describe('parseDateOnly', () => {
  it('yyyy-MM-dd 를 시간대 없이 그 날짜로 읽는다', () => {
    const date = parseDateOnly('2026-11-20')!;
    expect([date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()]).toEqual([2026, 10, 20]);
  });

  it('형식이 틀리거나 없는 날짜면 null', () => {
    expect(parseDateOnly('2026-11-20T00:00:00Z')).toBeNull();
    expect(parseDateOnly('2026/11/20')).toBeNull();
    expect(parseDateOnly('2026-02-30')).toBeNull();
    expect(parseDateOnly('')).toBeNull();
  });
});

describe('formatShipDate', () => {
  it('올해 날짜는 월 · 일만 스토어 언어로', () => {
    const date = parseDateOnly('2026-11-20')!;
    expect(formatShipDate(date, 'ko', today)).toBe('11월 20일');
    expect(formatShipDate(date, 'en', today)).toBe('November 20');
  });

  it('올해가 아니면 연도를 붙인다', () => {
    const date = parseDateOnly('2027-01-05')!;
    expect(formatShipDate(date, 'ko', today)).toBe('2027년 1월 5일');
    expect(formatShipDate(date, 'en', today)).toBe('January 5, 2027');
  });
});

describe('preorderNote', () => {
  it('프리오더가 아니면 null — 일반 배송 안내만 보인다', () => {
    expect(preorderNote(null, 'ko', ko.product.preorder, today)).toBeNull();
  });

  it('발송 예정일이 있으면 그 날짜부터 순차 발송', () => {
    const preorder = { expectedShipDate: '2026-11-20' };
    expect(preorderNote(preorder, 'ko', ko.product.preorder, today)).toBe(
      '프리오더 · 11월 20일부터 순차 발송',
    );
    expect(preorderNote(preorder, 'en', en.product.preorder, today)).toBe(
      'Pre-order · Ships from November 20',
    );
  });

  it('발송 예정일이 없거나 읽을 수 없으면 추후 안내', () => {
    expect(preorderNote({ expectedShipDate: null }, 'ko', ko.product.preorder, today)).toBe(
      '프리오더 · 발송일 추후 안내',
    );
    expect(preorderNote({ expectedShipDate: 'soon' }, 'en', en.product.preorder, today)).toBe(
      'Pre-order · Ship date to be announced',
    );
  });
});
