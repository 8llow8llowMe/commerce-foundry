import { describe, expect, it, vi } from 'vitest';
import { ApiError } from './api-error';
import { createEventStreamParser, readEventStream } from './event-stream';

describe('createEventStreamParser', () => {
  it('이름 · 여러 줄 data · id 를 읽고 주석(heartbeat)은 이벤트로 내지 않는다', () => {
    const parser = createEventStreamParser();
    const events = parser.push(
      ': heartbeat\n\nevent: notification\ndata: {"a":1}\nid: 7\n\ndata: x\ndata: y\n\n',
    );
    expect(events).toEqual([
      { event: 'notification', data: '{"a":1}', id: '7' },
      { event: 'message', data: 'x\ny', id: '7' },
    ]);
  });

  it('조각이 줄 · CRLF 중간에서 잘려 와도 끝난 이벤트만 낸다', () => {
    const parser = createEventStreamParser();
    expect(parser.push('event: noti')).toEqual([]);
    expect(parser.push('fication\r')).toEqual([]);
    expect(parser.push('\ndata:{"b":2}\r\n')).toEqual([]);
    expect(parser.push('\r\n')).toEqual([{ event: 'notification', data: '{"b":2}', id: null }]);
  });

  it('data 없는 빈 이벤트는 내지 않고, 맨 앞 BOM 은 버린다', () => {
    const parser = createEventStreamParser();
    expect(parser.push('﻿event: x\n\ndata: ok\n\n')).toEqual([
      { event: 'message', data: 'ok', id: null },
    ]);
  });
});

const encoder = new TextEncoder();
const streamOf = (chunks: string[]) =>
  new ReadableStream<Uint8Array>({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      controller.close();
    },
  });

const sse = (chunks: string[]) =>
  new Response(streamOf(chunks), { headers: { 'content-type': 'text/event-stream' } });

const envelope = (status: number, code: string) =>
  new Response(
    JSON.stringify({
      dataHeader: { success: false, resultCode: code, resultMessage: null, fieldErrors: null },
      dataBody: null,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

describe('readEventStream', () => {
  it('열리면 onOpen 을 부르고 이벤트를 넘긴 뒤 서버가 닫으면 정상 종료한다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValue(
        sse([
          'event: notification\ndata: 1\n\n',
          ': heartbeat\n\n',
          'event: notification\ndata: 2\n\n',
        ]),
      );
    const onOpen = vi.fn();
    const onEvent = vi.fn();

    await readEventStream('/api/v1/x/stream', {
      signal: new AbortController().signal,
      onOpen,
      onEvent,
      fetch: fetchImpl,
    });

    expect(onOpen).toHaveBeenCalledOnce();
    expect(onEvent.mock.calls.map(([e]) => e.data)).toEqual(['1', '2']);
    const init = fetchImpl.mock.calls[0]![1]!;
    expect(new Headers(init.headers).get('accept')).toBe('text/event-stream');
    expect(init.credentials).toBe('same-origin');
  });

  it.each([
    [envelope(401, 'SECURITY_001'), 'unauthorized'],
    [envelope(403, 'SECURITY_002'), 'forbidden'],
    [new Response('nope', { status: 401 }), 'unauthorized'],
    [
      new Response('{}', { status: 200, headers: { 'content-type': 'application/json' } }),
      'invalidResponse',
    ],
  ])('스트림이 아닌 응답은 ApiError 다 (%#)', async (response, kind) => {
    const onOpen = vi.fn();
    const error = await readEventStream('/s', {
      signal: new AbortController().signal,
      onOpen,
      onEvent: vi.fn(),
      fetch: vi.fn<typeof fetch>().mockResolvedValue(response),
    }).catch((cause: unknown) => cause);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).kind).toBe(kind);
    expect(onOpen).not.toHaveBeenCalled();
  });

  it('연결 실패는 network, 직접 끊으면 던지지 않는다', async () => {
    const failing = vi.fn<typeof fetch>().mockRejectedValue(new TypeError('fetch failed'));
    await expect(
      readEventStream('/s', {
        signal: new AbortController().signal,
        onEvent: vi.fn(),
        fetch: failing,
      }),
    ).rejects.toMatchObject({ kind: 'network' });

    const controller = new AbortController();
    controller.abort();
    await expect(
      readEventStream('/s', { signal: controller.signal, onEvent: vi.fn(), fetch: failing }),
    ).resolves.toBeUndefined();
  });

  it('읽는 중 끊기면 network 다', async () => {
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(encoder.encode('data: 1\n\n'));
        controller.error(new TypeError('terminated'));
      },
    });
    const onEvent = vi.fn();
    await expect(
      readEventStream('/s', {
        signal: new AbortController().signal,
        onEvent,
        fetch: vi
          .fn<typeof fetch>()
          .mockResolvedValue(
            new Response(body, { headers: { 'content-type': 'text/event-stream' } }),
          ),
      }),
    ).rejects.toMatchObject({ kind: 'network' });
  });
});
