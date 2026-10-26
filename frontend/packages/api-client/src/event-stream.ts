import { ApiError } from './api-error';
import { unwrap } from './unwrap';

/** SSE 이벤트 한 건. 이름이 없으면 `message` 다. 주석(`: heartbeat`)은 이벤트가 아니다 */
export type ServerSentEvent = { event: string; data: string; id: string | null };

/**
 * `text/event-stream` 본문을 이벤트로 나눈다(WHATWG HTML §9.2 의 해석 규칙 중 이 저장소가 쓰는 부분).
 * 조각(chunk)이 줄 · 이벤트 중간에서 잘려 와도 된다 — `push` 로 넣은 만큼 끝난 이벤트만 돌려준다.
 * `retry` 는 무시한다 — 다시 연결하는 간격은 호출자가 정한다.
 */
export function createEventStreamParser(): { push: (chunk: string) => ServerSentEvent[] } {
  let buffer = '';
  let event = '';
  let data: string[] = [];
  let id: string | null = null;
  let started = false;

  const line = (text: string, out: ServerSentEvent[]) => {
    if (text === '') {
      if (data.length > 0) out.push({ event: event || 'message', data: data.join('\n'), id });
      event = '';
      data = [];
      return;
    }
    if (text.startsWith(':')) return;
    const colon = text.indexOf(':');
    const field = colon < 0 ? text : text.slice(0, colon);
    let value = colon < 0 ? '' : text.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') event = value;
    else if (field === 'data') data.push(value);
    else if (field === 'id' && !value.includes('\u0000')) id = value;
  };

  return {
    push(chunk) {
      buffer += chunk;
      // 스트림 맨 앞의 BOM 은 버린다
      if (!started && buffer.length > 0) {
        started = true;
        if (buffer.charCodeAt(0) === 0xfeff) buffer = buffer.slice(1);
      }
      const out: ServerSentEvent[] = [];
      // 줄 끝은 CRLF · LF · CR 셋 다 받는다. 끝의 CR 은 다음 조각의 LF 와 짝일 수 있어 남겨 둔다
      for (;;) {
        const match = /\r\n|\n|\r/.exec(buffer);
        if (!match) break;
        if (match[0] === '\r' && match.index === buffer.length - 1) break;
        line(buffer.slice(0, match.index), out);
        buffer = buffer.slice(match.index + match[0].length);
      }
      return out;
    },
  };
}

export type EventStreamOptions = {
  /** 연결을 끊는다(화면 이탈 · 탭 숨김). 끊으면 `readEventStream` 은 조용히 끝난다 */
  signal: AbortSignal;
  /** 200 `text/event-stream` 응답을 받았다 — 연결됨 */
  onOpen?: () => void;
  onEvent: (event: ServerSentEvent) => void;
  fetch?: typeof fetch;
};

/**
 * 같은 origin BFF 의 SSE 경로를 `fetch` 로 연다(`EventSource` 를 쓰지 않는다 — 401 · 403 상태를 알 수 없고 스스로 다시 연결해서다).
 * 서버가 스트림을 닫으면 정상 종료로 돌아오고, 다시 열지는 호출자가 정한다.
 *
 * - 200 이 아니거나 `text/event-stream` 이 아니면 봉투를 읽어 `ApiError` 를 던진다(401 `unauthorized` · 403 `forbidden` 등).
 * - 연결 실패 · 읽는 중 끊김은 `ApiError('network')` 다.
 * - `signal` 로 끊으면 던지지 않고 끝난다.
 */
export async function readEventStream(path: string, options: EventStreamOptions): Promise<void> {
  const { signal } = options;
  let response: Response;
  try {
    response = await (options.fetch ?? fetch)(path, {
      headers: { accept: 'text/event-stream' },
      credentials: 'same-origin',
      cache: 'no-store',
      signal,
    });
  } catch {
    if (signal.aborted) return;
    throw new ApiError('network', null, null, null);
  }

  const type = response.headers.get('content-type') ?? '';
  if (!response.ok || !type.toLowerCase().startsWith('text/event-stream') || !response.body) {
    if (signal.aborted) return;
    // 실패 응답은 봉투다. 봉투가 아닌 200(잘못된 중계)도 unwrap 이 invalidResponse 로 가른다
    if (response.ok) {
      await response.body?.cancel();
      throw new ApiError('invalidResponse', response.status, null, null);
    }
    try {
      await unwrap<unknown>(response);
    } catch (cause) {
      // 봉투가 아닌 401 · 403(앞단 프록시가 만든 응답)도 상태로 가른다
      const { status } = response;
      if (cause instanceof ApiError && cause.kind === 'invalidResponse') {
        if (status === 401) throw new ApiError('unauthorized', status, null, null);
        if (status === 403) throw new ApiError('forbidden', status, null, null);
      }
      throw cause;
    }
    throw new ApiError('invalidResponse', response.status, null, null);
  }

  options.onOpen?.();
  const parser = createEventStreamParser();
  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return;
      for (const event of parser.push(value)) options.onEvent(event);
    }
  } catch {
    if (signal.aborted) return;
    throw new ApiError('network', null, null, null);
  } finally {
    reader.releaseLock();
  }
}
