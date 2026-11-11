import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { IMAGE_ACCEPT } from '@/lib/upload/image-upload';
import { DisplayImageUploadButton } from './DisplayImageUploadButton';

describe('DisplayImageUploadButton', () => {
  it('칸 이름 · 형식 · 용량 안내와 accept 를 그리고, 처음에는 다시 확인이 없다', () => {
    const html = renderToStaticMarkup(
      <DisplayImageUploadButton label="1번째 이미지 PC 파일 올리기" onUploaded={() => {}} />,
    );
    expect(html).toContain('1번째 이미지 PC 파일 올리기');
    expect(html).toContain(`accept="${IMAGE_ACCEPT}"`);
    expect(html).toContain('JPG · PNG · WebP, 10MB 이하');
    expect(html).not.toMatch(/<input[^>]*disabled=/);
    expect(html).not.toContain('다시 확인');
  });

  it('꺼 두면 고르기를 막는다', () => {
    const html = renderToStaticMarkup(
      <DisplayImageUploadButton label="파일" onUploaded={() => {}} disabled />,
    );
    expect(html).toMatch(/<input[^>]*disabled=""/);
  });

  it('칸마다 형식 · 용량 안내를 바꿀 수 있다(AI 상품 초안 5MB)', () => {
    const html = renderToStaticMarkup(
      <DisplayImageUploadButton
        label="대표 사진"
        hint="한 장에 5MB 이하"
        checkFile={() => null}
        onUploaded={() => {}}
      />,
    );
    expect(html).toContain('한 장에 5MB 이하');
    expect(html).not.toContain('10MB');
  });
});
