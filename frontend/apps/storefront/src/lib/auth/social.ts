import { ApiError, isSocialProvider, type SocialProvider } from '@cf/api-client';
import { readAgreements, type SignUpAgreements } from '@/lib/auth/agreements';
import { rateLimitTextOf } from '@/lib/auth/rate-limit';
import { safeReturnPath } from '@/lib/auth/return-path';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 카카오 · 네이버 소셜 로그인(#25 · BE #5).
 *
 * 시작: authorize-url 을 받아 `{ provider, state, verifier, returnPath, localePrefix, agreements }` 를 **같은 탭 sessionStorage** 에 두고
 * 제공자 인가 화면으로 간다. verifier 는 이 앱의 브라우저 바인딩 비밀이라 URL · 로그에 넣지 않는다(BE security-guide §13).
 * 콜백(`/login/oauth/{provider}`): 쿼리 state 를 저장한 값과 비교하고, 맞으면 BFF 소셜 로그인으로 code · state · verifier 를 보낸다.
 * BE 는 요청마다 state 를 먼저 소비하므로 저장 값은 보내기 전에 지운다 — 그 뒤의 실패는 모두 인가 URL 부터 다시 시작한다.
 */

// 제공자 허용 목록은 BFF 핸들러와 같은 것(`@cf/api-client` 의 SOCIAL_PROVIDERS)을 쓴다
export { isSocialProvider, SOCIAL_PROVIDERS, type SocialProvider } from '@cf/api-client';

/** sessionStorage 키. 탭마다 진행 중인 흐름 하나 — 새로 시작하면 덮어쓴다 */
export const SOCIAL_FLOW_KEY = 'cf-social-login';

/** BE state · verifier 모양(SecureRandom 256비트 base64url) */
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
/** 언어 경로 접두어('' 또는 '/en') — 다른 값이면 콜백이 엉뚱한 곳으로 보내지 않게 버린다 */
const LOCALE_PREFIX = /^(\/[a-z]{2}(-[A-Za-z]{2,4})?)?$/;
/** BE `SocialLoginRequest.code` 상한 */
const MAX_CODE_LENGTH = 2048;

export type SocialFlow = {
  provider: SocialProvider;
  state: string;
  verifier: string;
  /** 로그인 뒤 돌아갈 앱 경로(언어 접두어 없이, `safeReturnPath`) */
  returnPath: string;
  /** 시작한 화면의 언어 접두어 — 제공자 콜백 주소(redirect URI)는 하나라 기본 언어로 돌아온다 */
  localePrefix: string;
  /** 가입 화면에서 시작했거나 신규 가입 동의 뒤 다시 시작했을 때만 있다(기존 회원이면 BE 가 무시) */
  agreements: SignUpAgreements | null;
};

export type FlowStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** 이 탭의 sessionStorage. 막힌 브라우저(저장소 차단 · 일부 비공개 창)면 null */
export function sessionFlowStorage(): FlowStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.sessionStorage;
  } catch {
    return null;
  }
}

export function parseSocialFlow(raw: string | null): SocialFlow | null {
  if (!raw) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeof value !== 'object' || value === null) return null;
  const record = value as Record<string, unknown>;
  const { provider, state, verifier, returnPath, localePrefix } = record;
  if (!isSocialProvider(provider)) return null;
  if (typeof state !== 'string' || !TOKEN.test(state)) return null;
  if (typeof verifier !== 'string' || !TOKEN.test(verifier)) return null;
  if (typeof returnPath !== 'string') return null;
  if (typeof localePrefix !== 'string' || !LOCALE_PREFIX.test(localePrefix)) return null;
  return {
    provider,
    state,
    verifier,
    returnPath: safeReturnPath(returnPath),
    localePrefix,
    agreements: record.agreements == null ? null : readAgreements(record.agreements),
  };
}

export function readSocialFlow(storage: FlowStorage | null): SocialFlow | null {
  try {
    return parseSocialFlow(storage?.getItem(SOCIAL_FLOW_KEY) ?? null);
  } catch {
    return null;
  }
}

export function saveSocialFlow(storage: FlowStorage | null, flow: SocialFlow): boolean {
  if (!storage) return false;
  try {
    storage.setItem(SOCIAL_FLOW_KEY, JSON.stringify(flow));
    return true;
  } catch {
    return false;
  }
}

export function clearSocialFlow(storage: FlowStorage | null): void {
  try {
    storage?.removeItem(SOCIAL_FLOW_KEY);
  } catch {
    // 지우지 못해도 state 는 BE 에서 한 번만 쓰인다
  }
}

