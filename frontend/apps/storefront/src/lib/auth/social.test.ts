import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  SOCIAL_FLOW_KEY,
  SocialStorageUnavailable,
  checkCallback,
  clearSocialFlow,
  completeSocialLogin,
  localeHandoffPath,
  parseSocialFlow,
  readAuthorizeResponse,
  readSocialFlow,
  runSocialCallback,
  saveSocialFlow,
  socialFailure,
  socialStartErrorText,
  startSocialLogin,
  type FlowStorage,
  type SocialFlow,
} from './social';

const t = ko.auth;
const STATE = 'S'.repeat(43);
const VERIFIER = 'v_-'.repeat(14) + 'v';
const AGREED = { termsOfService: true, privacyPolicy: true, marketing: false };

const FLOW: SocialFlow = {
  provider: 'kakao',
  state: STATE,
  verifier: VERIFIER,
  returnPath: '/shop',
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

describe('흐름 저장 (sessionStorage)', () => {
  it('저장한 흐름을 그대로 읽고, 지우면 없다', () => {
    const storage = memoryStorage();
    expect(saveSocialFlow(storage, { ...FLOW, agreements: AGREED })).toBe(true);
    expect(readSocialFlow(storage)).toEqual({ ...FLOW, agreements: AGREED });
    clearSocialFlow(storage);
    expect(storage.data.has(SOCIAL_FLOW_KEY)).toBe(false);
    expect(readSocialFlow(storage)).toBeNull();
  });

  it('저장소가 없거나 쓰기가 막히면 false, 읽기가 막히면 null', () => {
    expect(saveSocialFlow(null, FLOW)).toBe(false);
    const broken: FlowStorage = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('quota');
      },
      removeItem: () => {
        throw new Error('denied');
      },
    };
    expect(saveSocialFlow(broken, FLOW)).toBe(false);
    expect(readSocialFlow(broken)).toBeNull();
    expect(() => clearSocialFlow(broken)).not.toThrow();
  });

  it.each([
    ['깨진 JSON', '{'],
    ['모르는 제공자', JSON.stringify({ ...FLOW, provider: 'google' })],
    ['state 모양', JSON.stringify({ ...FLOW, state: 'short' })],
    ['verifier 모양', JSON.stringify({ ...FLOW, verifier: 'x'.repeat(44) })],
    ['언어 접두어', JSON.stringify({ ...FLOW, localePrefix: '//evil.test' })],
  ])('%s 가 틀리면 흐름이 없는 것으로 본다', (_, raw) => {
    expect(parseSocialFlow(raw)).toBeNull();
  });

  it('돌아갈 경로는 다시 거른다(열린 리다이렉트 금지)', () => {
    const raw = JSON.stringify({ ...FLOW, returnPath: '//evil.test' });
    expect(parseSocialFlow(raw)?.returnPath).toBe('/');
  });
});

describe('readAuthorizeResponse', () => {
  const ok = {
    authorizeUrl: 'https://kauth.example/authorize?x=1',
    state: STATE,
    verifier: VERIFIER,
  };

  it('https 인가 URL 과 state · verifier 모양을 확인한다', () => {
    expect(readAuthorizeResponse(ok)).toEqual(ok);
    expect(readAuthorizeResponse({ ...ok, authorizeUrl: 'javascript:alert(1)' })).toBeNull();
    expect(readAuthorizeResponse({ ...ok, authorizeUrl: 'http://kauth.example' })).toBeNull();
    expect(readAuthorizeResponse({ ...ok, verifier: undefined })).toBeNull();
    expect(readAuthorizeResponse(null)).toBeNull();
  });
});

