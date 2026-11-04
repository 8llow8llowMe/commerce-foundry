import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import { CHECK_FIELDS, storeProfileSaveFailure } from './errors';
import { VERSION_CONFLICT } from './types';

describe('storeProfileSaveFailure', () => {
  it('409 STORE_PROFILE_020 은 충돌, 502 · 504 는 결과 모름', () => {
    expect(storeProfileSaveFailure(new ApiError('conflict', 409, VERSION_CONFLICT, null))).toEqual({
      kind: 'conflict',
    });
    expect(storeProfileSaveFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'unknownOutcome',
    });
  });

  it('401 은 폼을 지키고 다시 로그인 안내, 403 은 권한 상태', () => {
    expect(storeProfileSaveFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(storeProfileSaveFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('칸 오류는 그 칸에, 모르는 칸은 폼 전체에 붙이고 요약을 더한다', () => {
    const cause = new ApiError('validation', 400, 'STORE_PROFILE_101', null, [
      { code: 'STORE_PROFILE_101', field: 'storeName', message: '쇼핑몰 이름을 확인해 주세요.' },
      { code: 'STORE_PROFILE_111', field: 'expectedVersion', message: '버전을 확인해 주세요.' },
    ]);
    expect(storeProfileSaveFailure(cause)).toEqual({
      kind: 'errors',
      errors: { storeName: ['쇼핑몰 이름을 확인해 주세요.'], form: ['버전을 확인해 주세요.'] },
    });

    const fieldOnly = new ApiError('validation', 400, null, null, [
      { code: 'STORE_PROFILE_107', field: 'address', message: '주소를 확인해 주세요.' },
    ]);
    expect(storeProfileSaveFailure(fieldOnly)).toEqual({
      kind: 'errors',
      errors: { address: ['주소를 확인해 주세요.'], form: [CHECK_FIELDS] },
    });
  });

  it('STORE_PROFILE_100(알 수 없는 국가 등)은 서버 문구를 폼 전체에, 그 밖은 저장 실패', () => {
    expect(
      storeProfileSaveFailure(
        new ApiError('server', 400, 'STORE_PROFILE_100', '요청 값이 올바르지 않습니다.'),
      ),
    ).toEqual({ kind: 'errors', errors: { form: ['요청 값이 올바르지 않습니다.'] } });
    expect(storeProfileSaveFailure(new Error('x'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});
