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

/**
 * 어드민 입력칸의 금액 글자를 최소 단위 정수로. 계산이 아니라 표기 변환이다(KRW '3,000' → 3000, USD '12.5' → 1250).
 * 앞뒤 공백은 무시하고, 쉼표는 세 자리 묶음 자리에 있을 때만 받는다('12,50' 을 1250 으로 읽지 않게).
 * 빈 값은 null, 숫자가 아니거나 · 음수거나 · 통화의 소수 자릿수를 넘으면 NaN 이다 — 화면이 "금액 형식" 오류로 막는다(서버에 보내지 않는다).
 */
export function parseMoneyInput(text: string, currency: Currency): number | null {
  const raw = text.trim();
  if (raw === '') return null;
  if (raw.includes(',') && !/^\d{1,3}(,\d{3})+(\.\d*)?$/.test(raw)) return Number.NaN;
  const trimmed = raw.replace(/,/g, '');
  const digits = fractionDigits(currency);
  const pattern = digits === 0 ? /^\d+$/ : new RegExp(`^\\d+(\\.\\d{1,${digits}})?$`);
  if (!pattern.test(trimmed)) return Number.NaN;
  const [whole = '0', fraction = ''] = trimmed.split('.');
  const minor = Number(whole + fraction.padEnd(digits, '0'));
  return Number.isSafeInteger(minor) ? minor : Number.NaN;
}
