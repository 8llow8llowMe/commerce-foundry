import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api-error';
import { unwrap } from '../unwrap';
import type { AuthCookieConfig } from './cookies';
import {
  ADMIN_LOGIN_OTP_PATH,
  ADMIN_LOGIN_PATH,
  LOGIN_PATH,
  createAdminLoginHandler,
  createAdminLoginOtpHandler,
  createLoginHandler,
  readOtpChallenge,
} from './login';

const GATEWAY = 'http://gateway.test:8000';
const COOKIES: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 1800,
  refreshMaxAgeSec: 1209600,
  secure: false,
};

const envelope = (status: number, success: boolean, body: unknown, code: string | null = null) =>
  new Response(
    JSON.stringify({
      dataHeader: { success, resultCode: code, resultMessage: null, fieldErrors: null },
      dataBody: body,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

const TOKENS = {
  accessToken: 'access-1',
  accessTokenExpiresIn: 900,
  refreshToken: 'refresh-1',
  refreshTokenExpiresIn: 3600,
};

const login = (headers: Record<string, string> = { 'sec-fetch-site': 'same-origin' }) =>
  new Request(`http://localhost:3000${LOGIN_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'accept-language': 'en', ...headers },
    body: JSON.stringify({ email: 'a@test.com', password: 'pw' }),
  });

const handlerWith = (fetchImpl: typeof fetch) =>
  createLoginHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

describe('createLoginHandler', () => {
  it('성공하면 토큰을 서버가 준 수명의 httpOnly 쿠키로 심고, 본문에서는 토큰을 뺀다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    const res = await handlerWith(fetchImpl)(login());

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = await res.json();
    expect(body.dataBody).toBeNull();
    expect(JSON.stringify(body)).not.toContain('access-1');

    const cookies = res.headers.getSetCookie();
    expect(cookies).toEqual([
      'cf_at=access-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=900',
      'cf_rt=refresh-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600',
    ]);
  });

  it('게이트웨이에는 로그인 경로 · 본문 · 언어만 넘기고 쿠키는 넘기지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    await handlerWith(fetchImpl)(login({ 'sec-fetch-site': 'same-origin', cookie: 'cf_at=old' }));

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(`${GATEWAY}${LOGIN_PATH}`);
    const headers = new Headers(init?.headers);
    expect(headers.get('accept-language')).toBe('en');
    expect(headers.get('cookie')).toBeNull();
    expect(headers.get('authorization')).toBeNull();
    expect(new TextDecoder().decode(init?.body as ArrayBuffer)).toContain('a@test.com');
  });

  it('수명이 없으면 앱 설정값을 쓴다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(200, true, { accessToken: 'a', refreshToken: 'r' }));
    const cookies = (await handlerWith(fetchImpl)(login())).headers.getSetCookie();
    expect(cookies[0]).toContain('Max-Age=1800');
    expect(cookies[1]).toContain('Max-Age=1209600');
  });

  it('로그인 실패(400 AUTH_001)는 상태 · 봉투를 그대로 중계하고 쿠키를 건드리지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(400, false, null, 'AUTH_001'));
    const res = await handlerWith(fetchImpl)(login());

    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'AUTH_001' } });
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('시도 제한(429 AUTH_040)은 상태 · 봉투와 Retry-After 를 함께 중계한다 — 화면 쿨다운이 그 값을 쓴다', async () => {
    const limited = envelope(429, false, null, 'AUTH_040');
    limited.headers.set('retry-after', '840');
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(limited);
    const res = await handlerWith(fetchImpl)(login());

    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('840');
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'AUTH_040' } });
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('브라우저 쪽 unwrap 이 그 응답을 retryAfterSec 이 채워진 rateLimited ApiError 로 읽는다', async () => {
    const limited = envelope(429, false, null, 'AUTH_040');
    limited.headers.set('retry-after', '840');
    const res = await handlerWith(vi.fn<typeof fetch>().mockResolvedValue(limited))(login());

    const error = await unwrap(res).catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind: 'rateLimited', code: 'AUTH_040', retryAfterSec: 840 });
  });

  it('다른 사이트에서 온 요청은 게이트웨이로 보내지 않는다(login CSRF)', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(login({ 'sec-fetch-site': 'cross-site' }));

    expect(res.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('연결 실패 · 봉투 아님 · 토큰 없는 성공은 BFF_002 503 이고 쿠키를 심지 않는다', async () => {
    for (const fetchImpl of [
      vi.fn<typeof fetch>().mockRejectedValue(new Error('down')),
      vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>', { status: 502 })),
      vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, { accessToken: 'a' })),
    ]) {
      const res = await handlerWith(fetchImpl)(login());
      expect(res.status).toBe(503);
      expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });
      expect(res.headers.getSetCookie()).toEqual([]);
    }
  });

  it('브라우저가 보낸 X-Forwarded-For 는 버리고 클라이언트 IP 한 값으로 덮어쓴다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, TOKENS));
    await handlerWith(fetchImpl)(
      login({
        'sec-fetch-site': 'same-origin',
        'x-forwarded-for': '1.2.3.4',
        'x-real-ip': '203.0.113.7',
      }),
    );

    expect(new Headers(fetchImpl.mock.calls[0]![1]?.headers).get('x-forwarded-for')).toBe(
      '203.0.113.7',
    );
  });
});

describe('createAdminLoginHandler', () => {
  const ADMIN_COOKIES: AuthCookieConfig = {
    accessName: 'cf_admin_at',
    refreshName: null,
    accessMaxAgeSec: 1800,
    secure: false,
  };
  const adminLogin = (headers: Record<string, string> = { 'sec-fetch-site': 'same-origin' }) =>
    new Request(`http://localhost:3001${ADMIN_LOGIN_PATH}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...headers },
      body: JSON.stringify({ email: 'admin@test.com', password: 'pw' }),
    });
  const adminHandlerWith = (fetchImpl: typeof fetch) =>
    createAdminLoginHandler({ gatewayUrl: GATEWAY, cookies: ADMIN_COOKIES, fetch: fetchImpl });

  it('관리자 로그인 경로를 부르고 access 쿠키 하나만 서버가 준 수명으로 심는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        envelope(200, true, { accessToken: 'admin-1', accessTokenExpiresIn: 1200 }),
      );
    const res = await adminHandlerWith(fetchImpl)(adminLogin());

    expect(fetchImpl.mock.calls[0]![0]).toBe(`${GATEWAY}${ADMIN_LOGIN_PATH}`);
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = await res.json();
    expect(body.dataBody).toBeNull();
    expect(JSON.stringify(body)).not.toContain('admin-1');
    expect(res.headers.getSetCookie()).toEqual([
      'cf_admin_at=admin-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=1200',
    ]);
  });

  it('응답에 수명이 없으면 앱 설정 수명을 쓴다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(200, true, { accessToken: 'admin-1' }));
    const res = await adminHandlerWith(fetchImpl)(adminLogin());

    expect(res.headers.getSetCookie()).toEqual([
      'cf_admin_at=admin-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=1800',
    ]);
  });

  it('AUTH_030 은 봉투와 400 을 그대로 중계하고 쿠키를 심지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(400, false, null, 'AUTH_030'));
    const res = await adminHandlerWith(fetchImpl)(adminLogin());

    expect(res.status).toBe(400);
    expect(res.headers.getSetCookie()).toEqual([]);
    await expect(unwrap(res)).rejects.toMatchObject({ code: 'AUTH_030' });
  });

  it('시도 제한(429 AUTH_040)은 상태 · 봉투 · Retry-After 를 중계하고 쿠키를 심지 않는다(#407)', async () => {
    const limited = envelope(429, false, null, 'AUTH_040');
    limited.headers.set('retry-after', '840');
    const res = await adminHandlerWith(vi.fn<typeof fetch>().mockResolvedValue(limited))(
      adminLogin(),
    );

    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('840');
    expect(res.headers.getSetCookie()).toEqual([]);
    await expect(unwrap(res)).rejects.toMatchObject({
      kind: 'rateLimited',
      code: 'AUTH_040',
      retryAfterSec: 840,
    });
  });

  it('시도 계수기 장애(503 AUTH_013)는 봉투와 503 을 그대로 중계한다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(503, false, null, 'AUTH_013'));
    const res = await adminHandlerWith(fetchImpl)(adminLogin());

    expect(res.status).toBe(503);
    expect(res.headers.getSetCookie()).toEqual([]);
    await expect(unwrap(res)).rejects.toMatchObject({ kind: 'unavailable', code: 'AUTH_013' });
  });

  it('성공인데 access 토큰이 없으면 로그인된 척하지 않고 BFF_002 503 이다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, {}));
    const res = await adminHandlerWith(fetchImpl)(adminLogin());

    expect(res.status).toBe(503);
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('다른 사이트에서 온 요청은 게이트웨이로 보내지 않는다(login CSRF)', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await adminHandlerWith(fetchImpl)(adminLogin({ 'sec-fetch-site': 'cross-site' }));

    expect(res.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});

const OTP_ADMIN_COOKIES: AuthCookieConfig = {
  accessName: 'cf_admin_at',
  refreshName: null,
  accessMaxAgeSec: 1800,
  secure: false,
};
const CHALLENGE = 'challenge-abcdefghijklmnopqrstuvwxyz0123456';
const OTP_REQUIRED = {
  accessToken: null,
  accessTokenExpiresIn: null,
  otpRequired: true,
  challengeToken: CHALLENGE,
  challengeExpiresIn: 300,
};

describe('createAdminLoginHandler — OTP 등록 관리자(#414)', () => {
  const adminLogin = () =>
    new Request(`http://localhost:3001${ADMIN_LOGIN_PATH}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'sec-fetch-site': 'same-origin' },
      body: JSON.stringify({ email: 'admin@test.com', password: 'pw' }),
    });
  const handlerWith = (fetchImpl: typeof fetch) =>
    createAdminLoginHandler({ gatewayUrl: GATEWAY, cookies: OTP_ADMIN_COOKIES, fetch: fetchImpl });

  it('otpRequired=true 면 쿠키를 심지 않고 챌린지 세 칸만 no-store 로 넘긴다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(200, true, { ...OTP_REQUIRED, extra: 'x' }));
    const res = await handlerWith(fetchImpl)(adminLogin());

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.getSetCookie()).toEqual([]);
    expect((await res.json()).dataBody).toEqual({
      otpRequired: true,
      challengeToken: CHALLENGE,
      challengeExpiresIn: 300,
    });
  });

  it('OTP 미등록 관리자(otpRequired=false + 토큰)는 지금처럼 쿠키를 심고 본문은 null 이다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      envelope(200, true, {
        accessToken: 'admin-1',
        accessTokenExpiresIn: 1200,
        otpRequired: false,
        challengeToken: null,
        challengeExpiresIn: null,
      }),
    );
    const res = await handlerWith(fetchImpl)(adminLogin());

    expect(res.status).toBe(200);
    expect((await res.json()).dataBody).toBeNull();
    expect(res.headers.getSetCookie()).toEqual([
      'cf_admin_at=admin-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=1200',
    ]);
  });

  it('otpRequired=true 인데 챌린지가 비었거나 수명이 없으면 BFF_002 503 이다', async () => {
    for (const body of [
      { ...OTP_REQUIRED, challengeToken: '' },
      { ...OTP_REQUIRED, challengeToken: null },
      { ...OTP_REQUIRED, challengeExpiresIn: null },
      { ...OTP_REQUIRED, challengeExpiresIn: 0 },
    ]) {
      const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, body));
      const res = await handlerWith(fetchImpl)(adminLogin());
      expect(res.status).toBe(503);
      expect(res.headers.getSetCookie()).toEqual([]);
      expect(JSON.stringify(await res.json())).not.toContain(CHALLENGE);
    }
  });
});

describe('readOtpChallenge', () => {
  it('토큰이 함께 오면 챌린지로 읽지 않는다 — 토큰을 브라우저에 넘기지 않게', () => {
    expect(readOtpChallenge({ ...OTP_REQUIRED, accessToken: 'leak' })).toBeNull();
    expect(readOtpChallenge({ ...OTP_REQUIRED, otpRequired: 'true' })).toBeNull();
    expect(readOtpChallenge(null)).toBeNull();
  });
});

describe('createAdminLoginOtpHandler', () => {
  const otpLogin = (headers: Record<string, string> = { 'sec-fetch-site': 'same-origin' }) =>
    new Request(`http://localhost:3001${ADMIN_LOGIN_OTP_PATH}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...headers },
      body: JSON.stringify({ challengeToken: CHALLENGE, code: '123456' }),
    });
  const handlerWith = (fetchImpl: typeof fetch) =>
    createAdminLoginOtpHandler({
      gatewayUrl: GATEWAY,
      cookies: OTP_ADMIN_COOKIES,
      fetch: fetchImpl,
    });

  it('2단계 경로로 본문을 넘기고, 성공하면 access 쿠키만 심고 본문에서 토큰을 뺀다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(
      envelope(200, true, {
        accessToken: 'admin-otp-1',
        accessTokenExpiresIn: 1200,
        otpRequired: false,
        challengeToken: null,
        challengeExpiresIn: null,
      }),
    );
    const res = await handlerWith(fetchImpl)(
      otpLogin({ 'sec-fetch-site': 'same-origin', cookie: 'x=1' }),
    );

    const [url, init] = fetchImpl.mock.calls[0]!;
    expect(url).toBe(`${GATEWAY}${ADMIN_LOGIN_OTP_PATH}`);
    expect(new Headers(init?.headers).get('cookie')).toBeNull();
    expect(new TextDecoder().decode(init?.body as ArrayBuffer)).toContain(CHALLENGE);

    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = await res.json();
    expect(body.dataBody).toBeNull();
    expect(JSON.stringify(body)).not.toContain('admin-otp-1');
    expect(res.headers.getSetCookie()).toEqual([
      'cf_admin_at=admin-otp-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=1200',
    ]);
  });

  it.each([
    [400, 'AUTH_034', 'server'],
    [400, 'AUTH_035', 'server'],
    [409, 'AUTH_044', 'conflict'],
    [503, 'AUTH_039', 'unavailable'],
  ])('%s %s 는 봉투와 상태를 그대로 중계하고 쿠키를 심지 않는다', async (status, code, kind) => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(status, false, null, code));
    const res = await handlerWith(fetchImpl)(otpLogin());

    expect(res.status).toBe(status);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(res.headers.getSetCookie()).toEqual([]);
    await expect(unwrap(res)).rejects.toMatchObject({ code, kind });
  });

  it('시도 제한(429 AUTH_043)은 Retry-After 까지 중계한다', async () => {
    const limited = envelope(429, false, null, 'AUTH_043');
    limited.headers.set('retry-after', '600');
    const res = await handlerWith(vi.fn<typeof fetch>().mockResolvedValue(limited))(otpLogin());

    expect(res.status).toBe(429);
    expect(res.headers.get('retry-after')).toBe('600');
    await expect(unwrap(res)).rejects.toMatchObject({
      kind: 'rateLimited',
      code: 'AUTH_043',
      retryAfterSec: 600,
    });
  });

  it('2단계 성공인데 토큰이 없으면(다시 otpRequired 여도) BFF_002 503 이고 챌린지를 넘기지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValue(envelope(200, true, OTP_REQUIRED));
    const res = await handlerWith(fetchImpl)(otpLogin());

    expect(res.status).toBe(503);
    expect(res.headers.getSetCookie()).toEqual([]);
    expect(JSON.stringify(await res.json())).not.toContain(CHALLENGE);
  });

  it('다른 사이트에서 온 요청은 게이트웨이로 보내지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(otpLogin({ 'sec-fetch-site': 'cross-site' }));

    expect(res.status).toBe(403);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(fetchImpl).not.toHaveBeenCalled();
  });
});
