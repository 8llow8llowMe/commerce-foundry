/**
 * 사업자 식별 번호의 국가별 처리 — 항목 이름 · 공개 확인 링크. 형식 검증은 서버도 하지 않는다(국가별 하드코딩 금지).
 * 규칙이 없는 국가는 일반 이름으로 번호만 보이고 링크를 만들지 않는다. 새 국가는 `REGISTRIES` 에 더한다.
 */
type Registry = {
  /** 사전 `footer.registrationLabels` 의 키 — 없으면 일반 이름(`footer.registrationNumber`) */
  labelKey: string;
  verifyUrl: (registrationNumber: string) => string | null;
};

const REGISTRIES: Record<string, Registry> = {
  // 공정거래위원회 통신판매사업자 정보 공개 페이지. 사업자등록번호(숫자 10자리)로 연다
  KR: {
    labelKey: 'KR',
    verifyUrl: (registrationNumber) => {
      const digits = registrationNumber.replace(/\D/g, '');
      return digits.length === 10 ? `https://www.ftc.go.kr/bizCommPop.do?wrkr_no=${digits}` : null;
    },
  },
};

/** 국가 규칙의 공개 확인 링크. 규칙이 없거나 번호 형식이 다르면 잘못된 링크를 내보내지 않고 null */
export function businessVerificationUrl(
  countryCode: string,
  registrationNumber: string,
): string | null {
  return REGISTRIES[countryCode]?.verifyUrl(registrationNumber) ?? null;
}

/** 국가에 맞는 등록번호 항목 이름. 사전에 국가 이름이 없으면 일반 이름 */
export function registrationLabel(
  labels: { registrationNumber: string; registrationLabels: Partial<Record<string, string>> },
  countryCode: string,
): string {
  const key = REGISTRIES[countryCode]?.labelKey;
  return (key && labels.registrationLabels[key]) || labels.registrationNumber;
}