describe('startSocialLogin', () => {
  const issued = {
    authorizeUrl: 'https://nid.example/authorize',
    state: STATE,
    verifier: VERIFIER,
  };

  it('흐름을 저장한 뒤 인가 URL 로 가고, verifier 는 이동 주소에 넣지 않는다', async () => {
    const storage = memoryStorage();
    const navigate = vi.fn();
    await startSocialLogin({
      provider: 'naver',
      returnPath: '/shop?page=2',
      localePrefix: '/en',
      agreements: AGREED,
      storage,
      requestAuthorizeUrl: vi.fn().mockResolvedValue(issued),
      navigate,
    });
    expect(navigate).toHaveBeenCalledWith(issued.authorizeUrl);
    expect(String(navigate.mock.calls[0]![0])).not.toContain(VERIFIER);
    expect(readSocialFlow(storage)).toEqual({
      provider: 'naver',
      state: STATE,
      verifier: VERIFIER,
      returnPath: '/shop?page=2',
      localePrefix: '/en',
      agreements: AGREED,
    });
  });

  it('저장하지 못하면 이동하지 않고 SocialStorageUnavailable 이다', async () => {
    const navigate = vi.fn();
    await expect(
      startSocialLogin({
        provider: 'kakao',
        returnPath: '/',
        localePrefix: '',
        agreements: null,
        storage: null,
        requestAuthorizeUrl: vi.fn().mockResolvedValue(issued),
        navigate,
      }),
    ).rejects.toBeInstanceOf(SocialStorageUnavailable);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('응답 모양이 다르면 invalidResponse 로 던지고 저장하지 않는다', async () => {
    const storage = memoryStorage();
    await expect(
      startSocialLogin({
        provider: 'kakao',
        returnPath: '/',
        localePrefix: '',
        agreements: null,
        storage,
        requestAuthorizeUrl: vi.fn().mockResolvedValue({ authorizeUrl: 'https://x.example' }),
        navigate: vi.fn(),
      }),
    ).rejects.toMatchObject({ kind: 'invalidResponse' });
    expect(storage.data.size).toBe(0);
  });
});

describe('checkCallback', () => {
  it('state · 제공자가 맞으면 code · state · verifier 를 보낸다(동의가 없으면 싣지 않는다)', () => {
    const result = checkCallback('kakao', query({ code: 'c1', state: STATE }), FLOW);
    expect(result).toEqual({
      kind: 'send',
      flow: FLOW,
      body: { code: 'c1', state: STATE, verifier: VERIFIER },
    });
  });

  it('흐름에 동의가 있으면 함께 보낸다', () => {
    const flow = { ...FLOW, agreements: AGREED };
    const result = checkCallback('kakao', query({ code: 'c1', state: STATE }), flow);
    expect(result.kind === 'send' && result.body.agreements).toEqual(AGREED);
  });

  it.each([
    ['흐름 없음', 'kakao', { code: 'c', state: STATE }, null],
    ['state 다름', 'kakao', { code: 'c', state: 'T'.repeat(43) }, FLOW],
    ['state 없음', 'kakao', { code: 'c' }, FLOW],
    ['제공자 다름', 'naver', { code: 'c', state: STATE }, FLOW],
    ['code 없음', 'kakao', { state: STATE }, FLOW],
    ['code 너무 김', 'kakao', { code: 'c'.repeat(2049), state: STATE }, FLOW],
  ] as const)('%s 이면 보내지 않는다', (_, provider, values, flow) => {
    expect(checkCallback(provider, query(values), flow).kind).toBe('invalid');
  });

  it('제공자가 error 로 돌려보내면(사용자 취소 등) 보내지 않는다', () => {
    expect(checkCallback('kakao', query({ error: 'access_denied', state: STATE }), FLOW)).toEqual({
      kind: 'denied',
      flow: FLOW,
      cancelled: true,
    });
    expect(checkCallback('naver', query({ error: 'server_error' }), null)).toMatchObject({
      kind: 'denied',
      cancelled: false,
    });
  });
});

describe('localeHandoffPath', () => {
  it('시작한 언어가 다르면 같은 쿼리로 그 언어의 콜백으로 보낸다', () => {
    const flow = { ...FLOW, localePrefix: '/en' };
    expect(localeHandoffPath(flow, '', 'kakao', '?code=c&state=s')).toBe(
      '/en/login/oauth/kakao?code=c&state=s',
    );
    expect(localeHandoffPath(FLOW, '', 'kakao', '?code=c')).toBeNull();
    expect(localeHandoffPath(null, '', 'kakao', '?code=c')).toBeNull();
  });
});

describe('completeSocialLogin', () => {
  const ok = query({ code: 'c1', state: STATE });

  it('성공하면 한 번만 보내고 success', async () => {
    const send = vi.fn().mockResolvedValue(null);
    await expect(completeSocialLogin('kakao', ok, FLOW, send, t)).resolves.toEqual({
      kind: 'success',
      flow: FLOW,
    });
    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith('kakao', { code: 'c1', state: STATE, verifier: VERIFIER });
  });

  it('state 가 맞지 않으면 BE 를 부르지 않고 다시 시작 안내', async () => {
    const send = vi.fn();
    const outcome = await completeSocialLogin('kakao', query({ code: 'c' }), FLOW, send, t);
    expect(outcome).toEqual({ kind: 'error', message: t.social.invalid, flow: FLOW });
    expect(send).not.toHaveBeenCalled();
  });

  it('사용자 취소는 취소 안내', async () => {
    const send = vi.fn();
    const outcome = await completeSocialLogin(
      'naver',
      query({ error: 'access_denied' }),
      null,
      send,
      t,
    );
    expect(outcome).toMatchObject({ kind: 'error', message: t.social.cancelled });
    expect(send).not.toHaveBeenCalled();
  });

  it('AUTH_056 이면 약관 동의 화면', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError('server', 400, 'AUTH_056', null));
    await expect(completeSocialLogin('kakao', ok, FLOW, send, t)).resolves.toEqual({
      kind: 'agreements',
      flow: FLOW,
    });
  });

  it('그 밖의 실패는 코드별 문구', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError('conflict', 409, 'AUTH_057', null));
    await expect(completeSocialLogin('kakao', ok, FLOW, send, t)).resolves.toEqual({
      kind: 'error',
      message: t.social.errors.AUTH_057,
      flow: FLOW,
    });
  });
});