/** authorize-url 응답 `{ authorizeUrl, state, verifier }`. 모양이 다르거나 https 가 아니면 null */
export function readAuthorizeResponse(
  value: unknown,
): { authorizeUrl: string; state: string; verifier: string } | null {
  if (typeof value !== 'object' || value === null) return null;
  const { authorizeUrl, state, verifier } = value as Record<string, unknown>;
  if (typeof authorizeUrl !== 'string' || typeof state !== 'string') return null;
  if (typeof verifier !== 'string' || !TOKEN.test(state) || !TOKEN.test(verifier)) return null;
  try {
    if (new URL(authorizeUrl).protocol !== 'https:') return null;
  } catch {
    return null;
  }
  return { authorizeUrl, state, verifier };
}

/** sessionStorage 를 쓸 수 없어 시작하지 못했다 — 콜백에서 state 를 비교할 수 없다 */
export class SocialStorageUnavailable extends Error {
  constructor() {
    super('sessionStorage unavailable');
    this.name = 'SocialStorageUnavailable';
  }
}

export type StartSocialLogin = {
  provider: SocialProvider;
  returnPath: string;
  localePrefix: string;
  agreements: SignUpAgreements | null;
  storage: FlowStorage | null;
  /** `GET /api/v1/auth/oauth/{provider}/authorize-url` 의 dataBody (일반 BFF 프록시) */
  requestAuthorizeUrl: (provider: SocialProvider) => Promise<unknown>;
  /** 제공자 인가 화면으로 이동(`window.location.assign`) */
  navigate: (url: string) => void;
};

/** 인가 URL 을 받아 흐름을 저장하고 제공자로 보낸다. 실패는 ApiError · SocialStorageUnavailable 로 던진다 */
export async function startSocialLogin(input: StartSocialLogin): Promise<void> {
  const issued = readAuthorizeResponse(await input.requestAuthorizeUrl(input.provider));
  if (!issued) throw new ApiError('invalidResponse', 200, null, null);
  const saved = saveSocialFlow(input.storage, {
    provider: input.provider,
    state: issued.state,
    verifier: issued.verifier,
    returnPath: safeReturnPath(input.returnPath),
    localePrefix: input.localePrefix,
    agreements: input.agreements,
  });
  if (!saved) throw new SocialStorageUnavailable();
  input.navigate(issued.authorizeUrl);
}

export type SocialLoginBody = {
  code: string;
  state: string;
  verifier: string;
  agreements?: SignUpAgreements;
};

export type CallbackCheck =
  /** BFF 로 보낸다 */
  | { kind: 'send'; flow: SocialFlow; body: SocialLoginBody }
  /** 제공자가 `error` 로 돌려보냈다(사용자 취소 등) — BE 를 부르지 않는다 */
  | { kind: 'denied'; flow: SocialFlow | null; cancelled: boolean }
  /** 저장한 흐름이 없거나 state · 제공자가 다르거나 code 가 없다 — BE 를 부르지 않는다 */
  | { kind: 'invalid'; flow: SocialFlow | null };

/** 콜백 쿼리를 저장한 흐름과 맞춰 본다 */
export function checkCallback(
  provider: string,
  query: URLSearchParams,
  flow: SocialFlow | null,
): CallbackCheck {
  const error = query.get('error');
  if (error !== null) return { kind: 'denied', flow, cancelled: error === 'access_denied' };
  if (!flow || flow.provider !== provider) return { kind: 'invalid', flow };
  const state = query.get('state');
  if (state === null || state !== flow.state) return { kind: 'invalid', flow };
  const code = query.get('code');
  if (!code || code.length > MAX_CODE_LENGTH) return { kind: 'invalid', flow };
  const body: SocialLoginBody = { code, state, verifier: flow.verifier };
  if (flow.agreements) body.agreements = flow.agreements;
  return { kind: 'send', flow, body };
}

/** 같은 화면의 다른 언어로 넘길 콜백 주소. 지금 언어와 같으면 null */
export function localeHandoffPath(
  flow: SocialFlow | null,
  currentPrefix: string,
  provider: SocialProvider,
  search: string,
): string | null {
  if (!flow || flow.localePrefix === currentPrefix) return null;
  return `${flow.localePrefix}/login/oauth/${provider}${search}`;
}

type SocialTexts = Dictionary['auth'];

/** 소셜 로그인 실패를 다음 화면으로 — 신규 가입 약관 동의(AUTH_056)면 동의 화면, 그 밖은 코드별 문구 */
export function socialFailure(
  cause: unknown,
  t: SocialTexts,
): { kind: 'agreements' } | { kind: 'error'; message: string } {
  if (cause instanceof ApiError && cause.code === 'AUTH_056') return { kind: 'agreements' };
  return { kind: 'error', message: socialErrorText(cause, t, t.social.failed) };
}

/** 시작(authorize-url) 실패 문구 */
export function socialStartErrorText(cause: unknown, t: SocialTexts): string {
  if (cause instanceof SocialStorageUnavailable) return t.social.storageUnavailable;
  return socialErrorText(cause, t, t.social.startFailed);
}

