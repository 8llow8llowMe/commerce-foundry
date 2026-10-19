// 금액 계약: amount 는 최소 화폐 단위 정수(KRW 원, USD 센트). backend/docs/api-design-guide.md §2-3
// 프론트는 표시만 한다. 합계·할인 계산은 서버 값을 쓴다.

export type Currency = 'KRW' | 'USD';

export type Money = {
  amount: number;
  currency: Currency;
};

function fractionDigits(currency: Currency): number {
  return (
    new Intl.NumberFormat('en-US', { style: 'currency', currency }).resolvedOptions()
      .maximumFractionDigits ?? 0
  );
}

/**
 * 화면 표시용 금액. locale 은 스토어 언어('ko' · 'en')다.
 * 국문 스토어의 KRW 는 국내 관례대로 '129,000원', 그 밖에는 통화 기호 표기('₩129,000', '$89.50').
 */
export function formatMoney({ amount, currency }: Money, locale: string = 'ko'): string {
  if (!Number.isSafeInteger(amount)) {
    if (process.env.NODE_ENV !== 'production') {
      throw new Error(`amount 는 최소 화폐 단위 정수여야 한다: ${amount} ${currency}`);
    }
  }
  const major = amount / 10 ** fractionDigits(currency);

  if (currency === 'KRW' && locale === 'ko') {
    // 국내 쇼핑몰 관례: ₩129,000 이 아니라 129,000원
    return `${new Intl.NumberFormat('ko-KR').format(major)}원`;
  }
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(major);
}

/** 기계가 읽는 소수 표기(구조화 데이터 · 결제 SDK 등). KRW 129000 → '129000', USD 8950 → '89.50' */
export function moneyToDecimalString({ amount, currency }: Money): string {
  const digits = fractionDigits(currency);
  return (amount / 10 ** digits).toFixed(digits);
}
