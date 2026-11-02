import { ApiError } from '@cf/api-client';
import { accessDeniedOf, SESSION_EXPIRED_ON_SAVE, type FormErrors } from '@/lib/api-errors';
import { otpFailure, reauthCodeProblem, type OtpFailure } from './otp';
import { ADMIN_ACCOUNTS_PATH } from './query';

/**
 * 동료 관리자 OTP 복구(#428 · BE #238 PR-C). 정본은 `backend/docs/api-design-guide.md` "동료 관리자 OTP 복구" ·
 * `backend/docs/secret-management.md` "OTP 긴급 복구". 행위자는 토큰의 관리자이고, 행위자 본인의 현재 비밀번호 + 코드로 재인증한다.
 * 응답의 새 복구 코드 8개는 화면 상태에만 두고 한 번만 보인다 — 브라우저 저장소 · 주소 · 로그 · 분석 이벤트에 남기지 않는다.
 */
export function peerOtpRecoveryPath(memberId: string): string {
  return `${ADMIN_ACCOUNTS_PATH}/${encodeURIComponent(memberId)}/otp/recovery`;
}

/**
 * 이 줄에 복구 버튼을 둘지 — 내 계정이 아니고, 이번 방문에 끄지 않았고, 서버가 복구할 수 없다고(`AUTH_033`) 하지 않은 관리자만.
 * 내 아이디를 모르면(`null`) 어느 줄이 나인지 모르니 두지 않는다. 고객은 목록(`role=ADMIN`)에 오지 않는다.
 */
export function canRecoverPeer(
  memberId: string,
  selfMemberId: string | null,
  excluded: ReadonlySet<string>,
): boolean {
  return selfMemberId !== null && memberId !== selfMemberId && !excluded.has(memberId);
}

/** 대상에게 전달할 최소 코드 수 — 하나로 로그인하고 다른 하나로 기존 OTP 를 해제한다(첫 로그인에 쓴 코드는 다시 못 쓴다) */
export const PEER_MIN_CODES_TO_SEND = 2;

/** 404 `AUTH_033` — 대상이 없거나 비활성 관리자다(고객도 같다). 행위자 계정이 사라진 경우도 같은 코드라 둘 다 적는다 */
export const PEER_TARGET_GONE =
  '이 관리자 계정을 찾을 수 없거나 비활성화된 계정이라 복구할 수 없습니다. 내 계정이 비활성화됐다면 다시 로그인해 주세요.';
/** 404 `AUTH_036` — 행위자 또는 대상이 OTP 미등록이다. 응답은 어느 쪽인지 나누지 않는다 */
export const PEER_NOT_ENROLLED =
  '나와 이 관리자 모두 2단계 인증을 쓰고 있어야 복구할 수 있습니다. 내 2단계 인증을 켜지 않았다면 먼저 등록해 주세요. 이 관리자가 2단계 인증을 쓰지 않는다면 복구 없이 비밀번호로 로그인할 수 있습니다.';
/** 400 `AUTH_045` — 화면은 내 줄에 버튼을 두지 않지만 다른 탭 · 계정 전환으로 올 수 있다 */
export const PEER_SELF = '내 2단계 인증은 계정 메뉴의 "2단계 인증" 에서 관리해 주세요.';
/**
 * 503 · 결과를 모르는 실패. DB 교체(대상 OTP · 복구 코드 폐기, 내 코드 소비)는 끝났는데 기존 로그인 끊기(Redis)만 실패했을 수 있다.
 * 이번 요청의 코드는 받지 못했으니 새 코드(다음 30초 인증 코드 또는 쓰지 않은 복구 코드)로 다시 복구하고, 마지막으로 성공한 화면의 코드만 전달한다.
 */
export const PEER_UNCERTAIN =
  '복구를 끝까지 확인하지 못했습니다. 이 관리자의 기존 2단계 인증 · 복구 코드가 이미 무효화됐을 수 있고, 기존 로그인이 아직 끊기지 않았을 수 있습니다. 이번에는 새 복구 코드를 받지 못했으니, 인증 앱의 다음 코드나 쓰지 않은 복구 코드로 다시 복구해 주세요. 대상에게는 마지막으로 성공한 화면의 코드만 전달합니다.';