function socialErrorText(cause: unknown, t: SocialTexts, fallback: string): string {
  if (!(cause instanceof ApiError)) return fallback;
  const limited = rateLimitTextOf(cause, t);
  if (limited) return limited;
  const fieldCode = cause.fieldErrors[0]?.code ?? null;
  const code = cause.code ?? fieldCode;
  return (code && t.social.errors[code]) || (fieldCode && t.social.errors[fieldCode]) || fallback;
}

export type CallbackOutcome =
  | { kind: 'success'; flow: SocialFlow }
  /** 신규 가입인데 동의가 없었다(AUTH_056) — 동의를 받아 인가부터 다시 시작한다 */
  | { kind: 'agreements'; flow: SocialFlow }
  | { kind: 'error'; message: string; flow: SocialFlow | null };

/**
 * 콜백 하나를 끝까지 처리한다. state · 제공자가 맞을 때만 BFF 로 보내고(`send`), 결과를 화면 상태로 바꾼다.
 * 저장한 흐름을 지우는 일 · 주소 정리는 부르는 쪽(콜백 화면)이 보내기 전에 한다.
 */
export async function completeSocialLogin(
  provider: SocialProvider,
  query: URLSearchParams,
  flow: SocialFlow | null,
  send: (provider: SocialProvider, body: SocialLoginBody) => Promise<unknown>,
  t: SocialTexts,
): Promise<CallbackOutcome> {
  const check = checkCallback(provider, query, flow);
  if (check.kind === 'denied') {
    return {
      kind: 'error',
      message: check.cancelled ? t.social.cancelled : t.social.failed,
      flow: check.flow,
    };
  }
  if (check.kind === 'invalid')
    return { kind: 'error', message: t.social.invalid, flow: check.flow };
  try {
    await send(provider, check.body);
    return { kind: 'success', flow: check.flow };
  } catch (cause) {
    const failure = socialFailure(cause, t);
    return failure.kind === 'agreements'
      ? { kind: 'agreements', flow: check.flow }
      : { kind: 'error', message: failure.message, flow: check.flow };
  }
}

export type SocialCallbackDeps = {
  provider: SocialProvider;
  /** 지금 화면의 언어 접두어('' 또는 '/en') */
  prefix: string;
  storage: FlowStorage | null;
  location: Pick<Location, 'search' | 'pathname' | 'replace'>;
  history: Pick<History, 'replaceState'>;
  /** BFF 소셜 로그인(`POST /api/v1/auth/oauth/{provider}/login`) */
  send: (provider: SocialProvider, body: SocialLoginBody) => Promise<unknown>;
  /** 앱 경로 → 지금 언어 주소 */
  localize: (path: string) => string;
  /** 로그인 성공 뒤 이동(router.replace + refresh) */
  navigate: (href: string) => void;
  t: SocialTexts;
};

export type SocialCallbackRun =
  { kind: 'handoff' } | Exclude<CallbackOutcome, { kind: 'success' }> | { kind: 'success' };

/**
 * 콜백 화면이 한 번 하는 일(`SocialCallback` 의 effect). 순서가 중요해 한 함수로 둔다.
 * 1. 이미 시작했으면(`guard`, StrictMode effect 이중 실행) 아무것도 하지 않는다 — null.
 * 2. 시작한 언어가 다르면 같은 쿼리로 그 언어의 콜백으로 `location.replace` 만 한다(흐름은 그 화면이 읽어야 하므로 지우지 않는다).
 * 3. 주소에서 쿼리를 지우고(`history.replaceState`, 인가 코드가 기록 · 새로고침에 남지 않게) → 흐름을 지우고 → 보낸다.
 * 4. 성공이면 돌아갈 경로로 이동한다. 그 밖의 결과는 화면이 그린다.
 * 2 · 3 은 첫 await 전이라 호출과 함께 바로 일어난다.
 */
export async function runSocialCallback(
  deps: SocialCallbackDeps,
  guard: { current: boolean },
): Promise<SocialCallbackRun | null> {
  if (guard.current) return null;
  guard.current = true;

  const { provider, storage, location } = deps;
  const flow = readSocialFlow(storage);
  const search = location.search;
  const handoff = localeHandoffPath(flow, deps.prefix, provider, search);
  if (handoff) {
    location.replace(handoff);
    return { kind: 'handoff' };
  }
  deps.history.replaceState(null, '', location.pathname);
  clearSocialFlow(storage);

  const outcome = await completeSocialLogin(
    provider,
    new URLSearchParams(search),
    flow,
    deps.send,
    deps.t,
  );
  if (outcome.kind === 'success') {
    deps.navigate(deps.localize(outcome.flow.returnPath));
    return { kind: 'success' };
  }
  return outcome;
}
