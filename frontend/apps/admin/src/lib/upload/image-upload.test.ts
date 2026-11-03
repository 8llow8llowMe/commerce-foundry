import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import {
  CONFIRM_CHANGED,
  CONFIRM_GONE,
  CONFIRM_SESSION_EXPIRED,
  CONFIRM_UNKNOWN,
  confirmDisplayImage,
  confirmFailure,
  DISPLAY_IMAGE_UPLOAD_PATH,
  uploadDisplayImage,
  checkImageFile,
  formatBytes,
  IMAGE_EMPTY,
  IMAGE_FORMAT_NOT_ALLOWED,
  IMAGE_MAX_BYTES,
  IMAGE_SIZE_NOT_ALLOWED,
  PRODUCT_IMAGE_UPLOAD_PATH,
  UPLOAD_DISABLED,
  UPLOAD_FAILED,
  UPLOAD_SESSION_EXPIRED,
  UPLOAD_UNAVAILABLE,
  uploadFailure,
  uploadImage,
  usableTicket,
} from './image-upload';

const ticket = {
  uploadKey: 'uploads/100/3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg',
  uploadUrl: 'https://storage.example.com/bucket/uploads/100/x.jpg?X-Amz-Signature=abc',
  method: 'PUT',
  headers: { 'Content-Type': 'image/jpeg' },
  expiresAt: '2026-11-01T03:05:00Z',
};

