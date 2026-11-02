import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import {
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
