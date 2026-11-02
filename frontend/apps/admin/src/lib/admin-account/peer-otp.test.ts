import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  OTP_BUSY,
  OTP_CODE_OR_RECOVERY,
  OTP_FAILED,
  OTP_SIX_DIGITS,
  OTP_UNREADABLE,
  OTP_WRONG_CODE_OR_RECOVERY,
  OTP_WRONG_PASSWORD,
} from './otp';
import {
  canRecoverPeer,
  PEER_ACKNOWLEDGE_REQUIRED,
  PEER_NOT_ENROLLED,
  PEER_SELF,
  PEER_TARGET_GONE,
  PEER_UNCERTAIN,
  peerOtpRecoveryPath,
  peerRecoveryFailure,
  peerSubmitProblem,
  recoveredCodes,
} from './peer-otp';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, '서버 문구', fieldErrors);

describe('peerOtpRecoveryPath', () => {
  it('대상 아이디를 글자 그대로(인코딩해) 경로에 넣는다', () => {
    expect(peerOtpRecoveryPath('312345678901234567')).toBe(
      '/api/v1/admin/admins/312345678901234567/otp/recovery',
    );
    expect(peerOtpRecoveryPath('1/../2')).toBe('/api/v1/admin/admins/1%2F..%2F2/otp/recovery');
  });
});

describe('canRecoverPeer', () => {
  const none = new Set<string>();

  it('내 계정이 아닌 관리자에만 둔다', () => {
    expect(canRecoverPeer('2', '1', none)).toBe(true);
    expect(canRecoverPeer('1', '1', none)).toBe(false);
  });

  it('내 아이디를 모르면 어느 줄에도 두지 않는다', () => {
    expect(canRecoverPeer('2', null, none)).toBe(false);
  });

  it('비활성화했거나 서버가 복구할 수 없다고 한 계정에는 두지 않는다', () => {
    expect(canRecoverPeer('2', '1', new Set(['2']))).toBe(false);
  });
});