describe('오류 → 문구', () => {
  const failure = (cause: unknown, dict = t) => socialFailure(cause, dict);

  it.each(['AUTH_051', 'AUTH_052', 'AUTH_053', 'AUTH_054', 'AUTH_055', 'AUTH_057', 'AUTH_002'])(
    '%s 는 사전 문구(국문 · 영문)',
    (code) => {
      const cause = new ApiError('server', 400, code, '서버 문구');
      expect(failure(cause)).toEqual({ kind: 'error', message: t.social.errors[code] });
      expect(failure(cause, en.auth)).toEqual({
        kind: 'error',
        message: en.auth.social.errors[code],
      });
    },
  );

  it('AUTH_055 는 이메일 제공 동의 안내다', () => {
    expect(t.social.errors.AUTH_055).toContain('이메일 제공');
  });

  it('검증 오류(AUTH_151 등)는 fieldErrors 코드로 고른다', () => {
    const cause = new ApiError('validation', 400, 'COMMON_001', null, [
      { field: 'state', code: 'AUTH_151', message: 'state' },
    ]);
    expect(failure(cause)).toEqual({ kind: 'error', message: t.social.errors.AUTH_151 });
  });

  it('429 는 요청 제한 안내(남은 시간)', () => {
    const cause = new ApiError('rateLimited', 429, 'GATEWAY_006', null, [], 30);
    expect(failure(cause)).toEqual({
      kind: 'error',
      message: '요청이 너무 많습니다. 30초 뒤에 다시 시도해 주세요.',
    });
  });

  it('모르는 코드 · ApiError 가 아니면 기본 문구', () => {
    expect(failure(new ApiError('server', 500, 'X_999', '서버'))).toEqual({
      kind: 'error',
      message: t.social.failed,
    });
    expect(failure(new Error('boom'))).toEqual({ kind: 'error', message: t.social.failed });
  });

  it('시작 실패: 저장소 막힘 · 소셜 꺼짐 · 그 밖', () => {
    expect(socialStartErrorText(new SocialStorageUnavailable(), t)).toBe(
      t.social.storageUnavailable,
    );
    expect(socialStartErrorText(new ApiError('unavailable', 503, 'AUTH_051', null), t)).toBe(
      t.social.errors.AUTH_051,
    );
    expect(socialStartErrorText(new ApiError('network', null, null, null), t)).toBe(
      t.social.startFailed,
    );
  });
});

