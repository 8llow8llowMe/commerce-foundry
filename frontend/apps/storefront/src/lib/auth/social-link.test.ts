import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { SOCIAL_FLOW_KEY, SocialStorageUnavailable, type FlowStorage } from './social';
import {
  SOCIAL_LINK_FLOW_KEY,
  callbackPurpose,
  clearSocialLinkFlow,
  completeSocialLink,
  parseSocialLinkFlow,
  readLinkedProviders,
  readSocialLinkFlow,
  runSocialLinkCallback,
  saveSocialLinkFlow,
  socialLinkStartErrorText,
  startSocialLink,
  type SocialLinkFlow,
} from './social-link';

const t = ko.auth;
const STATE = 'L'.repeat(43);
const LOGIN_STATE = 'S'.repeat(43);
const VERIFIER = 'v_-'.repeat(14) + 'v';
const PASSWORD = 'pw-secret-1234';

const FLOW: SocialLinkFlow = {
  purpose: 'LINK',
  provider: 'kakao',
  state: STATE,
  verifier: VERIFIER,
  localePrefix: '',
};

const LOGIN_FLOW = {
  provider: 'kakao',
  state: LOGIN_STATE,
  verifier: VERIFIER,
  returnPath: '/',
  localePrefix: '',
  agreements: null,
};

function memoryStorage(initial: Record<string, string> = {}): FlowStorage & {
  data: Map<string, string>;
} {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (key) => data.get(key) ?? null,
    setItem: (key, value) => void data.set(key, value),
    removeItem: (key) => void data.delete(key),
  };
}

const query = (values: Record<string, string>) => new URLSearchParams(values);

describe('연결 흐름 저장 (sessionStorage)', () => {
  it('로그인 흐름과 다른 키에 저장하고, 지우면 없다', () => {
    const storage = memoryStorage();
    expect(saveSocialLinkFlow(storage, FLOW)).toBe(true);
    expect(SOCIAL_LINK_FLOW_KEY).not.toBe(SOCIAL_FLOW_KEY);
    expect(storage.data.has(SOCIAL_FLOW_KEY)).toBe(false);
    expect(readSocialLinkFlow(storage)).toEqual(FLOW);
    clearSocialLinkFlow(storage);
    expect(readSocialLinkFlow(storage)).toBeNull();
  });

  it('저장소가 없거나 막히면 false · null', () => {
    expect(saveSocialLinkFlow(null, FLOW)).toBe(false);
    const blocked: FlowStorage = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {
        throw new Error('blocked');
      },
    };
    expect(saveSocialLinkFlow(blocked, FLOW)).toBe(false);
    expect(readSocialLinkFlow(blocked)).toBeNull();
    expect(() => clearSocialLinkFlow(blocked)).not.toThrow();
  });

  it('목적이 LINK 가 아니거나 모양이 다르면 버린다(로그인 흐름을 연결 흐름으로 읽지 않는다)', () => {
    expect(parseSocialLinkFlow(JSON.stringify(LOGIN_FLOW))).toBeNull();
    expect(parseSocialLinkFlow(JSON.stringify({ ...FLOW, purpose: 'LOGIN' }))).toBeNull();
    expect(parseSocialLinkFlow(JSON.stringify({ ...FLOW, provider: 'google' }))).toBeNull();
    expect(parseSocialLinkFlow(JSON.stringify({ ...FLOW, state: 'short' }))).toBeNull();
    expect(parseSocialLinkFlow(JSON.stringify({ ...FLOW, verifier: undefined }))).toBeNull();
    expect(parseSocialLinkFlow(JSON.stringify({ ...FLOW, localePrefix: '//evil' }))).toBeNull();
    expect(parseSocialLinkFlow('{')).toBeNull();
  });

  it('비밀번호는 흐름에 담지 않는다', () => {
    expect(
      JSON.stringify(parseSocialLinkFlow(JSON.stringify({ ...FLOW, password: PASSWORD }))),
    ).not.toContain(PASSWORD);
  });
});