describe('peerRecoveryFailure', () => {
  it('403 은 권한 안내, 401 은 입력을 지키는 폼 오류다', () => {
    expect(peerRecoveryFailure(apiError('forbidden', 403, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(peerRecoveryFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
  });

  it('429 AUTH_043 은 쿨다운 신호다', () => {
    expect(peerRecoveryFailure(apiError('rateLimited', 429, 'AUTH_043'))).toEqual({
      kind: 'rateLimited',
    });
  });

  it('재인증 실패는 내 비밀번호 칸(AUTH_032) · 코드 칸(AUTH_035)에 붙인다', () => {
    expect(peerRecoveryFailure(apiError('server', 400, 'AUTH_032'))).toEqual({
      kind: 'errors',
      errors: { currentPassword: [OTP_WRONG_PASSWORD] },
    });
    expect(peerRecoveryFailure(apiError('server', 400, 'AUTH_035'))).toEqual({
      kind: 'errors',
      errors: { code: [OTP_WRONG_CODE_OR_RECOVERY] },
    });
  });

  it('검증 오류는 칸별 운영자 문구다', () => {
    expect(
      peerRecoveryFailure(
        apiError('validation', 400, 'AUTH_100', [
          { field: 'code', code: 'AUTH_149', message: 'pattern' },
        ]),
      ),
    ).toEqual({ kind: 'errors', errors: { code: [OTP_CODE_OR_RECOVERY] } });
  });

  it('AUTH_033 은 대상을 복구할 수 없다는 뜻이라 폼을 접는다(gone)', () => {
    expect(peerRecoveryFailure(apiError('notFound', 404, 'AUTH_033'))).toEqual({
      kind: 'errors',
      errors: { form: [PEER_TARGET_GONE] },
      gone: true,
    });
  });

  it('AUTH_036 · AUTH_045 는 동료 복구 문구, AUTH_044 는 같은 내용으로 다시 시도 안내다', () => {
    expect(peerRecoveryFailure(apiError('notFound', 404, 'AUTH_036'))).toEqual({
      kind: 'errors',
      errors: { form: [PEER_NOT_ENROLLED] },
    });
    expect(peerRecoveryFailure(apiError('server', 400, 'AUTH_045'))).toEqual({
      kind: 'errors',
      errors: { form: [PEER_SELF] },
    });
    expect(peerRecoveryFailure(apiError('conflict', 409, 'AUTH_044'))).toEqual({
      kind: 'errors',
      errors: { form: [OTP_BUSY] },
    });
  });

  it('503 컷오프 실패 · 결과를 모르는 실패는 교체됐을 수 있다고 알리고 코드 칸을 비운다(codeSpent)', () => {
    const uncertain = { kind: 'errors', errors: { form: [PEER_UNCERTAIN] }, codeSpent: true };
    expect(peerRecoveryFailure(apiError('unavailable', 503, 'AUTH_013'))).toEqual(uncertain);
    expect(peerRecoveryFailure(apiError('unavailable', 503, 'BFF_002'))).toEqual(uncertain);
    expect(peerRecoveryFailure(apiError('unknownOutcome', 504, null))).toEqual(uncertain);
    expect(peerRecoveryFailure(apiError('network', null, null))).toEqual(uncertain);
    expect(peerRecoveryFailure(apiError('invalidResponse', 200, null))).toEqual(uncertain);
    // DB 교체 커밋 뒤 감사 · 컷오프에서 난 500 도 교체가 끝났을 수 있다
    expect(peerRecoveryFailure(apiError('server', 500, null))).toEqual(uncertain);
    // 기존 응답 코드가 유효하다고 가정하지 않게 — 다시 복구하고 마지막 성공 코드만 전한다
    expect(PEER_UNCERTAIN).toContain('새 복구 코드를 받지 못했으니');
    expect(PEER_UNCERTAIN).toContain('마지막으로 성공한');
  });

  it('503 AUTH_039 는 교체 전 실패라 시스템 담당자 안내다', () => {
    expect(peerRecoveryFailure(apiError('unavailable', 503, 'AUTH_039'))).toEqual({
      kind: 'errors',
      errors: { form: [OTP_UNREADABLE] },
    });
  });

  it('ApiError 가 아닌 실패 · 모르는 코드는 한 문구다', () => {
    expect(peerRecoveryFailure(new Error('x'))).toEqual({
      kind: 'errors',
      errors: { form: [OTP_FAILED] },
    });
    expect(peerRecoveryFailure(apiError('server', 400, 'AUTH_999'))).toEqual({
      kind: 'errors',
      errors: { form: [OTP_FAILED] },
    });
  });
});

describe('peerSubmitProblem', () => {
  it('무효화 확인 체크 없이는 보내지 않는다(재인증 칸보다 먼저)', () => {
    expect(peerSubmitProblem(false, '123456')).toEqual({
      acknowledge: [PEER_ACKNOWLEDGE_REQUIRED],
    });
  });

  it('코드 모양이 틀리면 코드 칸 오류이고 보내지 않는다', () => {
    expect(peerSubmitProblem(true, '12345')).toEqual({ code: [OTP_SIX_DIGITS] });
    expect(peerSubmitProblem(true, 'x'.repeat(33))).toEqual({ code: [OTP_CODE_OR_RECOVERY] });
  });

  it('체크하고 6자리 또는 복구 코드면 보낸다', () => {
    expect(peerSubmitProblem(true, '123456')).toBeNull();
    expect(peerSubmitProblem(true, 'abcde-fgh23')).toBeNull();
  });
});

describe('recoveredCodes', () => {
  it('글자 배열이면 그대로 돌려준다', () => {
    const codes = ['aaaaa-bbbbb', 'ccccc-ddddd'];
    expect(recoveredCodes({ recoveryCodes: codes })).toEqual(codes);
  });

  it('모양이 틀린 성공 응답은 null 이다(결과를 모르는 실패로 다룬다)', () => {
    expect(recoveredCodes(null)).toBeNull();
    expect(recoveredCodes({})).toBeNull();
    expect(recoveredCodes({ recoveryCodes: [] })).toBeNull();
    expect(recoveredCodes({ recoveryCodes: 'aaaaa-bbbbb' })).toBeNull();
    expect(recoveredCodes({ recoveryCodes: [1, 2] })).toBeNull();
  });
});
