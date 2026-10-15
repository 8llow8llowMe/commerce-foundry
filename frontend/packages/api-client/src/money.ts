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

export function formatMoney({ amount, currency }: Money): string {
  if (!Number.isSafeInteger(amount)) {
    if (process.env.NODE_ENV !== 'production') {
      throw new Error(`amount 는 최소 화폐 단위 정수여야 한다: ${amount} ${currency}`);
    }
  }
  const major = amount / 10 ** fractionDigits(currency);

  if (currency === 'KRW') {
    // 국내 쇼핑몰 관례: ₩129,000 이 아니라 129,000원
    return `${new Intl.NumberFormat('ko-KR').format(major)}원`;
  }
  return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(major);
}
