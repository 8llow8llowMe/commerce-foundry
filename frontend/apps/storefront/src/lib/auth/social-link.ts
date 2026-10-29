import { ApiError, isSocialProvider, SOCIAL_PROVIDERS, type SocialProvider } from '@cf/api-client';
import { rateLimitTextOf } from '@/lib/auth/rate-limit';
import {
  FLOW_TOKEN,
  LOCALE_PREFIX,
  MAX_CODE_LENGTH,
  SocialStorageUnavailable,
  localeHandoffPath,
  readAuthorizeResponse,
  readSocialFlow,
  type FlowStorage,
} from '@/lib/auth/social';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 일반 계정에 카카오 · 네이버 로그인 연결(#356 · BE #355).
 *
 * 시작(마이페이지 `/mypage/social`): 현재 비밀번호로 재인증해 연결용 authorize-url 을 받고,
 * `{ purpose: 'LINK', provider, state, verifier, localePrefix }` 를 **로그인과 다른 sessionStorage 키**(`cf-social-link`)에 둔다.
 * 비밀번호는 요청 본문에만 싣고 저장하지 않는다.
 * 콜백: BE 는 로그인과 같은 redirect URI(`/login/oauth/{provider}`)를 쓰므로 콜백 화면이 저장한 흐름의 state 로 목적을 가른다(`callbackPurpose`).
 * 연결 흐름의 state 와 맞을 때만 일반 BFF 프록시(고객 access 쿠키)로 code · state · verifier 를 연결 완료 API 에 보낸다.
 * BE 는 state 를 먼저 소비하므로 저장 값은 보내기 전에 지우고, 그 뒤의 실패는 모두 비밀번호 확인부터 다시 시작한다.
 */

/** sessionStorage 키 — 로그인 흐름(`cf-social-login`)과 따로 둔다 */
export const SOCIAL_LINK_FLOW_KEY = 'cf-social-link';

/** 연결 화면(언어 접두어 없는 앱 경로) */
export const SOCIAL_LINK_PAGE = '/mypage/social';

/** 연결 API 경로 — 응답에 토큰이 없어 일반 BFF 프록시가 중계한다 */
export const SOCIAL_ACCOUNTS_PATH = '/api/v1/members/me/social-accounts';

export type SocialLinkFlow = {
  purpose: 'LINK';
  provider: SocialProvider;
  state: string;
  verifier: string;
  /** 시작한 화면의 언어 접두어 — redirect URI 는 하나라 기본 언어로 돌아온다 */
  localePrefix: string;
};

export function parseSocialLinkFlow(raw: string | null): SocialLinkFlow | null {
  if (!raw) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const { purpose, provider, state, verifier, localePrefix } = value as Record<string, unknown>;
  if (purpose !== 'LINK' || !isSocialProvider(provider)) return null;
  if (typeof state !== 'string' || !FLOW_TOKEN.test(state)) return null;
  if (typeof verifier !== 'string' || !FLOW_TOKEN.test(verifier)) return null;
  if (typeof localePrefix !== 'string' || !LOCALE_PREFIX.test(localePrefix)) return null;
  return { purpose, provider, state, verifier, localePrefix };
}

export function readSocialLinkFlow(storage: FlowStorage | null): SocialLinkFlow | null {
  try {
    return parseSocialLinkFlow(storage?.getItem(SOCIAL_LINK_FLOW_KEY) ?? null);
  } catch {
    return null;
  }
}

export function saveSocialLinkFlow(storage: FlowStorage | null, flow: SocialLinkFlow): boolean {
  if (!storage) return false;
  try {
    storage.setItem(SOCIAL_LINK_FLOW_KEY, JSON.stringify(flow));
    return true;
  } catch {
    return false;
  }
}

export function clearSocialLinkFlow(storage: FlowStorage | null): void {
  try {
    storage?.removeItem(SOCIAL_LINK_FLOW_KEY);
  } catch {
    // 지우지 못해도 state 는 BE 에서 한 번만 쓰인다
  }
}

/**
 * 콜백이 어느 흐름인지 정한다. 연결 흐름이 없으면 로그인(기존 흐름 그대로)이다.
 * 둘 다 있으면 쿼리 state 가 로그인 흐름과 맞을 때만 로그인 — 그 밖은 연결 흐름이 받아 state 를 대조한다(맞지 않으면 보내지 않는다).
 */
export function callbackPurpose(storage: FlowStorage | null, search: string): 'login' | 'link' {
  if (!readSocialLinkFlow(storage)) return 'login';
  const state = new URLSearchParams(search).get('state');
  const login = readSocialFlow(storage);
  return login && state !== null && login.state === state ? 'login' : 'link';
}

/** 목록 응답 `{ providers: [...] }` — 모르는 코드는 버린다. 모양이 다르면 null */
export function readLinkedProviders(value: unknown): SocialProvider[] | null {
  if (typeof value !== 'object' || value === null) return null;
  const { providers } = value as Record<string, unknown>;
  if (!Array.isArray(providers)) return null;
  return SOCIAL_PROVIDERS.filter((provider) => providers.includes(provider));
}

export type StartSocialLink = {
  provider: SocialProvider;
  /** 요청 본문에만 싣는다 — 저장 · 주소 · 로그에 남기지 않는다 */
  currentPassword: string;
  localePrefix: string;
  storage: FlowStorage | null;
  /** `POST /api/v1/members/me/social-accounts/{provider}/authorize-url` 의 dataBody */
  requestAuthorizeUrl: (provider: SocialProvider, currentPassword: string) => Promise<unknown>;
  /** 제공자 인가 화면으로 이동(`window.location.assign`) */
  navigate: (url: string) => void;
};

/** 재인증해 연결 흐름을 저장하고 제공자로 보낸다. 실패는 ApiError · SocialStorageUnavailable 로 던진다 */
export async function startSocialLink(input: StartSocialLink): Promise<void> {
  const issued = readAuthorizeResponse(
    await input.requestAuthorizeUrl(input.provider, input.currentPassword),
  );
  if (!issued) throw new ApiError('invalidResponse', 200, null, null);
  const saved = saveSocialLinkFlow(input.storage, {
    purpose: 'LINK',
    provider: input.provider,
    state: issued.state,
    verifier: issued.verifier,
    localePrefix: input.localePrefix,
  });
  if (!saved) throw new SocialStorageUnavailable();
  input.navigate(issued.authorizeUrl);
}

export type SocialLinkBody = { code: string; state: string; verifier: string };

type AuthTexts = Dictionary['auth'];

function linkErrorText(cause: unknown, t: AuthTexts, fallback: string): string {
  if (cause instanceof SocialStorageUnavailable) return t.socialLink.storageUnavailable;
  if (!(cause instanceof ApiError)) return fallback;
  const limited = rateLimitTextOf(cause, t);
  if (limited) return limited;
  const fieldCode = cause.fieldErrors[0]?.code ?? null;
  const code = cause.code ?? fieldCode;
  return (
    (code && t.socialLink.errors[code]) || (fieldCode && t.socialLink.errors[fieldCode]) || fallback
  );
}

/** 연결 시작(재인증 · authorize-url) 실패 문구 */
export function socialLinkStartErrorText(cause: unknown, t: AuthTexts): string {
  return linkErrorText(cause, t, t.socialLink.startFailed);
}

export type SocialLinkOutcome =
  | { kind: 'success'; provider: SocialProvider }
  /** 고객 세션이 끝났다(401) — 다시 로그인해 연결 화면으로 돌아온다 */
  | { kind: 'signedOut' }
  | { kind: 'error'; message: string };

/** 콜백 쿼리를 연결 흐름과 맞춰 보고, 맞을 때만 보낸다. 흐름 정리 · 주소 정리는 부르는 쪽이 보내기 전에 한다 */
export async function completeSocialLink(
  provider: SocialProvider,
  query: URLSearchParams,
  flow: SocialLinkFlow | null,
  send: (provider: SocialProvider, body: SocialLinkBody) => Promise<unknown>,
  t: AuthTexts,
): Promise<SocialLinkOutcome> {
  const error = query.get('error');
  if (error !== null) {
    return {
      kind: 'error',
      message: error === 'access_denied' ? t.socialLink.cancelled : t.socialLink.failed,
    };
  }
  const state = query.get('state');
  const code = query.get('code');
  if (!flow || flow.provider !== provider || state === null || state !== flow.state) {
    return { kind: 'error', message: t.socialLink.invalid };
  }
  if (!code || code.length > MAX_CODE_LENGTH)
    return { kind: 'error', message: t.socialLink.invalid };
  try {
    await send(provider, { code, state, verifier: flow.verifier });
    return { kind: 'success', provider };
  } catch (cause) {
    if (cause instanceof ApiError && cause.kind === 'unauthorized') return { kind: 'signedOut' };
    return { kind: 'error', message: linkErrorText(cause, t, t.socialLink.failed) };
  }
}

/** 연결 성공 뒤 갈 화면 — 연결 화면이 목록을 다시 읽고 완료 안내를 띄운다 */
export function linkedHref(provider: SocialProvider): string {
  return `${SOCIAL_LINK_PAGE}?linked=${provider}`;
}

export type SocialLinkCallbackDeps = {
  provider: SocialProvider;
  /** 지금 화면의 언어 접두어('' 또는 '/en') */
  prefix: string;
  storage: FlowStorage | null;
  location: Pick<Location, 'search' | 'pathname' | 'replace'>;
  history: Pick<History, 'replaceState'>;
  /** 연결 완료(`POST /api/v1/members/me/social-accounts/{provider}`, 일반 BFF 프록시) */
  send: (provider: SocialProvider, body: SocialLinkBody) => Promise<unknown>;
  /** 앱 경로 → 지금 언어 주소 */
  localize: (path: string) => string;
  /** 성공 뒤 이동(router.replace + refresh) */
  navigate: (href: string) => void;
  t: AuthTexts;
};

export type SocialLinkFailure = Exclude<SocialLinkOutcome, { kind: 'success' }>;

export type SocialLinkCallbackRun = { kind: 'handoff' } | SocialLinkFailure | { kind: 'success' };

/**
 * 연결 콜백이 한 번 하는 일. 순서는 로그인 콜백(`runSocialCallback`)과 같다 —
 * 가드 → 언어 넘기기(흐름을 지우지 않는다) → 쿼리 지우기 → 연결 흐름 지우기 → 보내기 → 성공이면 연결 화면으로 이동.
 */
export async function runSocialLinkCallback(
  deps: SocialLinkCallbackDeps,
  guard: { current: boolean },
): Promise<SocialLinkCallbackRun | null> {
  if (guard.current) return null;
  guard.current = true;

  const { provider, storage, location } = deps;
  const flow = readSocialLinkFlow(storage);
  const search = location.search;
  const handoff = localeHandoffPath(flow, deps.prefix, provider, search);
  if (handoff) {
    location.replace(handoff);
    return { kind: 'handoff' };
  }
  deps.history.replaceState(null, '', location.pathname);
  clearSocialLinkFlow(storage);

  const outcome = await completeSocialLink(
    provider,
    new URLSearchParams(search),
    flow,
    deps.send,
    deps.t,
  );
  if (outcome.kind === 'success') {
    deps.navigate(deps.localize(linkedHref(outcome.provider)));
    return { kind: 'success' };
  }
  return outcome;
}