describe('runSocialCallback', () => {
  const SEARCH = `?code=c1&state=${STATE}`;

  function setup(flow: SocialFlow | null, prefix = '', send = vi.fn().mockResolvedValue(null)) {
    const calls: string[] = [];
    const storage = memoryStorage(flow ? { [SOCIAL_FLOW_KEY]: JSON.stringify(flow) } : {});
    const removeItem = storage.removeItem;
    storage.removeItem = (key) => {
      calls.push('clear');
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
    return { deps, calls, storage, location, history, sendSpy, navigate };
  }

  it('주소의 쿼리를 지우고 → 흐름을 지우고 → 보낸다, 성공하면 지금 언어의 돌아갈 경로로 이동', async () => {
    const { deps, calls, storage, navigate } = setup(FLOW);
    await expect(runSocialCallback(deps, { current: false })).resolves.toEqual({
      kind: 'success',
    });
    expect(calls).toEqual(['replaceState /login/oauth/kakao', 'clear', 'send']);
    expect(storage.data.has(SOCIAL_FLOW_KEY)).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/shop');
  });

  it('두 번 불러도(StrictMode effect 이중 실행) 한 번만 보낸다', async () => {
    const { deps, sendSpy } = setup(FLOW);
    const guard = { current: false };
    const [first, second] = await Promise.all([
      runSocialCallback(deps, guard),
      runSocialCallback(deps, guard),
    ]);
    expect(first).toEqual({ kind: 'success' });
    expect(second).toBeNull();
    expect(sendSpy).toHaveBeenCalledTimes(1);
  });

  it('시작한 언어가 다르면 그 언어의 콜백으로 location.replace 만 하고 흐름을 지우지 않는다', async () => {
    const { deps, calls, storage, sendSpy } = setup({ ...FLOW, localePrefix: '/en' });
    await expect(runSocialCallback(deps, { current: false })).resolves.toEqual({
      kind: 'handoff',
    });
    expect(calls).toEqual([`location.replace /en/login/oauth/kakao${SEARCH}`]);
    expect(storage.data.has(SOCIAL_FLOW_KEY)).toBe(true);
    expect(sendSpy).not.toHaveBeenCalled();
  });

  it('흐름이 없으면 쿼리 · 흐름을 정리하고 BE 를 부르지 않는다', async () => {
    const { deps, calls, navigate } = setup(null);
    await expect(runSocialCallback(deps, { current: false })).resolves.toEqual({
      kind: 'error',
      message: t.social.invalid,
      flow: null,
    });
    expect(calls).toEqual(['replaceState /login/oauth/kakao', 'clear']);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('AUTH_056 이면 이동하지 않고 동의 화면 결과를 돌려준다', async () => {
    const send = vi.fn().mockRejectedValue(new ApiError('server', 400, 'AUTH_056', null));
    const { deps, navigate } = setup(FLOW, '', send);
    await expect(runSocialCallback(deps, { current: false })).resolves.toEqual({
      kind: 'agreements',
      flow: FLOW,
    });
    expect(navigate).not.toHaveBeenCalled();
  });
});