describe('callbackPurpose', () => {
  const search = (state: string) => `?code=c1&state=${state}`;

  it('연결 흐름이 없으면 로그인(기존 흐름 그대로)', () => {
    expect(callbackPurpose(memoryStorage(), search(STATE))).toBe('login');
    expect(
      callbackPurpose(
        memoryStorage({ [SOCIAL_FLOW_KEY]: JSON.stringify(LOGIN_FLOW) }),
        search(LOGIN_STATE),
      ),
    ).toBe('login');
  });

  it('연결 흐름이 있으면 연결 — 로그인 흐름의 state 와 맞을 때만 로그인', () => {
    const both = memoryStorage({
      [SOCIAL_FLOW_KEY]: JSON.stringify(LOGIN_FLOW),
      [SOCIAL_LINK_FLOW_KEY]: JSON.stringify(FLOW),
    });
    expect(callbackPurpose(both, search(STATE))).toBe('link');
    expect(callbackPurpose(both, search(LOGIN_STATE))).toBe('login');
    expect(callbackPurpose(both, search('X'.repeat(43)))).toBe('link');
    expect(callbackPurpose(both, '?error=access_denied')).toBe('link');
  });
});

describe('readLinkedProviders', () => {
  it('아는 제공자만 순서대로, 모양이 다르면 null', () => {
    expect(readLinkedProviders({ providers: ['naver', 'kakao', 'google'] })).toEqual([
      'kakao',
      'naver',
    ]);
    expect(readLinkedProviders({ providers: [] })).toEqual([]);
    expect(readLinkedProviders({})).toBeNull();
    expect(readLinkedProviders(null)).toBeNull();
  });
});

