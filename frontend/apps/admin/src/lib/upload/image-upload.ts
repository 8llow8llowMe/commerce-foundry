import { ApiError, apiFetch } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';

// 어드민 이미지 업로드(#29 · BE #9 F1, presigned PUT). 순서는 ① BFF 로 업로드 자리를 발급받고 ② 받은 주소로 파일을 스토리지에 직접 올린 뒤
// ③ 저장 본문에 `uploadKey` 를 싣는다. ② 는 게이트웨이 · BFF 를 지나지 않는다 — 쿠키를 싣지 않고, 서명 주소는 로그 · 화면에 남기지 않는다.
// 상품 이미지 발급(`PRODUCT_IMAGE_UPLOAD_PATH`)이 첫 사용처다. 전시 미디어 등 다른 발급 경로도 같은 응답 모양이면 경로만 바꿔 쓴다.

export const PRODUCT_IMAGE_UPLOAD_PATH = '/api/v1/admin/products/image-uploads';

/** BE `ProductImageFormat` 과 같다 — JPEG · PNG · WebP */
export const IMAGE_CONTENT_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const;
/** 파일 고르기 창의 `accept` */
export const IMAGE_ACCEPT = IMAGE_CONTENT_TYPES.join(',');
/** BE `ProductImageFormat.MAX_BYTES`(10MB) */
export const IMAGE_MAX_BYTES = 10 * 1024 * 1024;

export const IMAGE_FORMAT_NOT_ALLOWED = 'JPG · PNG · WebP 이미지만 올릴 수 있습니다.';
export const IMAGE_SIZE_NOT_ALLOWED = '10MB 이하의 이미지만 올릴 수 있습니다.';
export const IMAGE_EMPTY = '빈 파일은 올릴 수 없습니다.';
export const UPLOAD_UNAVAILABLE =
  '지금은 이미지를 올릴 수 없습니다. 잠시 후 다시 시도해 주세요. 이미지 없이 저장할 수는 있습니다.';
export const UPLOAD_DISABLED =
  '이미지 저장소가 아직 연결되지 않았습니다. 이미지 없이 저장할 수는 있습니다.';
/** 발급 401 — 화면을 지킨다(§9 공통 규칙). 서버가 처리하지 않은 요청이라 다시 로그인한 뒤 같은 칸에서 다시 고르면 된다 */
export const UPLOAD_SESSION_EXPIRED =
  '로그인이 만료되어 이미지를 올리지 못했습니다. 입력한 값은 그대로 두었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 골라 주세요.';
/** ② 실패 — 서명 만료 · 스토리지 거절 · 연결 실패. 발급부터 다시 하면 된다(키 하나는 한 번 올리는 자리다) */
export const UPLOAD_FAILED = '이미지를 올리지 못했습니다. 파일을 다시 골라 주세요.';

/** 발급 응답 — BE `ProductImageUploadAdminResponse` */
export type ImageUploadTicket = {
  uploadKey: string;
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  expiresAt: string;
};

export type ImageUploadFailure =
  | { ok: false; kind: 'access'; denied: AccessDenied }
  | { ok: false; kind: 'error'; message: string };
export type ImageUploadResult = { ok: true; uploadKey: string } | ImageUploadFailure;

/** 올리기 전 검사(형식 · 크기). BE 도 같은 규칙으로 다시 본다 — 여기서는 왕복을 줄이고 문구를 바로 보인다 */
export function checkImageFile(file: Pick<File, 'type' | 'size'>): string | null {
  if (!(IMAGE_CONTENT_TYPES as readonly string[]).includes(file.type)) {
    return IMAGE_FORMAT_NOT_ALLOWED;
  }
  if (file.size <= 0) return IMAGE_EMPTY;
  if (file.size > IMAGE_MAX_BYTES) return IMAGE_SIZE_NOT_ALLOWED;
  return null;
}

/**
 * 서버가 준 업로드 자리를 따를지. 계약은 presigned **PUT** 이다(본문 = 파일 그대로 — POST 폼 업로드는 모양이 달라 받지 않는다).
 * 주소는 https 절대 주소만이고, 화면이 http(로컬 개발 · MinIO)일 때만 http 를 받는다. 모양이 다르면(빈 본문 등) 쓰지 않는다.
 */
