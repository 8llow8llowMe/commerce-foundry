import { describe, expect, it } from 'vitest';
import { formatMoney, moneyToDecimalString, parseMoneyInput } from './money';

describe('formatMoney', () => {
  it('KRW 는 원 단위 정수를 "129,000원" 으로 표시한다', () => {
    expect(formatMoney({ amount: 129000, currency: 'KRW' })).toBe('129,000원');
    // 영문 스토어는 통화 기호로
    expect(formatMoney({ amount: 129000, currency: 'KRW' }, 'en')).toBe('₩129,000');
    expect(formatMoney({ amount: 8950, currency: 'USD' }, 'ko')).toBe('$89.50');
  });

  it('USD 는 센트 정수를 달러로 표시한다', () => {
    expect(formatMoney({ amount: 8950, currency: 'USD' })).toBe('$89.50');
    expect(formatMoney({ amount: 5, currency: 'USD' })).toBe('$0.05');
  });

  it('0 과 음수(환불 등)를 표시한다', () => {
    expect(formatMoney({ amount: 0, currency: 'KRW' })).toBe('0원');
    expect(formatMoney({ amount: -3000, currency: 'KRW' })).toBe('-3,000원');
    expect(formatMoney({ amount: -8950, currency: 'USD' })).toBe('-$89.50');
  });

  it('정수가 아닌 amount 는 계약 위반으로 던진다', () => {
    expect(() => formatMoney({ amount: 89.5, currency: 'USD' })).toThrow(/최소 화폐 단위 정수/);
    expect(() => formatMoney({ amount: Number.NaN, currency: 'KRW' })).toThrow();
  });
});

describe('moneyToDecimalString', () => {
  it('통화의 소수 자릿수로 쓴다', () => {
    expect(moneyToDecimalString({ amount: 129000, currency: 'KRW' })).toBe('129000');
    expect(moneyToDecimalString({ amount: 8950, currency: 'USD' })).toBe('89.50');
    expect(moneyToDecimalString({ amount: 5, currency: 'USD' })).toBe('0.05');
  });
});

describe('parseMoneyInput', () => {
  it('입력 글자를 최소 단위 정수로 바꾼다 — 쉼표 · 공백은 무시한다', () => {
    expect(parseMoneyInput(' 3,000 ', 'KRW')).toBe(3000);
    expect(parseMoneyInput('1,234,567', 'KRW')).toBe(1234567);
    expect(parseMoneyInput('1,200.5', 'USD')).toBe(120050);
    expect(parseMoneyInput('12.5', 'USD')).toBe(1250);
    expect(parseMoneyInput('12', 'USD')).toBe(1200);
    expect(parseMoneyInput('0', 'KRW')).toBe(0);
  });

  it('빈 값은 null 이다', () => {
    expect(parseMoneyInput('  ', 'KRW')).toBeNull();
  });

  it('숫자가 아니거나 음수 · 소수 자릿수 초과면 NaN 이다', () => {
    for (const [text, currency] of [
      ['abc', 'KRW'],
      ['-100', 'KRW'],
      ['10.5', 'KRW'],
      ['1.234', 'USD'],
      ['1e3', 'KRW'],
      ['99999999999999999999', 'KRW'],
      ['12,50', 'USD'],
      ['3,00', 'KRW'],
    ] as const) {
      expect(parseMoneyInput(text, currency)).toBeNaN();
    }
  });
});
