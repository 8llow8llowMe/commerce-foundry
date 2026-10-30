import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  formatOtpDate,
  formatOtpDeadline,
  formatOtpSecret,
  normalizeOtpCode,
  OTP_ACCOUNT_GONE,
  OTP_ALREADY_OFF,
  OTP_ALREADY_ON,
  OTP_BUSY,
  OTP_CHECK_INPUT,
  OTP_CODE_OR_RECOVERY,
  OTP_CURRENT_PASSWORD_REQUIRED,
  OTP_ENROLLMENT_EXPIRED,
  OTP_FAILED,
  OTP_SIX_DIGITS,
  OTP_UNREADABLE,
  OTP_WRONG_CODE,
  OTP_WRONG_CODE_OR_RECOVERY,
  OTP_WRONG_PASSWORD,
  otpFailure,
  otpRateLimitText,
  qrPath,
  reauthCodeProblem,
  recoveryCodesText,
  totpProblem,
} from './otp';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message = '서버 문구',
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

describe('otpFailure', () => {
  it('429 는 쿨다운 신호다(코드 무관)', () => {
    expect(otpFailure(apiError('rateLimited', 429, 'AUTH_043'), 'confirm')).toEqual({
      kind: 'rateLimited',
    });
  });

  it('AUTH_032 는 현재 비밀번호 칸, AUTH_035 는 코드 칸에 단계별 운영자 문구로 붙인다', () => {
    expect(otpFailure(apiError('server', 400, 'AUTH_032'), 'deactivate')).toEqual({
      kind: 'errors',
      errors: { currentPassword: [OTP_WRONG_PASSWORD] },
    });
    expect(otpFailure(apiError('server', 400, 'AUTH_035'), 'confirm')).toEqual({
      kind: 'errors',
      errors: { code: [OTP_WRONG_CODE] },
    });
    expect(otpFailure(apiError('server', 400, 'AUTH_035'), 'regenerate')).toEqual({
      kind: 'errors',
      errors: { code: [OTP_WRONG_CODE_OR_RECOVERY] },
    });
  });

  it('검증 fieldErrors 는 코드로 칸 · 문구를 찾고 모르는 코드는 폼 한 문구다(서버 문구를 보이지 않는다)', () => {
    expect(
      otpFailure(
        apiError('validation', 400, 'AUTH_100', '입력값', [
          {
            field: 'currentPassword',
            code: 'AUTH_145',
            message: 'currentPassword must not be blank',
          },
          { field: 'code', code: 'AUTH_149', message: 'code size' },
          { field: 'other', code: 'X', message: '?' },
        ]),
        'deactivate',
      ),
    ).toEqual({
      kind: 'errors',
      errors: {
        currentPassword: [OTP_CURRENT_PASSWORD_REQUIRED],
        code: [OTP_CODE_OR_RECOVERY],
        form: [OTP_CHECK_INPUT],
      },
    });
  });

  it('409 AUTH_044 는 같은 내용으로 다시 시도하라는 폼 문구다(단계를 접지 않는다)', () => {
    expect(otpFailure(apiError('conflict', 409, 'AUTH_044'), 'confirm')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_BUSY] },
    });
  });

  it('상태가 바뀐 실패는 단계를 접는다 — 036 · 037 은 다시 읽기, 038 은 처음부터', () => {
    expect(otpFailure(apiError('notFound', 404, 'AUTH_036'), 'regenerate')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_ALREADY_OFF] },
      next: 'reload',
    });
    expect(otpFailure(apiError('conflict', 409, 'AUTH_037'), 'start')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_ALREADY_ON] },
      next: 'reload',
    });
    expect(otpFailure(apiError('server', 400, 'AUTH_038'), 'confirm')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_ENROLLMENT_EXPIRED] },
      next: 'restart',
    });
  });

  it('AUTH_033 · AUTH_039 는 운영자 문구다', () => {
    expect(otpFailure(apiError('notFound', 404, 'AUTH_033'), 'start')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_ACCOUNT_GONE] },
    });
    expect(otpFailure(apiError('unavailable', 503, 'AUTH_039'), 'confirm')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_UNREADABLE] },
    });
  });

  it('결과를 모르면 쓰기 단계는 상태를 다시 읽고, 등록 시작은 다시 시작하게 한다', () => {
    const cause = () => apiError('unknownOutcome', 504, 'GATEWAY_004');
    for (const action of ['confirm', 'deactivate', 'regenerate'] as const) {
      const failure = otpFailure(cause(), action);
      expect(failure).toMatchObject({ kind: 'errors', next: 'reload' });
    }
    const start = otpFailure(cause(), 'start');
    expect(start.kind === 'errors' && start.next).toBeFalsy();
    expect(otpFailure(cause(), 'regenerate')).toMatchObject({
      errors: { form: [expect.stringContaining('복구 코드')] },
    });
  });

  it('401 은 입력을 지키는 폼 오류, 403 은 권한 안내, 연결 실패 · 그 밖은 한 문구다', () => {
    expect(otpFailure(apiError('unauthorized', 401, 'SECURITY_001'), 'confirm')).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(otpFailure(apiError('forbidden', 403, 'SECURITY_005'), 'confirm')).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(otpFailure(new Error('boom'), 'confirm')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_FAILED] },
    });
    expect(otpFailure(apiError('unavailable', 503, 'AUTH_013'), 'deactivate')).toEqual({
      kind: 'errors',
      errors: { form: [OTP_FAILED] },
    });
  });
});