export function usableTicket(
  ticket: unknown,
  pageProtocol: string | null = typeof location === 'undefined' ? null : location.protocol,
): ticket is ImageUploadTicket {
  if (typeof ticket !== 'object' || ticket === null) return false;
  const { uploadKey, uploadUrl, method, headers } = ticket as Partial<ImageUploadTicket>;
  if (typeof uploadKey !== 'string' || uploadKey === '') return false;
  if (typeof uploadUrl !== 'string' || typeof method !== 'string') return false;
  if (
    headers !== undefined &&
    (typeof headers !== 'object' ||
      headers === null ||
      Array.isArray(headers) ||
      !Object.values(headers).every((value) => typeof value === 'string'))
  ) {
    return false;
  }
  if (method.toUpperCase() !== 'PUT') return false;
  let url: URL;
  try {
    url = new URL(uploadUrl);
  } catch {
    return false;
  }
  return url.protocol === 'https:' || (url.protocol === 'http:' && pageProtocol === 'http:');
}

/**
 * 발급 실패 → 화면 처리. 401 은 쓰기 중 세션 만료라 화면을 지키고 문구만, 403 은 접근 상태다.
 * 형식 · 크기(`PRODUCT_033` · `034`)는 검사를 지나친 경우(브라우저가 형식을 다르게 알려 준 파일)다.
 */
export function uploadFailure(cause: unknown): ImageUploadFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') {
    return { ok: false, kind: 'error', message: UPLOAD_SESSION_EXPIRED };
  }
  if (denied) return { ok: false, kind: 'access', denied };
  if (cause instanceof ApiError) {
    switch (cause.code) {
      case 'PRODUCT_033':
        return { ok: false, kind: 'error', message: IMAGE_FORMAT_NOT_ALLOWED };
      case 'PRODUCT_034':
        return { ok: false, kind: 'error', message: IMAGE_SIZE_NOT_ALLOWED };
      case 'PRODUCT_036':
        return { ok: false, kind: 'error', message: UPLOAD_DISABLED };
      case 'PRODUCT_035':
        return { ok: false, kind: 'error', message: UPLOAD_UNAVAILABLE };
      default:
        break;
    }
    // 429 는 서버 문구만 — 어드민 상품 API 는 게이트웨이 요청 제한 대상(장바구니 · 주문 쓰기)이 아니라 쿨다운을 두지 않는다
    if (cause.kind === 'rateLimited') return { ok: false, kind: 'error', message: cause.message };
  }
  // 발급은 저장이 아니다(키만 만든다) — 결과를 모르는 실패도 다시 고르면 새 키를 받는다
  return { ok: false, kind: 'error', message: UPLOAD_UNAVAILABLE };
}

/**
 * 파일 하나를 올리고 `uploadKey` 를 돌려준다. 실패는 던지지 않고 결과로 돌려준다 — 취소(`signal`)만 그대로 던진다.
 * ② 는 같은 origin 이 아니라 쿠키를 싣지 않는다(`credentials: 'omit'`). 헤더는 서명에 들어가 받은 그대로 보낸다.
 * 리다이렉트는 따르지 않는다(`redirect: 'error'`) — presigned PUT 에는 없어야 하고, 따르면 파일이 다른 호스트로 갈 수 있다.
 */
export async function uploadImage(
  file: File,
  { issuePath, signal }: { issuePath: string; signal?: AbortSignal },
  fetchImpl: typeof fetch = fetch,
): Promise<ImageUploadResult> {
  const invalid = checkImageFile(file);
  if (invalid) return { ok: false, kind: 'error', message: invalid };

  let ticket: unknown;
  try {
    ticket = await apiFetch<unknown>(
      issuePath,
      { method: 'POST', json: { contentType: file.type, contentLength: file.size }, signal },
      fetchImpl,
    );
  } catch (cause) {
    if (signal?.aborted) throw cause;
    return uploadFailure(cause);
  }
  if (!usableTicket(ticket)) return { ok: false, kind: 'error', message: UPLOAD_UNAVAILABLE };

  try {
    const response = await fetchImpl(ticket.uploadUrl, {
      method: 'PUT',
      headers: ticket.headers ?? {},
      body: file,
      credentials: 'omit',
      redirect: 'error',
      signal,
    });
    if (!response.ok) return { ok: false, kind: 'error', message: UPLOAD_FAILED };
  } catch (cause) {
    if (signal?.aborted) throw cause;
    return { ok: false, kind: 'error', message: UPLOAD_FAILED };
  }
  return { ok: true, uploadKey: ticket.uploadKey };
}

/** 크기 표시 — 1MB 미만은 KB(올림), 그 위는 MB 소수 한 자리(.0 은 뺀다) */
export function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.ceil(bytes / 1024))}KB`;
  return `${Number((bytes / (1024 * 1024)).toFixed(1))}MB`;
}
