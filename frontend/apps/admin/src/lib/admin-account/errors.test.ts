import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  changePasswordFailure,
  ALREADY_OFF,
  CREATE_UNKNOWN,
  createAdminFailure,
  DEACTIVATE_UNKNOWN,
  OFF_BUT_SESSION_LIVE,
  PASSWORD_UNKNOWN,
  DEACTIVATE_FAILED,
  deactivateFailure,
  passwordProblem,
} from './errors';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

describe('passwordProblem', () => {
  it('BE 규칙(12~64자 · 영문과 숫자 · 공백 없는 ASCII)을 따른다', () => {
    expect(passwordProblem('shop-staff-2026')).toBeNull();
    expect(passwordProblem('')).toBeNull();
    expect(passwordProblem('short1a')).toContain('12자 이상');
    expect(passwordProblem(`a1${'x'.repeat(63)}`)).toContain('64자 이하');
    expect(passwordProblem('onlyletterssss')).toContain('영문과 숫자');
    expect(passwordProblem('123456789012')).toContain('영문과 숫자');
    expect(passwordProblem('has space 2026')).toContain('공백 없이');
    expect(passwordProblem('한글비밀번호abc123')).toContain('공백 없이');
  });

  it('문구에 입력값을 넣지 않는다', () => {
    expect(passwordProblem('secret-1')).not.toContain('secret-1');
  });
});

describe('createAdminFailure', () => {
  it('fieldErrors 는 칸에, 모르는 칸은 폼 전체 한 문구로 둔다', () => {
    const failure = createAdminFailure(
      apiError('validation', 400, 'AUTH_100', '입력값', [
        { field: 'email', code: 'AUTH_140', message: '이메일 형식이 올바르지 않습니다.' },
        { field: 'name', code: 'AUTH_143', message: '이름은 필수입니다.' },
        { field: 'request', code: 'X', message: '?' },
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        email: ['이메일 형식이 올바르지 않습니다.'],
        name: ['이름은 필수입니다.'],
        form: [SAVE_FAILED],
      },
    });
  });

  it('409 AUTH_002 는 이메일 칸에 서버 문구로 붙인다', () => {
    expect(
      createAdminFailure(apiError('conflict', 409, 'AUTH_002', '이미 가입된 이메일입니다.')),
    ).toEqual({ kind: 'errors', errors: { email: ['이미 가입된 이메일입니다.'] } });
  });

  it('401 은 입력을 지키는 폼 오류, 403 은 권한 안내다', () => {
    expect(createAdminFailure(apiError('unauthorized', 401, 'SECURITY_001', 'x'))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(createAdminFailure(apiError('forbidden', 403, 'SECURITY_005', 'x'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('연결 실패 · 5xx 는 한 문구다', () => {
    expect(createAdminFailure(new Error('boom'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(createAdminFailure(apiError('unavailable', 503, 'AUTH_013', '장애'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('결과를 모르는 실패(unknownOutcome)', () => {
  it('다시 보내기 전에 결과를 확인하게 한다', () => {
    const cause = () => apiError('unknownOutcome', 504, 'GATEWAY_004', '시간 초과');
    expect(createAdminFailure(cause())).toEqual({
      kind: 'errors',
      errors: { form: [CREATE_UNKNOWN] },
      uncertain: true,
    });
    expect(changePasswordFailure(cause())).toEqual({
      kind: 'errors',
      errors: { form: [PASSWORD_UNKNOWN] },
      uncertain: true,
    });
    expect(deactivateFailure(cause())).toEqual({
      kind: 'row',
      message: DEACTIVATE_UNKNOWN,
      reload: true,
    });
  });
});

describe('changePasswordFailure', () => {
  it('AUTH_032 는 현재 비밀번호 칸에, 새 비밀번호 검증은 그 칸에 붙인다', () => {
    expect(
      changePasswordFailure(
        apiError('server', 400, 'AUTH_032', '현재 비밀번호가 올바르지 않습니다.'),
      ),
    ).toEqual({
      kind: 'errors',
      errors: { currentPassword: ['현재 비밀번호가 올바르지 않습니다.'] },
    });
    expect(
      changePasswordFailure(
        apiError('validation', 400, 'AUTH_100', '입력값', [
          {
            field: 'newPassword',
            code: 'AUTH_141',
            message: '비밀번호는 12자 이상 64자 이하여야 합니다.',
          },
          { field: 'currentPassword', code: 'AUTH_145', message: '현재 비밀번호는 필수입니다.' },
        ]),
      ),
    ).toEqual({
      kind: 'errors',
      errors: {
        newPassword: ['비밀번호는 12자 이상 64자 이하여야 합니다.'],
        currentPassword: ['현재 비밀번호는 필수입니다.'],
      },
    });
  });

  it('503 AUTH_013 은 바뀐 것이다(BE 가 저장한 뒤 컷오프에서 실패) — 성공 경로로 보낸다', () => {
    expect(changePasswordFailure(apiError('unavailable', 503, 'AUTH_013', '잠시 후'))).toEqual({
      kind: 'changed',
    });
  });
});

describe('deactivateFailure', () => {
  it('마지막 관리자(AUTH_031)는 서버 문구를 줄에 두고 다시 읽지 않는다', () => {
    expect(
      deactivateFailure(
        apiError('conflict', 409, 'AUTH_031', '마지막 관리자는 비활성화할 수 없습니다.'),
      ),
    ).toEqual({ kind: 'row', message: '마지막 관리자는 비활성화할 수 없습니다.', reload: false });
  });

  it('AUTH_033(이미 꺼짐) · AUTH_013(끈 뒤 컷오프 실패)은 꺼진 것으로 표시한다', () => {
    expect(
      deactivateFailure(apiError('notFound', 404, 'AUTH_033', '관리자 계정을 찾을 수 없습니다.')),
    ).toEqual({ kind: 'row', message: ALREADY_OFF, reload: false, off: true });
    // 서버 문구("로그인을 처리할 수 없습니다")가 아니라 기존 로그인이 남는다는 화면 문구
    expect(
      deactivateFailure(
        apiError('unavailable', 503, 'AUTH_013', '일시적으로 로그인을 처리할 수 없습니다.'),
      ),
    ).toEqual({ kind: 'row', message: OFF_BUT_SESSION_LIVE, reload: false, off: true });
  });

  it('연결 실패는 목록을 다시 읽는다', () => {
    expect(deactivateFailure(new Error('boom'))).toEqual({
      kind: 'row',
      message: DEACTIVATE_FAILED,
      reload: true,
    });
  });

  it('401 · 403 은 화면 상태다', () => {
    expect(deactivateFailure(apiError('unauthorized', 401, 'SECURITY_001', 'x'))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
  });
});
