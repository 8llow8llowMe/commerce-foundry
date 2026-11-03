import { describe, expect, it } from 'vitest';
import { ALT_TEXT_INVALID } from './create';
import { emptyEditSlot, imagesFormOf, imagesRequest, sameImages, videoCount } from './images';
import { adminProduct } from './test-fixtures';

const file = new File(['x'], 'a.png', { type: 'image/png' });

describe('이미지 수정', () => {
  it('영상은 빼고 대표 · 추가 이미지 칸으로 채운다', () => {
    const form = imagesFormOf(adminProduct());
    expect(form.thumbnail.existing).toEqual({ imageId: '501', url: 'https://img.test/thumb.jpg' });
    expect(form.thumbnail.altText).toBe('정면');
    expect(form.gallery).toEqual([]);
    expect(videoCount(adminProduct())).toBe(1);
  });

  it('기존은 imageId, 새 업로드는 uploadKey 하나만 싣고 빈 칸은 뺀다', () => {
    const form = imagesFormOf(adminProduct());
    form.gallery = [
      { ...emptyEditSlot('g1'), upload: { uploadKey: 'uploads/k1', file }, altText: ' 뒤 ' },
      emptyEditSlot('g2'),
    ];
    expect(imagesRequest(form, 7)).toEqual({
      ok: true,
      request: {
        version: 7,
        images: [
          { imageId: '501', imageType: 'THUMBNAIL', altText: '정면' },
          { uploadKey: 'uploads/k1', imageType: 'GALLERY', altText: '뒤' },
        ],
      },
    });
  });

  it('대표를 빼면 빈 배열(전부 빼기)도 보낸다', () => {
    const form = imagesFormOf(adminProduct());
    form.thumbnail = emptyEditSlot('thumbnail');
    expect(imagesRequest(form, 1)).toEqual({ ok: true, request: { version: 1, images: [] } });
    expect(sameImages(form, imagesFormOf(adminProduct()))).toBe(false);
  });

  it('대체 텍스트 칸 오류는 본문 번호로 단다', () => {
    const form = imagesFormOf(adminProduct());
    form.thumbnail = { ...form.thumbnail, altText: '줄\n바꿈' };
    expect(imagesRequest(form, 1)).toEqual({
      ok: false,
      errors: { 'images.0.altText': [ALT_TEXT_INVALID] },
    });
  });
});
