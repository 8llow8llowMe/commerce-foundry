import { describe, expect, it } from 'vitest';
import { loginNotice, PASSWORD_CHANGED_LOGIN } from './login-notice';

describe('loginNotice', () => {
  it('아는 사유만 문구로 바꾼다', () => {
    expect(loginNotice('password-changed')).toContain('새 비밀번호로');
    expect(loginNotice(['password-changed'])).toContain('새 비밀번호로');
    expect(loginNotice('toString')).toBeNull();
    expect(loginNotice('<script>')).toBeNull();
    expect(loginNotice(undefined)).toBeNull();
  });

  it('비밀번호를 바꾼 뒤 가는 주소에는 사유 이름만 있다', () => {
    expect(PASSWORD_CHANGED_LOGIN).toBe('/login?reason=password-changed');
  });
});
