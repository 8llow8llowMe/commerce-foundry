import { ApiError } from '@cf/api-client';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { STORE_PROFILE_FIXTURE } from './store-profile-fixtures';
import {
  loadStoreProfile,
  readStoreProfile,
  STORE_PROFILE_NOT_CONFIGURED,
  STORE_PROFILE_PATH,
  storeProfileFailure,
} from './store-profile';

const serverFetch = vi.hoisted(() => vi.fn());
vi.mock('@/lib/server-api', () => ({ serverFetch }));

beforeEach(() => serverFetch.mockReset());

describe('readStoreProfile', () => {
  it('공개 필드 10개를 읽고 앞뒤 공백을 지운다. 빈 선택 칸은 null', () => {
    expect(
      readStoreProfile({
        ...STORE_PROFILE_FIXTURE,
        storeName: ' 테스트몰 ',
        customerServicePhone: '  ',
        customerServiceHours: null,
      }),
    ).toEqual({
      ...STORE_PROFILE_FIXTURE,
      storeName: '테스트몰',
      customerServicePhone: null,
      customerServiceHours: null,
    });
  });

  it('공개 응답에 없는 필드(version 등)는 버린다', () => {
    expect(readStoreProfile({ ...STORE_PROFILE_FIXTURE, version: 3 })).not.toHaveProperty(
      'version',
    );
  });

  it('필수 칸이 비었거나 모양이 다르면 null', () => {
    expect(readStoreProfile(null)).toBeNull();
    expect(readStoreProfile('x')).toBeNull();
    expect(readStoreProfile({ ...STORE_PROFILE_FIXTURE, companyName: '' })).toBeNull();
    expect(readStoreProfile({ ...STORE_PROFILE_FIXTURE, customerServicePhone: 1 })).toBeNull();
  });
});

describe('storeProfileFailure', () => {
  it('미등록 404 만 unregistered, 나머지는 unavailable', () => {
    expect(
      storeProfileFailure(new ApiError('notFound', 404, STORE_PROFILE_NOT_CONFIGURED, null)),
    ).toEqual({ kind: 'unregistered' });
    expect(storeProfileFailure(new ApiError('notFound', 404, 'BFF_001', null))).toEqual({
      kind: 'unavailable',
    });
    expect(storeProfileFailure(new ApiError('unavailable', 503, null, null))).toEqual({
      kind: 'unavailable',
    });
    expect(storeProfileFailure(new DOMException('timeout', 'TimeoutError'))).toEqual({
      kind: 'unavailable',
    });
  });
});

describe('loadStoreProfile', () => {
  it('토큰 없이 캐시하지 않고 시간 제한을 걸어 부른다', async () => {
    serverFetch.mockResolvedValue(STORE_PROFILE_FIXTURE);
    await expect(loadStoreProfile()).resolves.toEqual({
      kind: 'registered',
      profile: STORE_PROFILE_FIXTURE,
    });
    const [path, init, options] = serverFetch.mock.calls[0]!;
    expect(path).toBe(STORE_PROFILE_PATH);
    expect(init).toMatchObject({ cache: 'no-store' });
    expect(init.signal).toBeInstanceOf(AbortSignal);
    expect(options).toEqual({ anonymous: true });
  });

  it('미등록 · 장애 · 모양 불일치를 상태로 가른다', async () => {
    serverFetch.mockRejectedValueOnce(
      new ApiError('notFound', 404, STORE_PROFILE_NOT_CONFIGURED, null),
    );
    await expect(loadStoreProfile()).resolves.toEqual({ kind: 'unregistered' });

    serverFetch.mockRejectedValueOnce(new ApiError('network', null, null, null));
    await expect(loadStoreProfile()).resolves.toEqual({ kind: 'unavailable' });

    serverFetch.mockResolvedValueOnce({ storeName: '테스트몰' });
    await expect(loadStoreProfile()).resolves.toEqual({ kind: 'unavailable' });
  });
});
