import { describe, expect, it, vi } from 'vitest';
import type { AuthCookieConfig } from './cookies';
import { LOGIN_PATH } from './login';
import { SIGN_UP_PATH, createSignUpHandler } from './sign-up';

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

const SIGN_UP_BODY = {
  email: 'a@test.com',
  password: 'abcd1234',
  name: '홍길동',
  phone: null,
  agreements: { termsOfService: true, privacyPolicy: true, marketing: false },
};

const signUp = (headers: Record<string, string> = { 'sec-fetch-site': 'same-origin' }) =>
  new Request(`http://localhost:3000${SIGN_UP_PATH}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', 'accept-language': 'en', ...headers },
    body: JSON.stringify(SIGN_UP_BODY),
  });

const handlerWith = (fetchImpl: typeof fetch) =>
  createSignUpHandler({ gatewayUrl: GATEWAY, cookies: COOKIES, fetch: fetchImpl });

const bodyOf = (init: RequestInit | undefined) =>
  typeof init?.body === 'string' ? init.body : new TextDecoder().decode(init?.body as ArrayBuffer);

describe('createSignUpHandler', () => {
  it('가입이 되면 같은 이메일 · 비밀번호로 로그인해 쿠키를 심고 201 signedIn true 를 돌려준다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, true, { memberId: '7' }))
      .mockResolvedValueOnce(envelope(200, true, TOKENS));
    const res = await handlerWith(fetchImpl)(signUp());

    expect(res.status).toBe(201);
    expect(res.headers.get('cache-control')).toBe('no-store');
    const body = await res.json();
    expect(body.dataBody).toEqual({ signedIn: true });
    expect(JSON.stringify(body)).not.toContain('access-1');
    expect(res.headers.getSetCookie()).toEqual([
      'cf_at=access-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=900',
      'cf_rt=refresh-1; Path=/; HttpOnly; SameSite=Lax; Max-Age=3600',
    ]);

    const [signUpUrl, signUpInit] = fetchImpl.mock.calls[0]!;
    expect(signUpUrl).toBe(`${GATEWAY}${SIGN_UP_PATH}`);
    expect(JSON.parse(bodyOf(signUpInit))).toEqual(SIGN_UP_BODY);

    const [loginUrl, loginInit] = fetchImpl.mock.calls[1]!;
    expect(loginUrl).toBe(`${GATEWAY}${LOGIN_PATH}`);
    // 로그인에는 두 칸만 — 이름 · 연락처 · 동의는 넘기지 않는다
    expect(JSON.parse(bodyOf(loginInit))).toEqual({ email: 'a@test.com', password: 'abcd1234' });
  });

  it('게이트웨이에는 쿠키 · 인증 헤더를 넘기지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, true, { memberId: '7' }))
      .mockResolvedValueOnce(envelope(200, true, TOKENS));
    await handlerWith(fetchImpl)(signUp({ 'sec-fetch-site': 'same-origin', cookie: 'cf_at=old' }));

    for (const [, init] of fetchImpl.mock.calls) {
      const headers = new Headers(init?.headers);
      expect(headers.get('accept-language')).toBe('en');
      expect(headers.get('cookie')).toBeNull();
      expect(headers.get('authorization')).toBeNull();
    }
  });

  it('가입 실패(409 AUTH_002 · 검증)는 상태 · 봉투를 그대로 중계하고 로그인하지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(envelope(409, false, null, 'AUTH_002'));
    const res = await handlerWith(fetchImpl)(signUp());

    expect(res.status).toBe(409);
    expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'AUTH_002' } });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it('가입은 됐는데 자동 로그인이 실패하면 201 signedIn false — 가입 실패로 보이지 않게', async () => {
    for (const loginResult of [
      () => Promise.resolve(envelope(400, false, null, 'AUTH_001')),
      () => Promise.reject(new Error('down')),
      () => Promise.resolve(envelope(200, true, { accessToken: 'a' })),
    ]) {
      const fetchImpl = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(envelope(201, true, { memberId: '7' }))
        .mockImplementationOnce(loginResult);
      const res = await handlerWith(fetchImpl)(signUp());

      expect(res.status).toBe(201);
      expect((await res.json()).dataBody).toEqual({ signedIn: false });
      expect(res.headers.getSetCookie()).toEqual([]);
    }
  });

  it('다른 사이트에서 온 요청은 게이트웨이로 보내지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const res = await handlerWith(fetchImpl)(signUp({ 'sec-fetch-site': 'cross-site' }));

    expect(res.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('가입 연결 실패 · 봉투 아님은 BFF_002 503 이다', async () => {
    for (const fetchImpl of [
      vi.fn<typeof fetch>().mockRejectedValue(new Error('down')),
      vi.fn<typeof fetch>().mockResolvedValue(new Response('<html>', { status: 502 })),
    ]) {
      const res = await handlerWith(fetchImpl)(signUp());
      expect(res.status).toBe(503);
      expect(await res.json()).toMatchObject({ dataHeader: { resultCode: 'BFF_002' } });
    }
  });

  it('가입과 이어지는 로그인 모두 X-Forwarded-For 를 클라이언트 IP 한 값으로 보낸다', async () => {
    const fetchImpl = vi.fn<typeof fetch>(async (input) =>
      String(input).endsWith(SIGN_UP_PATH)
        ? envelope(201, true, { memberId: '1' })
        : envelope(200, true, TOKENS),
    );
    await handlerWith(fetchImpl)(
      signUp({
        'sec-fetch-site': 'same-origin',
        'x-forwarded-for': '1.2.3.4',
        'x-real-ip': '203.0.113.7',
      }),
    );

    expect(fetchImpl).toHaveBeenCalledTimes(2);
    for (const [, init] of fetchImpl.mock.calls) {
      expect(new Headers(init?.headers).get('x-forwarded-for')).toBe('203.0.113.7');
    }
  });
});