export type PeerRecoveryFailure =
  | Extract<OtpFailure, { kind: 'access' | 'rateLimited' }>
  /**
   * 폼 오류. `gone` 이면 대상이 복구할 수 없는 계정이라 폼을 접고 줄 버튼을 거둔다.
   * `codeSpent` 면 보낸 코드가 이미 쓰였을 수 있어 코드 칸을 비운다(같은 TOTP · 복구 코드는 다시 쓸 수 없다).
   */
  | { kind: 'errors'; errors: FormErrors; gone?: true; codeSpent?: true };

const UNCERTAIN_KINDS: ReadonlySet<ApiError['kind']> = new Set([
  'unknownOutcome',
  'network',
  'invalidResponse',
]);

/** 결과를 모르는 실패 — 성공 응답의 모양이 틀렸을 때도 쓴다(교체는 끝났을 수 있다) */
export const PEER_UNCERTAIN_FAILURE: Extract<PeerRecoveryFailure, { kind: 'errors' }> = {
  kind: 'errors',
  errors: { form: [PEER_UNCERTAIN] },
  codeSpent: true,
};

export const PEER_ACKNOWLEDGE_REQUIRED = '무효화되는 내용을 확인했다고 체크해 주세요.';

/**
 * 보내기 전 확인 — 무효화 확인 체크와 코드 모양(6자리 또는 복구 코드 32자 이하). 문제가 있으면 칸 오류이고 요청을 보내지 않는다.
 * 빈 칸은 칸의 `required` 와 서버 검증(정본)에 맡긴다.
 */
export function peerSubmitProblem(acknowledged: boolean, code: string): FormErrors | null {
  if (!acknowledged) return { acknowledge: [PEER_ACKNOWLEDGE_REQUIRED] };
  const problem = reauthCodeProblem(code);
  return problem ? { code: [problem] } : null;
}

/** 성공 응답의 새 복구 코드. 글자 배열이 아니면(계약 불일치) null — 화면은 결과를 모르는 실패로 다룬다 */
export function recoveredCodes(result: unknown): string[] | null {
  const codes = (result as { recoveryCodes?: unknown } | null)?.recoveryCodes;
  return Array.isArray(codes) &&
    codes.length > 0 &&
    codes.every((code): code is string => typeof code === 'string')
    ? codes
    : null;
}

const form = (message: string): PeerRecoveryFailure => ({
  kind: 'errors',
  errors: { form: [message] },
});

/**
 * 동료 복구 실패 → 화면 처리. 재인증 칸 오류(`AUTH_032` · `035` · 검증) · `AUTH_044` · `AUTH_039` 는 본인 OTP 와 같은 문구(`otpFailure`)를 쓰고,
 * 대상 · 미등록 · 본인 대상 · 결과를 모르는 실패는 동료 복구 문구로 바꾼다.
 */
export function peerRecoveryFailure(cause: unknown): PeerRecoveryFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'forbidden') return { kind: 'access', denied };
  if (denied === 'unauthorized') return form(SESSION_EXPIRED_ON_SAVE);
  if (cause instanceof ApiError) {
    // 503 `AUTH_013`(컷오프 · 시도 계수 저장소 장애) · 500 · 502 · 504 · 연결 끊김 · 봉투 아닌 응답은 교체가 끝났는지 모른다
    // (BE 는 DB 교체 커밋 뒤 감사 · 컷오프를 한다 — 그 뒤 예외는 교체가 끝난 5xx 다). `AUTH_013` 은 교체 전 시도 계수기 장애일 수도 있지만
    // 나누지 못하므로 보수적으로 같이 둔다. `AUTH_039` 는 교체 전 실패라 아래 공통 문구다.
    // 게이트웨이에 닿지 못한 `BFF_002` 도 같이 둔다 — 다시 복구하면 마지막 성공만 남는다.
    if (
      UNCERTAIN_KINDS.has(cause.kind) ||
      (cause.kind === 'unavailable' && cause.code !== 'AUTH_039') ||
      (cause.kind === 'server' && (cause.status ?? 0) >= 500)
    ) {
      return PEER_UNCERTAIN_FAILURE;
    }
    if (cause.fieldErrors.length === 0) {
      switch (cause.code) {
        case 'AUTH_033':
          return { kind: 'errors', errors: { form: [PEER_TARGET_GONE] }, gone: true };
        case 'AUTH_036':
          return form(PEER_NOT_ENROLLED);
        case 'AUTH_045':
          return form(PEER_SELF);
      }
    }
  }
  const common = otpFailure(cause, 'deactivate');
  if (common.kind !== 'errors') return common;
  return { kind: 'errors', errors: common.errors };
}
