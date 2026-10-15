import { describe, expect, it } from 'vitest';
import { ApiError } from './api-error';
import { unwrap } from './unwrap';

const envelope = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

describe('unwrap', () => {
  it('성공이면 dataBody 를 돌려준다', async () => {
    const res = envelope(200, {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: { id: 1 },
    });
    await expect(unwrap(res)).resolves.toEqual({ id: 1 });
  });

  it('성공 + dataBody null 은 null 이다 (선택적 하위 리소스 부재)', async () => {
    const res = envelope(200, {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: null,
    });
    await expect(unwrap(res)).resolves.toBeNull();
  });

  it('204 No Content 는 null 로 성공 처리한다', async () => {
    await expect(unwrap(new Response(null, { status: 204 }))).resolves.toBeNull();
  });

  it('실패 봉투는 ApiError 로 던진다', async () => {
    const res = envelope(404, {
      dataHeader: {
        success: false,
        resultCode: 'ORDER_001',
        resultMessage: '존재하지 않는 주문입니다.',
        fieldErrors: null,
      },
      dataBody: null,
    });
    const error = await unwrap(res).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: 'notFound',
      status: 404,
      code: 'ORDER_001',
      message: '존재하지 않는 주문입니다.',
    });
  });

  it('fieldErrors 를 그대로 싣는다', async () => {
    const fieldErrors = [{ code: 'MEMBER_103', field: 'phone', message: '형식 오류' }];
    const res = envelope(400, {
      dataHeader: {
        success: false,
        resultCode: 'MEMBER_103',
        resultMessage: '형식 오류',
        fieldErrors,
      },
      dataBody: null,
    });
    await expect(unwrap(res)).rejects.toMatchObject({ kind: 'validation', fieldErrors });
  });

  it('봉투가 아닌 JSON 은 invalidResponse 다', async () => {
    await expect(unwrap(envelope(403, { message: 'nope' }))).rejects.toMatchObject({
      kind: 'invalidResponse',
      status: 403,
    });
  });

  it('JSON 이 아닌 본문은 invalidResponse 다', async () => {
    await expect(unwrap(new Response('<html>', { status: 500 }))).rejects.toMatchObject({
      kind: 'invalidResponse',
      status: 500,
    });
  });

  it('success=true 인데 HTTP 오류 상태면 실패로 본다', async () => {
    const res = envelope(500, {
      dataHeader: { success: true, resultCode: null, resultMessage: null, fieldErrors: null },
      dataBody: null,
    });
    await expect(unwrap(res)).rejects.toMatchObject({ kind: 'server', status: 500 });
  });
});
