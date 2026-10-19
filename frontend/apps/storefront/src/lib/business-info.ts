/**
 * 푸터에 표시하는 사업자 정보(전자상거래법 표시 의무). 실값은 저장소에 두지 않고 서버 env `BUSINESS_*` 로 받는다
 * — 키 이름은 frontend/.env.example, 실값은 Vault · 배포 환경. 빈 값은 null 이고 화면은 그 줄을 뺀다.
 */
export type BusinessInfo = {
  name: string | null;
  representative: string | null;
  registrationNumber: string | null;
  mailOrderNumber: string | null;
  address: string | null;
  contact: string | null;
};

type EnvRecord = Record<string, string | undefined>;

const value = (env: EnvRecord, key: string) => {
  const trimmed = env[key]?.trim();
  return trimmed ? trimmed : null;
};

export function parseBusinessInfo(env: EnvRecord): BusinessInfo {
  return {
    name: value(env, 'BUSINESS_NAME'),
    representative: value(env, 'BUSINESS_REPRESENTATIVE'),
    registrationNumber: value(env, 'BUSINESS_REGISTRATION_NUMBER'),
    mailOrderNumber: value(env, 'BUSINESS_MAIL_ORDER_NUMBER'),
    address: value(env, 'BUSINESS_ADDRESS'),
    contact: value(env, 'BUSINESS_CONTACT'),
  };
}

/**
 * 공정거래위원회 통신판매사업자 정보 공개 페이지. 사업자등록번호(숫자 10자리)로 연다.
 * 번호 형식이 다르면 잘못된 링크를 내보내지 않고 null.
 */
export function businessVerificationUrl(registrationNumber: string | null): string | null {
  const digits = registrationNumber?.replace(/\D/g, '') ?? '';
  if (digits.length !== 10) return null;
  return `https://www.ftc.go.kr/bizCommPop.do?wrkr_no=${digits}`;
}
