import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { IMAGE_ACCEPT, PRODUCT_IMAGE_UPLOAD_PATH } from '@/lib/upload/image-upload';
import { ImageUploadField } from './ImageUploadField';

const base = {
  label: '대표 이미지',
  issuePath: PRODUCT_IMAGE_UPLOAD_PATH,
  onChange: () => {},
};

describe('ImageUploadField', () => {
  it('형식 · 용량 안내와 accept 를 그린다', () => {
    const html = renderToStaticMarkup(<ImageUploadField {...base} value={null} />);
    expect(html).toContain('대표 이미지');
    expect(html).toContain(`accept="${IMAGE_ACCEPT}"`);
    expect(html).toContain('JPG · PNG · WebP, 10MB 이하');
    expect(html).not.toMatch(/<input[^>]*disabled=/);
  });

  it('꺼 두면 고르기를 막고 이유를 안내한다', () => {
    const html = renderToStaticMarkup(
      <ImageUploadField {...base} value={null} disabled disabledNotice="준비되면 열립니다." />,
    );
    expect(html).toMatch(/<input[^>]*disabled=""/);
    expect(html).toContain('준비되면 열립니다.');
  });

  it('올린 이미지는 파일 이름 · 크기 · 빼기, 서버 오류는 칸 아래, 키는 화면에 없다', () => {
    const html = renderToStaticMarkup(
      <ImageUploadField
        {...base}
        value={{
          uploadKey: 'uploads/1/k.jpg',
          file: new File([new Uint8Array(2048)], 'hero.jpg', { type: 'image/jpeg' }),
        }}
        hint="권장 4:5"
        errors={['이미지를 다시 올려 주세요.']}
      />,
    );
    // 미리보기 object URL 은 ref 가 단다 — 서버 렌더에는 src 가 없다
    expect(html).toMatch(/<img alt=""/);
    expect(html).not.toContain('src=');
    expect(html).toContain('aria-label="대표 이미지 빼기"');
    expect(html).toContain('hero.jpg');
    expect(html).toContain('2KB · 올림 완료');
    expect(html).toContain('권장 4:5');
    expect(html).toContain('이미지를 다시 올려 주세요.');
    expect(html).not.toContain('uploads/1/k.jpg');
  });
});
