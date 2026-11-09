import { ApiError, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED } from '@/lib/api-errors';
import {
  passwordResetLinkPath,
  RESET_LINK_FAILED,
  RESET_LINK_SPENT,
  RESET_LINK_UNKNOWN,
  RESET_UNKNOWN,
  resetFailure,
  resetLinkFailure,
  resetLinkRequested,
  tokenFromHash,
} from './password-reset';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, message, fieldErrors);

// 서버 토큰과 같은 모양(base64url 43자)의 자리 값
const TOKEN = 'A'.repeat(20) + '_-' + 'b9'.repeat(10) + 'z';

describe('passwordResetLinkPath', () => {
  it('아이디를 문자열 그대로 경로에 넣는다(Snowflake 자릿수 보존)', () => {
    expect(passwordResetLinkPath('900719925474099312')).toBe(
      '/api/v1/admin/admins/900719925474099312/password-reset-link',
    );
    expect(passwordResetLinkPath('1/2')).toBe('/api/v1/admin/admins/1%2F2/password-reset-link');
  });
});

describe('tokenFromHash', () => {
  it('#token= 의 base64url 43자만 받는다', () => {
    expect(TOKEN).toHaveLength(43);
    expect(tokenFromHash(`#token=${TOKEN}`)).toBe(TOKEN);
    expect(tokenFromHash(`token=${TOKEN}`)).toBe(TOKEN);
  });

  it('없거나 모양이 다르면 null 이다', () => {
    expect(tokenFromHash('')).toBeNull();
    expect(tokenFromHash('#')).toBeNull();
    expect(tokenFromHash('#token=')).toBeNull();
    expect(tokenFromHash(`#token=${TOKEN}x`)).toBeNull();
    expect(tokenFromHash(`#token=${TOKEN.slice(1)}=`)).toBeNull();
    expect(tokenFromHash(`#other=${TOKEN}`)).toBeNull();
  });
});

describe('resetLinkRequested', () => {
  it('발송됐다고 단정하지 않는다', () => {
    const text = resetLinkRequested('김운영');
    expect(text).toContain('김운영');
    expect(text).toContain('요청했습니다');
    expect(text).not.toContain('보냈습니다');
  });
});

describe('resetLinkFailure', () => {
  it('401 · 403 은 권한 상태다', () => {
    expect(resetLinkFailure(apiError('forbidden', 403, 'AUTH_046', '권한 없음'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(resetLinkFailure(apiError('unauthorized', 401, null, '만료'))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
  });

  it('시도 초과 · 기능 꺼짐은 서버 문구다', () => {
    for (const code of ['AUTH_062', 'AUTH_063']) {
      expect(resetLinkFailure(apiError('unavailable', 429, code, `서버 ${code}`))).toEqual({
        kind: 'row',
        message: `서버 ${code}`,
      });
    }
  });

  it('결과를 모르면 메일을 확인하게 하고, 그 밖은 한 문구다', () => {
    expect(resetLinkFailure(apiError('unknownOutcome', 504, null, 'x'))).toEqual({
      kind: 'row',
      message: RESET_LINK_UNKNOWN,
    });
    expect(resetLinkFailure(apiError('unavailable', 503, 'AUTH_013', 'redis'))).toEqual({
      kind: 'row',
      message: RESET_LINK_FAILED,
    });
    expect(resetLinkFailure(new Error('network'))).toEqual({
      kind: 'row',
      message: RESET_LINK_FAILED,
    });
  });
});

describe('resetFailure', () => {
  it('만료 · 위조 · 사용됨(AUTH_061) · 경합(AUTH_064)은 이 링크를 더 쓰지 않는다', () => {
    for (const code of ['AUTH_061', 'AUTH_064']) {
      expect(resetFailure(apiError('validation', 400, code, '서버 문구'))).toEqual({
        kind: 'spent',
        message: RESET_LINK_SPENT,
      });
    }
  });

  it('결과를 모르면 같은 링크로 다시 보내지 않는다', () => {
    expect(resetFailure(apiError('unknownOutcome', 502, null, 'x'))).toEqual({
      kind: 'spent',
      message: RESET_UNKNOWN,
    });
  });

  it('비밀번호 검증 오류는 칸에, 모르는 칸은 폼 한 문구로', () => {
    expect(
      resetFailure(
        apiError('validation', 400, 'COMMON_001', '입력 오류', [
          { field: 'newPassword', code: 'AUTH_150', message: '10자 이상' },
          { field: 'token', code: 'AUTH_161', message: '토큰 필요' },
        ]),
      ),
    ).toEqual({ kind: 'errors', errors: { newPassword: ['10자 이상'], form: [SAVE_FAILED] } });
  });

  it('토큰 확인 전 거절(시도 초과 · 꺼짐 · 저장소 장애)은 서버 문구로 다시 보낼 수 있게 둔다', () => {
    for (const code of ['AUTH_062', 'AUTH_063', 'AUTH_013']) {
      expect(resetFailure(apiError('unavailable', 503, code, `서버 ${code}`))).toEqual({
        kind: 'errors',
        errors: { form: [`서버 ${code}`] },
      });
    }
    expect(resetFailure(new Error('network'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});
