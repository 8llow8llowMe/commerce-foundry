import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { LOGIN_FAILED, LOGIN_STORE_UNAVAILABLE } from './login-errors';
import {
  OTP_CHALLENGE_EXPIRED,
  OTP_CODE_INVALID,
  OTP_CONFLICT,
  otpOutcome,
  otpSubmitLabel,
  readOtpChallenge,
} from './otp-login';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
  retryAfterSec: number | null = null,
) => new ApiError(kind, status, code, '서버 문구', fieldErrors, retryAfterSec);

describe('readOtpChallenge', () => {
  it('otpRequired=true 와 챌린지 · 수명이 있으면 만료 시각을 계산해 돌려준다', () => {
    expect(
      readOtpChallenge(
        { otpRequired: true, challengeToken: 'c-1', challengeExpiresIn: 300 },
        1_000,
      ),
    ).toEqual({ token: 'c-1', expiresAt: 301_000 });
  });

  it('OTP 미등록 성공(dataBody null)과 모양이 틀린 값은 챌린지가 아니다', () => {
    expect(readOtpChallenge(null, 0)).toBeNull();
    expect(readOtpChallenge({ otpRequired: false }, 0)).toBeNull();
    expect(readOtpChallenge({ otpRequired: true, challengeToken: '' }, 0)).toBeNull();
    expect(
      readOtpChallenge({ otpRequired: true, challengeToken: 'c', challengeExpiresIn: 0 }, 0),
    ).toBeNull();
  });
});

describe('otpOutcome', () => {
  it('AUTH_034(만료 · 이미 씀)는 1단계로 돌아간다', () => {
    expect(otpOutcome(apiError('server', 400, 'AUTH_034'))).toEqual({
      kind: 'restart',
      notice: OTP_CHALLENGE_EXPIRED,
    });
  });

  it('AUTH_044(쓰기 잠금 대기 초과)는 챌린지가 소비됐으니 1단계로 돌아간다', () => {
    expect(otpOutcome(apiError('conflict', 409, 'AUTH_044'))).toEqual({
      kind: 'restart',
      notice: OTP_CONFLICT,
    });
  });

  it('챌린지 칸 검증(AUTH_153)도 1단계로 돌아간다', () => {
    const cause = apiError('validation', 400, 'AUTH_153', [
      { field: 'challengeToken', code: 'AUTH_153', message: '필수' },
    ]);
    expect(otpOutcome(cause)).toMatchObject({ kind: 'restart' });
  });

  it('AUTH_035 는 같은 화면에서 코드 칸에 다시 입력 안내를 붙인다', () => {
    expect(otpOutcome(apiError('server', 400, 'AUTH_035'))).toEqual({
      kind: 'retry',
      code: [OTP_CODE_INVALID],
      form: [],
    });
  });

  it('코드 칸 검증(AUTH_149)은 서버 문구를 코드 칸에 붙인다', () => {
    const cause = apiError('validation', 400, 'AUTH_149', [
      { field: 'code', code: 'AUTH_149', message: '6자리 또는 복구 코드' },
    ]);
    expect(otpOutcome(cause)).toEqual({ kind: 'retry', code: ['6자리 또는 복구 코드'], form: [] });
  });

  it('429 AUTH_043 은 Retry-After 를 분으로 올려 안내한다', () => {
    expect(otpOutcome(apiError('rateLimited', 429, 'AUTH_043', [], 600))).toEqual({
      kind: 'retry',
      code: [],
      form: ['인증 코드 시도가 너무 많습니다. 10분 뒤에 다시 시도해 주세요.'],
    });
    expect(otpOutcome(apiError('rateLimited', 429, 'GATEWAY_006'))).toMatchObject({
      form: ['요청이 많습니다. 잠시 뒤에 다시 시도해 주세요.'],
    });
  });

  it('계수기 장애 · 연결 실패 · 그 밖은 같은 화면에서 다시 시도다', () => {
    expect(otpOutcome(apiError('unavailable', 503, 'AUTH_013'))).toEqual({
      kind: 'retry',
      code: [],
      form: [LOGIN_STORE_UNAVAILABLE],
    });
    expect(otpOutcome(apiError('unavailable', 503, 'AUTH_039'))).toMatchObject({
      kind: 'retry',
      form: [LOGIN_FAILED],
    });
    expect(otpOutcome(new TypeError('offline'))).toMatchObject({
      kind: 'retry',
      form: [LOGIN_FAILED],
    });
  });
});

describe('otpSubmitLabel', () => {
  it('보내는 중 · 남은 시간 · 기본 순서다', () => {
    expect(otpSubmitLabel(true, 30)).toBe('확인하는 중…');
    expect(otpSubmitLabel(false, 840)).toBe('14분 뒤에 다시 시도할 수 있습니다');
    expect(otpSubmitLabel(false, 0)).toBe('확인');
  });
});
