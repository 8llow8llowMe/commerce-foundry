import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  CHANGE_FAILED,
  CHANGE_GONE,
  CHANGE_UNKNOWN,
  CHANGED_SESSION_LIVE,
  changeFailure,
  isAssignableRole,
} from './roles';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string,
) => new ApiError(kind, status, code, message, []);

describe('isAssignableRole', () => {
  it('OWNER · MANAGER · STAFF 만 줄 수 있다(이행용 ADMIN 은 아니다)', () => {
    expect(isAssignableRole('MANAGER')).toBe(true);
    expect(isAssignableRole('ADMIN')).toBe(false);
    expect(isAssignableRole('CUSTOMER')).toBe(false);
    expect(isAssignableRole('')).toBe(false);
  });
});

describe('changeFailure', () => {
  it('역할 나누기 꺼짐(AUTH_049)은 서버 문구와 함께 역할 고르기를 거두게 한다', () => {
    expect(changeFailure(apiError('conflict', 409, 'AUTH_049', '준비 중'))).toEqual({
      kind: 'row',
      message: '준비 중',
      reload: false,
      roleSplitOff: true,
    });
  });

  it('본인 · 대표 관리자 계정 · 마지막 대표 관리자는 서버 문구를 줄에 둔다 — 403 AUTH_046 도 화면 권한 안내로 바꾸지 않는다', () => {
    for (const [kind, status, code] of [
      ['validation', 400, 'AUTH_047'],
      ['forbidden', 403, 'AUTH_046'],
      ['conflict', 409, 'AUTH_048'],
      ['conflict', 409, 'AUTH_031'],
    ] as const) {
      expect(changeFailure(apiError(kind, status, code, `서버 ${code}`))).toEqual({
        kind: 'row',
        message: `서버 ${code}`,
        reload: false,
      });
    }
  });

  it('그 밖의 401 · 403 은 화면 상태다', () => {
    expect(changeFailure(apiError('forbidden', 403, 'SECURITY_002', 'x'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(changeFailure(apiError('unauthorized', 401, null, 'x'))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
  });

  it('없는 계정 · 컷오프 실패 · 결과 모름 · 연결 실패는 목록을 다시 읽는다', () => {
    expect(changeFailure(apiError('notFound', 404, 'AUTH_033', 'x'))).toEqual({
      kind: 'row',
      message: CHANGE_GONE,
      reload: true,
    });
    expect(changeFailure(apiError('unavailable', 503, 'AUTH_013', 'x'))).toEqual({
      kind: 'row',
      message: CHANGED_SESSION_LIVE,
      reload: true,
    });
    expect(changeFailure(apiError('unknownOutcome', 504, null, 'x'))).toEqual({
      kind: 'row',
      message: CHANGE_UNKNOWN,
      reload: true,
    });
    expect(changeFailure(new Error('network'))).toEqual({
      kind: 'row',
      message: CHANGE_FAILED,
      reload: true,
    });
  });

  it('코드가 있는 다른 4xx 는 서버 문구, 5xx 는 한 문구다', () => {
    expect(changeFailure(apiError('validation', 400, 'AUTH_160', '역할 값'))).toEqual({
      kind: 'row',
      message: '역할 값',
      reload: false,
    });
    expect(changeFailure(apiError('server', 500, 'COMMON_500', 'x'))).toEqual({
      kind: 'row',
      message: CHANGE_FAILED,
      reload: true,
    });
  });
});