describe('코드 입력', () => {
  it('공백을 지운다', () => {
    expect(normalizeOtpCode(' 123 456 ')).toBe('123456');
  });

  it('등록 확인은 숫자 6자리만 받는다', () => {
    expect(totpProblem('')).toBeNull();
    expect(totpProblem('123456')).toBeNull();
    expect(totpProblem('12345')).toBe(OTP_SIX_DIGITS);
    expect(totpProblem('abcde-fghij')).toBe(OTP_SIX_DIGITS);
  });

  it('해제 · 재발급은 6자리 또는 32자 이하 복구 코드를 받는다', () => {
    expect(reauthCodeProblem('')).toBeNull();
    expect(reauthCodeProblem('123456')).toBeNull();
    expect(reauthCodeProblem('abcde-fghij')).toBeNull();
    expect(reauthCodeProblem('1234567')).toBe(OTP_SIX_DIGITS);
    expect(reauthCodeProblem('a'.repeat(33))).toBe(OTP_CODE_OR_RECOVERY);
  });
});

describe('표시', () => {
  it('429 안내는 1분 이상이면 분으로 보인다', () => {
    expect(otpRateLimitText(840)).toContain('14분');
    expect(otpRateLimitText(5)).toContain('5초');
  });

  it('직접 입력 키는 4자씩 띄운다', () => {
    expect(formatOtpSecret('JBSWY3DPEHPK3PXP')).toBe('JBSW Y3DP EHPK 3PXP');
    expect(formatOtpSecret('')).toBe('');
  });

  it('복구 코드는 한 줄에 하나로 복사한다', () => {
    expect(recoveryCodesText(['aaaaa-bbbbb', 'ccccc-ddddd'])).toBe('aaaaa-bbbbb\nccccc-ddddd');
  });

  it('시각은 한국 시각으로, 읽을 수 없으면 null', () => {
    expect(formatOtpDate(null)).toBeNull();
    expect(formatOtpDate('not-a-date')).toBeNull();
    expect(formatOtpDate('2026-10-30T00:00:00Z')).toContain('2026');
    expect(formatOtpDeadline('2026-10-30T00:15:00Z')).toMatch(/9:15/);
    expect(formatOtpDeadline('x')).toBeNull();
  });

  it('QR 은 정사각 칸 그림이고 값이 다르면 그림도 다르다', () => {
    const uri = 'otpauth://totp/Shop:a%40b.test?secret=JBSWY3DPEHPK3PXP&issuer=Shop&digits=6';
    const a = qrPath(uri);
    expect(a.size).toBeGreaterThan(21);
    expect(a.d).toMatch(/^M\d+ \d+h1v1h-1z/);
    expect(qrPath(`${uri}&period=30`).d).not.toBe(a.d);
  });
});
