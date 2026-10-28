/** 가입 약관 동의 — 일반 가입(`SignUpForm`)과 소셜 신규 가입이 같은 모양으로 보낸다. 세 칸 모두 보낸다(마케팅도 빠지면 AUTH_113) */
export type SignUpAgreements = {
  termsOfService: boolean;
  privacyPolicy: boolean;
  marketing: boolean;
};

export const NO_AGREEMENTS: SignUpAgreements = {
  termsOfService: false,
  privacyPolicy: false,
  marketing: false,
};

/** 필수 두 칸(이용약관 · 개인정보)에 동의했는가 */
export function hasRequiredAgreements(value: SignUpAgreements): boolean {
  return value.termsOfService && value.privacyPolicy;
}

/** 저장소 · 응답에서 읽은 값이 동의 모양이면 그대로, 아니면 null */
export function readAgreements(value: unknown): SignUpAgreements | null {
  if (typeof value !== 'object' || value === null) return null;
  const { termsOfService, privacyPolicy, marketing } = value as Record<string, unknown>;
  if (
    typeof termsOfService !== 'boolean' ||
    typeof privacyPolicy !== 'boolean' ||
    typeof marketing !== 'boolean'
  ) {
    return null;
  }
  return { termsOfService, privacyPolicy, marketing };
}
