import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { LOGIN_FAILED, loginErrors } from './login-errors';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number,
  code: string | null,
  message: string,
  fieldErrors: FieldErrorItem[] = [],
  retryAfterSec: number | null = null,
) => new ApiError(kind, status, code, message, fieldErrors, retryAfterSec);

describe('loginErrors', () => {
  it('AUTH_030 은 칸을 짚지 않고 서버 문구 하나를 폼 전체 오류로 둔다', () => {
    const errors = loginErrors(
      apiError('validation', 400, 'AUTH_030', '이메일 또는 비밀번호가 올바르지 않습니다.'),
    );
    expect(errors).toEqual({
      form: ['이메일 또는 비밀번호가 올바르지 않습니다.'],
      email: [],
      password: [],
    });
  });

  it('필드 검증은 그 칸에, 모르는 칸은 폼 전체 한 문구로 둔다', () => {
    const errors = loginErrors(
      apiError('validation', 400, 'AUTH_100', '입력값을 확인해 주세요.', [
        { field: 'email', code: 'AUTH_130', message: '이메일은 필수입니다.' },
        { field: 'password', code: 'AUTH_132', message: '비밀번호는 필수입니다.' },
        { field: 'request', code: 'X', message: '?' },
      ]),
    );
    expect(errors).toEqual({
      email: ['이메일은 필수입니다.'],
      password: ['비밀번호는 필수입니다.'],
      form: [LOGIN_FAILED],
    });
  });

  it('요청 제한은 남은 시간을 안내한다', () => {
    expect(loginErrors(apiError('rateLimited', 429, 'GATEWAY_006', '많음', [], 30)).form).toEqual([
      '요청이 많습니다. 30초 뒤에 다시 시도해 주세요.',
    ]);
    expect(loginErrors(apiError('rateLimited', 429, 'GATEWAY_006', '많음')).form).toEqual([
      '요청이 많습니다. 잠시 뒤에 다시 시도해 주세요.',
    ]);
  });

  it('연결 실패 · 예상 밖 오류는 한 문구다', () => {
    expect(loginErrors(apiError('unavailable', 503, 'BFF_002', '연결 실패')).form).toEqual([
      LOGIN_FAILED,
    ]);
    expect(loginErrors(new Error('boom')).form).toEqual([LOGIN_FAILED]);
  });
});
