import type { FormErrors } from '@/lib/api-errors';
import {
  ALT_TEXT_INVALID,
  ALT_TEXT_MAX,
  IMAGES_MAX,
  IMAGES_TOO_MANY,
  isPlainText,
  type ProductImageType,
} from './create';
import { VIDEO_MEDIA, type AdminProductDetail } from './detail';
import { addError } from './section';

// 어드민 상품 정지 이미지 수정(#29 · BE #560 F3) — `PUT /api/v1/admin/products/{productId}/images`.
// 정지 이미지(대표 · 추가) 전체 교체다. 남길 기존 이미지는 `imageId`, 새로 올린 이미지는 `uploadKey` 하나만 보낸다.
// 배열 순서가 노출 순서이고 대표는 한 장까지, 합쳐 8장까지(빈 배열은 전부 빼기). 영상은 보내지 않아도 남는다.
// 같은 요청이어도 저장하면 버전이 오른다. 뺀 기존 이미지 파일은 서버가 지우지 않는다(다른 곳이 같은 파일을 쓸 수 있다).

/**
 * 이미지 칸 하나. 기존 이미지(`existing`)이거나 새로 올린 이미지(`upload`)이거나 빈 칸이다.
 * 기존 이미지를 빼면 같은 칸이 업로드 칸이 된다. `id` 는 화면 key 용.
 */
export type EditImageSlot = {
  id: string;
  existing: { imageId: string; url: string } | null;
  upload: { uploadKey: string; file: File } | null;
  altText: string;
};

export type ImagesForm = { thumbnail: EditImageSlot; gallery: EditImageSlot[] };

export type ImagesRequestItem =
  | { imageId: string; imageType: ProductImageType; altText?: string }
  | { uploadKey: string; imageType: ProductImageType; altText?: string };

export type ImagesRequest = { version: number; images: ImagesRequestItem[] };

export const emptyEditSlot = (id: string): EditImageSlot => ({
  id,
  existing: null,
  upload: null,
  altText: '',
});

/** 상세 → 폼. 영상은 이 화면에서 다루지 않는다(그대로 남는다). 대표가 여럿인 과거 데이터는 첫 장만 대표로 둔다 */
export function imagesFormOf(product: AdminProductDetail): ImagesForm {
  const stills = product.images.filter((media) => media.imageType.code !== VIDEO_MEDIA);
  const slotOf = (media: (typeof stills)[number]): EditImageSlot => ({
    id: media.imageId,
    existing: { imageId: media.imageId, url: media.url },
    upload: null,
    altText: media.altText ?? '',
  });
  const thumbnail = stills.find((media) => media.imageType.code === 'THUMBNAIL');
  return {
    thumbnail: thumbnail ? slotOf(thumbnail) : emptyEditSlot('thumbnail'),
    gallery: stills.filter((media) => media !== thumbnail).map(slotOf),
  };
}

/** 영상 개수 — 화면이 "영상은 그대로 남는다" 를 알린다 */
export const videoCount = (product: AdminProductDetail) =>
  product.images.filter((media) => media.imageType.code === VIDEO_MEDIA).length;

type Entry = { slot: EditImageSlot; imageType: ProductImageType; index: number };

/** 보낼 칸 — 채운 칸만, 대표 → 추가 순서. `index` 는 본문 번호라 칸 오류 키(`images.{n}.altText`)와 같다 */
export function editImageEntries(form: ImagesForm): Entry[] {
  return [
    { slot: form.thumbnail, imageType: 'THUMBNAIL' as const },
    ...form.gallery.map((slot) => ({ slot, imageType: 'GALLERY' as const })),
  ]
    .filter(({ slot }) => slot.existing !== null || slot.upload !== null)
    .map((entry, index) => ({ ...entry, index }));
}

export type BuildImages = { ok: true; request: ImagesRequest } | { ok: false; errors: FormErrors };

export function imagesRequest(form: ImagesForm, version: number): BuildImages {
  const errors: FormErrors = {};
  const entries = editImageEntries(form);
  if (entries.length > IMAGES_MAX) addError(errors, 'images', IMAGES_TOO_MANY);
  const images = entries.map(({ slot, imageType, index }): ImagesRequestItem => {
    const altText = slot.altText.trim();
    if (!isPlainText(altText) || altText.length > ALT_TEXT_MAX) {
      addError(errors, `images.${index}.altText`, ALT_TEXT_INVALID);
    }
    const alt = altText === '' ? {} : { altText };
    // 새로 올린 것이 기존보다 앞선다 — 기존 칸을 빼고 다시 올렸다
    return slot.upload
      ? { uploadKey: slot.upload.uploadKey, imageType, ...alt }
      : { imageId: slot.existing!.imageId, imageType, ...alt };
  });
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request: { version, images } };
}

/** 저장할 것이 바뀌었나 — 본문(버전 제외)이 같으면 같다 */
export function sameImages(a: ImagesForm, b: ImagesForm): boolean {
  const body = (form: ImagesForm) => {
    const built = imagesRequest(form, 0);
    return built.ok ? JSON.stringify(built.request.images) : null;
  };
  const left = body(a);
  return left !== null && left === body(b);
}
