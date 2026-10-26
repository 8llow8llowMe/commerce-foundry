import { errorEnvelope } from '../envelope';
import { resolveClientIp, setClientIp } from './client-ip';
import { readCookie } from './cookies';
import { forwardHeaders, relay, type ProxyOptions } from './proxy';
import { joinUrl } from './url';

export type EventStreamProxyOptions = ProxyOptions & {
  /** 게이트웨이의 SSE 경로 하나(`/api/v1/...`). 요청 주소가 아니라 이 값으로만 보낸다 — 열린 중계가 되지 않게 */
  path: string;
};

/**
 * 브라우저 → Next → 게이트웨이 SSE 중계(`text/event-stream`). 일반 프록시(`createProxyHandler`)와 다른 점:
 *
 * - **본문을 모으지 않고 흘린다** — 게이트웨이 응답 본문을 그대로 넘기고(맨 앞에 SSE 주석 `: connected` 한 줄을 붙여 헤더를 바로 내보낸다), 중간 단계가 모으지 못하게
 *   `Cache-Control: no-cache, no-transform`(Next 의 gzip 압축이 건너뛴다) · `X-Accel-Buffering: no`(nginx 버퍼링 끔)를 붙인다.
 * - **브라우저가 끊으면 게이트웨이 연결도 끊는다** — 요청의 `signal` 을 게이트웨이 요청에 넘긴다(서버 쪽 구독이 남지 않게).
 * - **GET 하나만, 재발급 · 재시도 없이** 보낸다. 401 · 403 · 그 밖의 실패는 봉투 그대로 중계한다 — 다시 열지 · 따라잡을지는 화면이 정한다
 *   (refresh 쿠키가 있는 앱이라도 따라잡기 목록 요청이 일반 프록시에서 재발급한다).
 * - 연결 실패는 BFF_002 503 이다.
 */
export function createEventStreamProxyHandler(
  options: EventStreamProxyOptions,
): (request: Request) => Promise<Response> {
  const fetchImpl = options.fetch ?? fetch;
  const target = joinUrl(options.gatewayUrl, options.path);

  return async (request) => {
    if (request.method !== 'GET') {
      return Response.json(errorEnvelope('BFF_001', '존재하지 않는 경로입니다.'), { status: 404 });
    }
    const accessToken = readCookie(request.headers.get('cookie'), options.cookies.accessName);
    const headers = setClientIp(
      forwardHeaders(new Headers({ accept: 'text/event-stream' }), accessToken),
      resolveClientIp(request, options.clientIpHeader),
    );

    let upstream: Response;
    try {
      upstream = await fetchImpl(target, {
        method: 'GET',
        headers,
        redirect: 'manual',
        cache: 'no-store',
        signal: request.signal,
      });
    } catch {
      return Response.json(
        errorEnvelope('BFF_002', '서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'),
        { status: 503 },
      );
    }

    const type = upstream.headers.get('content-type') ?? '';
    if (!upstream.ok || !type.toLowerCase().startsWith('text/event-stream') || !upstream.body) {
      return relay(upstream, []);
    }
    // 연결 직후 주석 한 줄을 먼저 보낸다 — Next 는 본문 첫 바이트가 나올 때까지 응답 헤더를 보내지 않아,
    // 그대로 두면 게이트웨이 첫 heartbeat(5초)까지 브라우저가 연결됐는지 모른다(다시 연결 뒤 따라잡기가 그만큼 늦는다)
    const opened = new TextEncoder().encode(': connected\n\n');
    const body = upstream.body.pipeThrough(
      new TransformStream<Uint8Array, Uint8Array>({
        start: (controller) => controller.enqueue(opened),
      }),
    );
    return new Response(body, {
      status: 200,
      headers: {
        'content-type': 'text/event-stream; charset=utf-8',
        'cache-control': 'no-cache, no-transform',
        'x-accel-buffering': 'no',
      },
    });
  };
}