const envelope = (status: number, body: unknown, resultCode: string | null = null) =>
  new Response(
    JSON.stringify({
      dataHeader: {
        success: status < 400,
        resultCode,
        resultMessage: status < 400 ? null : '서버 문구',
        fieldErrors: null,
      },
      dataBody: body,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

const jpeg = (size = 1024) => new File([new Uint8Array(size)], 'a.jpg', { type: 'image/jpeg' });
const options = { issuePath: PRODUCT_IMAGE_UPLOAD_PATH };

describe('checkImageFile', () => {
  it('JPEG · PNG · WebP 와 1B ~ 10MB 만 통과한다', () => {
    expect(checkImageFile({ type: 'image/png', size: 1 })).toBeNull();
    expect(checkImageFile({ type: 'image/webp', size: IMAGE_MAX_BYTES })).toBeNull();
    expect(checkImageFile({ type: 'image/gif', size: 10 })).toBe(IMAGE_FORMAT_NOT_ALLOWED);
    expect(checkImageFile({ type: '', size: 10 })).toBe(IMAGE_FORMAT_NOT_ALLOWED);
    expect(checkImageFile({ type: 'image/jpeg', size: 0 })).toBe(IMAGE_EMPTY);
    expect(checkImageFile({ type: 'image/jpeg', size: IMAGE_MAX_BYTES + 1 })).toBe(
      IMAGE_SIZE_NOT_ALLOWED,
    );
  });
});

describe('uploadFailure', () => {
  const error = (status: number, code: string | null, kind: ApiError['kind'] = 'server') =>
    new ApiError(kind, status, code, '서버 문구');

  it('업무 코드별 문구', () => {
    expect(uploadFailure(error(400, 'PRODUCT_033'))).toMatchObject({
      message: IMAGE_FORMAT_NOT_ALLOWED,
    });
    expect(uploadFailure(error(400, 'PRODUCT_034'))).toMatchObject({
      message: IMAGE_SIZE_NOT_ALLOWED,
    });
    expect(uploadFailure(error(503, 'PRODUCT_035', 'unavailable'))).toMatchObject({
      message: UPLOAD_UNAVAILABLE,
    });
    expect(uploadFailure(error(503, 'PRODUCT_036', 'unavailable'))).toMatchObject({
      message: UPLOAD_DISABLED,
    });
  });

  it('401 은 화면을 지키는 문구, 403 은 접근 상태', () => {
    expect(uploadFailure(error(401, null, 'unauthorized'))).toEqual({
      ok: false,
      kind: 'error',
      message: UPLOAD_SESSION_EXPIRED,
    });
    expect(uploadFailure(error(403, null, 'forbidden'))).toEqual({
      ok: false,
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('요청 제한은 서버 문구, 그 밖은 일시 오류', () => {
    expect(uploadFailure(error(429, 'GATEWAY_006', 'rateLimited'))).toMatchObject({
      message: '서버 문구',
    });
    expect(uploadFailure(error(502, null, 'unknownOutcome'))).toMatchObject({
      message: UPLOAD_UNAVAILABLE,
    });
    expect(uploadFailure(new Error('x'))).toMatchObject({ message: UPLOAD_UNAVAILABLE });
  });
});

describe('uploadImage', () => {
  it('발급 → 받은 주소에 받은 메서드 · 헤더로 올리고 키를 돌려준다(쿠키 없이)', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, ticket))
      .mockResolvedValueOnce(new Response(null, { status: 200 }));
    const file = jpeg(2048);

    const result = await uploadImage(file, options, fetchImpl);

    expect(result).toEqual({ ok: true, uploadKey: ticket.uploadKey });
    const [issueUrl, issueInit] = fetchImpl.mock.calls[0]!;
    expect(issueUrl).toBe(PRODUCT_IMAGE_UPLOAD_PATH);
    expect(issueInit?.method).toBe('POST');
    expect(JSON.parse(String(issueInit?.body))).toEqual({
      contentType: 'image/jpeg',
      contentLength: 2048,
    });
    const [putUrl, putInit] = fetchImpl.mock.calls[1]!;
    expect(putUrl).toBe(ticket.uploadUrl);
    expect(putInit).toMatchObject({
      method: 'PUT',
      headers: ticket.headers,
      body: file,
      credentials: 'omit',
      redirect: 'error',
    });
  });

  it('검사에 걸리면 서버를 부르지 않는다', async () => {
    const fetchImpl = vi.fn<typeof fetch>();
    const gif = new File(['x'], 'a.gif', { type: 'image/gif' });

    expect(await uploadImage(gif, options, fetchImpl)).toEqual({
      ok: false,
      kind: 'error',
      message: IMAGE_FORMAT_NOT_ALLOWED,
    });
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('발급 실패는 코드별 문구이고 올리지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(503, null, 'PRODUCT_036'));

    expect(await uploadImage(jpeg(), options, fetchImpl)).toMatchObject({
      message: UPLOAD_DISABLED,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('쓸 수 없는 업로드 주소 · 메서드는 따르지 않는다', async () => {
    for (const bad of [
      { uploadUrl: 'javascript:alert(1)' },
      { uploadUrl: '/relative' },
      { uploadUrl: 'http://storage.example.com/x' },
      { method: 'DELETE' },
      { method: 'POST' },
      { method: null },
      { uploadKey: '' },
      { headers: 'x' },
      { headers: [] },
      { headers: { 'Content-Type': 1 } },
    ]) {
      const fetchImpl = vi
        .fn<typeof fetch>()
        .mockResolvedValueOnce(envelope(201, { ...ticket, ...bad }));
      expect(await uploadImage(jpeg(), options, fetchImpl)).toMatchObject({
        message: UPLOAD_UNAVAILABLE,
      });
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    }
  });

  it('스토리지 거절 · 연결 실패는 다시 고르라는 문구', async () => {
    const rejected = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, ticket))
      .mockResolvedValueOnce(new Response('<Error/>', { status: 403 }));
    expect(await uploadImage(jpeg(), options, rejected)).toMatchObject({ message: UPLOAD_FAILED });

    const offline = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, ticket))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect(await uploadImage(jpeg(), options, offline)).toMatchObject({ message: UPLOAD_FAILED });
  });

  it('발급 응답 본문이 비면(계약과 다르면) 올리지 않고 일시 오류', async () => {
    const fetchImpl = vi.fn<typeof fetch>().mockResolvedValueOnce(envelope(201, null));
    expect(await uploadImage(jpeg(), options, fetchImpl)).toMatchObject({
      message: UPLOAD_UNAVAILABLE,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it('올리는 중 취소도 결과가 아니라 그대로 던진다', async () => {
    const controller = new AbortController();
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, ticket))
      .mockImplementationOnce(async () => {
        controller.abort();
        throw new DOMException('aborted', 'AbortError');
      });

    await expect(
      uploadImage(jpeg(), { ...options, signal: controller.signal }, fetchImpl),
    ).rejects.toBeDefined();
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('취소는 결과가 아니라 그대로 던진다', async () => {
    const controller = new AbortController();
    const fetchImpl = vi.fn<typeof fetch>().mockImplementationOnce(async () => {
      controller.abort();
      throw new DOMException('aborted', 'AbortError');
    });

    await expect(
      uploadImage(jpeg(), { ...options, signal: controller.signal }, fetchImpl),
    ).rejects.toBeDefined();
  });
});

describe('uploadFailure — 전시 발급(#508)', () => {
  it('MEDIA_UPLOAD 코드도 상품과 같은 문구', () => {
    const error = (code: string) => new ApiError('server', 400, code, '서버 문구');
    expect(uploadFailure(error('MEDIA_UPLOAD_003'))).toMatchObject({
      message: IMAGE_FORMAT_NOT_ALLOWED,
    });
    expect(uploadFailure(error('MEDIA_UPLOAD_004'))).toMatchObject({
      message: IMAGE_SIZE_NOT_ALLOWED,
    });
    expect(uploadFailure(error('MEDIA_UPLOAD_006'))).toMatchObject({ message: UPLOAD_DISABLED });
    expect(uploadFailure(error('MEDIA_UPLOAD_007'))).toMatchObject({
      message: UPLOAD_UNAVAILABLE,
    });
  });
});

describe('uploadDisplayImage', () => {
  const displayTicket = { ...ticket, uploadKey: '3f2a1c4e-8b7d-4e2a-9c1f-5a6b7c8d9e0f.jpg' };
  const confirmed = {
    publicUrl: 'https://cdn.example.test/products/media/1/3f2a/abc.jpg',
    contentType: 'image/jpeg',
    contentLength: 1024,
  };

  it('발급 → PUT → 확정 순서로 부르고 공개 주소를 돌려준다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, displayTicket))
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockResolvedValueOnce(envelope(200, confirmed));

    expect(await uploadDisplayImage(jpeg(), {}, fetchImpl)).toEqual({
      ok: true,
      publicUrl: confirmed.publicUrl,
    });
    expect(fetchImpl.mock.calls[0]![0]).toBe(DISPLAY_IMAGE_UPLOAD_PATH);
    const [confirmUrl, confirmInit] = fetchImpl.mock.calls[2]!;
    expect(confirmUrl).toBe(`${DISPLAY_IMAGE_UPLOAD_PATH}/${displayTicket.uploadKey}/confirm`);
    expect(confirmInit?.method).toBe('POST');
    expect(confirmInit?.body).toBeUndefined();
  });

  it('PUT 이 실패하면 확정하지 않는다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, displayTicket))
      .mockResolvedValueOnce(new Response(null, { status: 403 }));
    expect(await uploadDisplayImage(jpeg(), {}, fetchImpl)).toMatchObject({
      message: UPLOAD_FAILED,
    });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('확정 결과를 모르면 같은 키로 다시 확인할 수 있게 남긴다', async () => {
    const fetchImpl = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(envelope(201, displayTicket))
      .mockResolvedValueOnce(new Response(null, { status: 200 }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect(await uploadDisplayImage(jpeg(), {}, fetchImpl)).toEqual({
      ok: false,
      kind: 'error',
      message: CONFIRM_UNKNOWN,
      retryKey: displayTicket.uploadKey,
    });
  });

  it('확정 응답 주소가 쓸 수 없으면(http · 빈 값) 결과 모름으로 본다', async () => {
    for (const body of [null, {}, { publicUrl: 'javascript:alert(1)' }, { publicUrl: 'x' }]) {
      const fetchImpl = vi.fn<typeof fetch>().mockResolvedValueOnce(envelope(200, body));
      expect(await confirmDisplayImage('k.jpg', {}, fetchImpl)).toMatchObject({
        message: CONFIRM_UNKNOWN,
        retryKey: 'k.jpg',
      });
    }
  });

  it('확정 실패는 다시 확인으로 풀리는 것만 키를 남긴다', () => {
    const error = (status: number, code: string | null, kind: ApiError['kind'] = 'server') =>
      new ApiError(kind, status, code, '서버 문구');
    expect(confirmFailure(error(404, 'MEDIA_UPLOAD_001'), 'k')).toEqual({
      ok: false,
      kind: 'error',
      message: CONFIRM_GONE,
    });
    expect(confirmFailure(error(400, 'MEDIA_UPLOAD_106'), 'k')).toMatchObject({
      message: CONFIRM_GONE,
    });
    expect(confirmFailure(error(503, 'MEDIA_UPLOAD_006', 'unavailable'), 'k')).not.toHaveProperty(
      'retryKey',
    );
    expect(confirmFailure(error(409, 'MEDIA_UPLOAD_005', 'conflict'), 'k')).toMatchObject({
      message: CONFIRM_CHANGED,
      retryKey: 'k',
    });
    expect(confirmFailure(error(503, 'MEDIA_UPLOAD_007', 'unavailable'), 'k')).toMatchObject({
      message: CONFIRM_UNKNOWN,
      retryKey: 'k',
    });
    expect(confirmFailure(error(401, null, 'unauthorized'), 'k')).toMatchObject({
      message: CONFIRM_SESSION_EXPIRED,
      retryKey: 'k',
    });
    expect(confirmFailure(error(403, null, 'forbidden'), 'k')).toEqual({
      ok: false,
      kind: 'access',
      denied: 'forbidden',
    });
  });
});

describe('usableTicket', () => {
  it('https 는 늘, http 는 화면이 http(로컬 개발)일 때만', () => {
    expect(usableTicket(ticket, 'https:')).toBe(true);
    const local = { ...ticket, uploadUrl: 'http://localhost:9000/bucket/x' };
    expect(usableTicket(local, 'https:')).toBe(false);
    expect(usableTicket(local, null)).toBe(false);
    expect(usableTicket(local, 'http:')).toBe(true);
  });
});

describe('formatBytes', () => {
  it('KB 올림 · MB 한 자리', () => {
    expect(formatBytes(1)).toBe('1KB');
    expect(formatBytes(1536)).toBe('2KB');
    expect(formatBytes(5 * 1024 * 1024)).toBe('5MB');
    expect(formatBytes(2.5 * 1024 * 1024)).toBe('2.5MB');
  });
});
