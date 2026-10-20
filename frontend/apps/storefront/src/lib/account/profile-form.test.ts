import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { profileDraft, profileErrors, profilePatch } from './profile-form';
import type { MemberProfile } from './types';

const t = ko.account;

const PROFILE: MemberProfile = {
  memberId: '1',
  email: 'a@test.com',
  name: '홍길동',
  phone: '01012345678',
  marketingAgreed: false,
};

describe('profilePatch', () => {
  it('바뀐 것이 없으면 null 이다 — 이름 앞뒤 공백 · 연락처 하이픈만 다른 것도 같다', () => {
    expect(profilePatch(PROFILE, profileDraft(PROFILE))).toBeNull();
    expect(
      profilePatch(PROFILE, { name: ' 홍길동 ', phone: '010-1234-5678', marketingAgreed: false }),
    ).toBeNull();
  });

  it('바뀐 칸만 싣는다', () => {
    expect(
      profilePatch(PROFILE, { name: '김철수', phone: '01012345678', marketingAgreed: true }),
    ).toEqual({ name: '김철수', marketingAgreed: true });
  });

  it('연락처를 비우면 빈 문자열(지우기), 바꾸면 숫자만 보낸다 (BE 는 공백을 받지 않는다)', () => {
    expect(profilePatch(PROFILE, { ...profileDraft(PROFILE), phone: '  ' })).toEqual({ phone: '' });
    expect(profilePatch(PROFILE, { ...profileDraft(PROFILE), phone: '010 9999-8888' })).toEqual({
      phone: '01099998888',
    });
    // 연락처가 없던 회원이 비워 둔 채 저장하면 보낼 것이 없다
    const none = { ...PROFILE, phone: null };
    expect(profilePatch(none, profileDraft(none))).toBeNull();
  });
});

describe('profileErrors', () => {
  it('검증 오류는 칸마다, 동시 수정(004)은 폼 문구다', () => {
    const invalid = new ApiError('validation', 400, 'AUTH_100', null, [
      { code: 'AUTH_109', field: 'phone', message: '서버' },
    ]);
    expect(profileErrors(invalid, t).phone).toEqual([t.errors.AUTH_109]);
    expect(profileErrors(new ApiError('conflict', 409, 'AUTH_004', null), t).form).toEqual([
      t.errors.AUTH_004,
    ]);
    expect(profileErrors(new ApiError('server', 500, null, null), t).form).toEqual([
      t.profile.failed,
    ]);
  });
});