describe('startSocialLink', () => {
  const issued = {
    authorizeUrl: 'https://kauth.kakao.com/oauth/authorize?state=' + STATE,
    state: STATE,
    verifier: VERIFIER,
  };

  it('비밀번호로 인가 URL 을 받아 흐름을 저장한 뒤 이동한다 — 비밀번호 · verifier 는 저장소 · 주소에 없다', async () => {
    const storage = memoryStorage();
    const requestAuthorizeUrl = vi.fn().mockResolvedValue(issued);
    const navigate = vi.fn();
    await startSocialLink({
      provider: 'kakao',
      currentPassword: PASSWORD,
      localePrefix: '/en',
      storage,
      requestAuthorizeUrl,
      navigate,
    });
    expect(requestAuthorizeUrl).toHaveBeenCalledWith('kakao', PASSWORD);
    expect(readSocialLinkFlow(storage)).toEqual({ ...FLOW, localePrefix: '/en' });
    expect([...storage.data.values()].join()).not.toContain(PASSWORD);
    expect(navigate).toHaveBeenCalledWith(issued.authorizeUrl);
    const url = String(navigate.mock.calls[0]?.[0]);
    expect(url).not.toContain(VERIFIER);
    expect(url).not.toContain(PASSWORD);
  });

  it('저장하지 못하면 이동하지 않고 SocialStorageUnavailable', async () => {
    const navigate = vi.fn();
    await expect(
      startSocialLink({
        provider: 'kakao',
        currentPassword: PASSWORD,
        localePrefix: '',
        storage: null,
        requestAuthorizeUrl: vi.fn().mockResolvedValue(issued),
        navigate,
      }),
    ).rejects.toBeInstanceOf(SocialStorageUnavailable);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('재인증 실패(AUTH_058)는 그대로 던지고 저장 · 이동하지 않는다', async () => {
    const storage = memoryStorage();
    const navigate = vi.fn();
    const failure = new ApiError('server', 400, 'AUTH_058', null);
    await expect(
      startSocialLink({
        provider: 'naver',
        currentPassword: PASSWORD,
        localePrefix: '',
        storage,
        requestAuthorizeUrl: vi.fn().mockRejectedValue(failure),
        navigate,
      }),
    ).rejects.toBe(failure);
    expect(storage.data.size).toBe(0);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('응답 모양이 다르면(http 인가 URL 등) invalidResponse', async () => {
    await expect(
      startSocialLink({
        provider: 'kakao',
        currentPassword: PASSWORD,
        localePrefix: '',
        storage: memoryStorage(),
        requestAuthorizeUrl: vi.fn().mockResolvedValue({ ...issued, authorizeUrl: 'http://x' }),
        navigate: vi.fn(),
      }),
    ).rejects.toMatchObject({ kind: 'invalidResponse' });
  });
});

describe('completeSocialLink', () => {
  const ok = query({ code: 'c1', state: STATE });

  it('state · 제공자가 맞으면 code · state · verifier 를 한 번 보낸다', async () => {
    const send = vi.fn().mockResolvedValue({ providers: ['kakao'] });
    await expect(completeSocialLink('kakao', ok, FLOW, send, t)).resolves.toEqual({
      kind: 'success',
      provider: 'kakao',
    });
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith('kakao', { code: 'c1', state: STATE, verifier: VERIFIER });
  });

  it('흐름이 없거나(다른 탭) state · 제공자가 다르거나 code 가 없으면 보내지 않는다', async () => {
    const send = vi.fn();
    const invalid = { kind: 'error', message: t.socialLink.invalid };
    await expect(completeSocialLink('kakao', ok, null, send, t)).resolves.toEqual(invalid);
    await expect(
      completeSocialLink('kakao', query({ code: 'c1', state: LOGIN_STATE }), FLOW, send, t),
    ).resolves.toEqual(invalid);
    await expect(completeSocialLink('naver', ok, FLOW, send, t)).resolves.toEqual(invalid);
    await expect(
      completeSocialLink('kakao', query({ state: STATE }), FLOW, send, t),
    ).resolves.toEqual(invalid);
    expect(send).not.toHaveBeenCalled();
  });

  it('사용자 취소 · 제공자 오류는 보내지 않는다', async () => {
    const send = vi.fn();
    await expect(
      completeSocialLink('kakao', query({ error: 'access_denied', state: STATE }), FLOW, send, t),
    ).resolves.toEqual({ kind: 'error', message: t.socialLink.cancelled });
    await expect(
      completeSocialLink('kakao', query({ error: 'server_error' }), FLOW, send, t),
    ).resolves.toEqual({ kind: 'error', message: t.socialLink.failed });
    expect(send).not.toHaveBeenCalled();
  });

  it.each([
    ['AUTH_057', 409],
    ['AUTH_052', 400],
    ['AUTH_004', 409],
    ['AUTH_054', 502],
  ])('%s 는 코드별 안내', async (code, status) => {
    const send = vi.fn().mockRejectedValue(new ApiError('server', status, code, null));
    await expect(completeSocialLink('kakao', ok, FLOW, send, t)).resolves.toEqual({
      kind: 'error',
      message: t.socialLink.errors[code],
    });
  });

  it('세션이 끝났으면(401) signedOut', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError('unauthorized', 401, null, null));
    await expect(completeSocialLink('kakao', ok, FLOW, send, t)).resolves.toEqual({
      kind: 'signedOut',
    });
  });
});

describe('socialLinkStartErrorText', () => {
  it('재인증 실패 · 시도 제한(Retry-After 없음) · 연결 꺼짐 · 저장소 막힘 · 그 밖', () => {
    expect(socialLinkStartErrorText(new ApiError('server', 400, 'AUTH_058', null), t)).toBe(
      t.socialLink.errors.AUTH_058,
    );
    expect(
      socialLinkStartErrorText(new ApiError('rateLimited', 429, 'AUTH_059', null, [], null), t),
    ).toBe(`${t.rateLimit.reasons.AUTH_059} ${t.rateLimit.retryLater}`);
    expect(socialLinkStartErrorText(new ApiError('server', 503, 'AUTH_060', null), t)).toBe(
      t.socialLink.errors.AUTH_060,
    );
    expect(socialLinkStartErrorText(new SocialStorageUnavailable(), t)).toBe(
      t.socialLink.storageUnavailable,
    );
    expect(socialLinkStartErrorText(new Error('x'), t)).toBe(t.socialLink.startFailed);
  });

  it('영문 사전도 같은 코드를 갖는다', () => {
    expect(Object.keys(en.auth.socialLink.errors).sort()).toEqual(
      Object.keys(ko.auth.socialLink.errors).sort(),
    );
    expect(en.auth.rateLimit.reasons.AUTH_059).toBeTruthy();
  });
});

describe('runSocialLinkCallback', () => {
  const SEARCH = `?code=c1&state=${STATE}`;

  function setup(flow: SocialLinkFlow | null, prefix = '', send = vi.fn().mockResolvedValue(null)) {
    const calls: string[] = [];
    const storage = memoryStorage(flow ? { [SOCIAL_LINK_FLOW_KEY]: JSON.stringify(flow) } : {});
    const removeItem = storage.removeItem;
    storage.removeItem = (key) => {
      calls.push(`clear ${key}`);
      removeItem(key);
    };
    const location = {
      search: SEARCH,
      pathname: '/login/oauth/kakao',
      replace: vi.fn((url: string) => void calls.push(`location.replace ${url}`)),
    };
    const history = {
      replaceState: vi.fn((_: unknown, __: string, url?: string | URL | null) => {
        calls.push(`replaceState ${String(url)}`);
      }),
    };
    const sendSpy = vi.fn((provider: string, body: unknown) => {
      calls.push('send');
      return send(provider, body);
    });
    const navigate = vi.fn();
    const deps = {
      provider: 'kakao' as const,
      prefix,
      storage,
      location,
      history,
      send: sendSpy,
      localize: (path: string) => `${prefix}${path}`,
      navigate,
      t,
    };
    return { deps, calls, storage, sendSpy, navigate };
  }

  it('쿼리를 지우고 → 연결 흐름을 지우고 → 보낸다, 성공하면 연결 화면(완료 안내)으로', async () => {
    const { deps, calls, storage, navigate } = setup(FLOW);
    await expect(runSocialLinkCallback(deps, { current: false })).resolves.toEqual({
      kind: 'success',
    });
    expect(calls).toEqual([
      'replaceState /login/oauth/kakao',
      `clear ${SOCIAL_LINK_FLOW_KEY}`,
      'send',
    ]);
    expect(storage.data.has(SOCIAL_LINK_FLOW_KEY)).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/mypage/social?linked=kakao');
  });

  it('두 번 불러도(StrictMode) 한 번만 보낸다', async () => {
    const { deps, sendSpy } = setup(FLOW);
    const guard = { current: false };
    const [first, second] = await Promise.all([
      runSocialLinkCallback(deps, guard),
      runSocialLinkCallback(deps, guard),
    ]);
    expect(first).toEqual({ kind: 'success' });
    expect(second).toBeNull();
    expect(sendSpy).toHaveBeenCalledTimes(1);
  });

  it('시작한 언어가 다르면 그 언어의 콜백으로 넘기고 흐름을 지우지 않는다', async () => {
    const { deps, calls, storage, sendSpy } = setup({ ...FLOW, localePrefix: '/en' });
    await expect(runSocialLinkCallback(deps, { current: false })).resolves.toEqual({
      kind: 'handoff',
    });
    expect(calls).toEqual([`location.replace /en/login/oauth/kakao${SEARCH}`]);
    expect(storage.data.has(SOCIAL_LINK_FLOW_KEY)).toBe(true);
    expect(sendSpy).not.toHaveBeenCalled();
  });

  it('충돌(AUTH_057)이면 이동하지 않고 안내 — 흐름은 이미 지웠다(비밀번호 확인부터 다시)', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError('server', 409, 'AUTH_057', null));
    const { deps, storage, navigate } = setup(FLOW, '', send);
    await expect(runSocialLinkCallback(deps, { current: false })).resolves.toEqual({
      kind: 'error',
      message: t.socialLink.errors.AUTH_057,
    });
    expect(storage.data.has(SOCIAL_LINK_FLOW_KEY)).toBe(false);
    expect(navigate).not.toHaveBeenCalled();
  });
});
