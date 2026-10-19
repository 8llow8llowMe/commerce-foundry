import { describe, expect, it } from 'vitest';
import { formatMoney, moneyToDecimalString } from './money';

describe('formatMoney', () => {
  it('KRW 는 원 단위 정수를 "129,000원" 으로 표시한다', () => {
    expect(formatMoney({ amount: 129000, currency: 'KRW' })).toBe('129,000원');
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
