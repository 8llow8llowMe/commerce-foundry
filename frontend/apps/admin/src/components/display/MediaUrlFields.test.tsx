import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { createForm, editForm, type MediaFormItem } from '@/lib/display/form';
import { mediaItem, section } from '@/lib/display/test-fixtures';
import { MediaUrlFields } from './MediaUrlFields';

const render = (media: MediaFormItem[], errors: Record<string, string[]> = {}) =>
  renderToStaticMarkup(
    <MediaUrlFields
      type="LOOK"
      media={media}
      errorsOf={(key) => errors[key] ?? []}
      onChange={() => {}}
    />,
  );

const disabledButton = (html: string, label: string) =>
  new RegExp(`<button[^>]*disabled=""[^>]*>${label}<`).test(html);

describe('MediaUrlFields', () => {
  it('저장소에 올린 이미지 주소를 넣으라고 안내하고, 주소 · 대체 텍스트 칸과 미리보기를 보인다', () => {
    const html = render(editForm(section('LOOK', { items: [mediaItem()] }), 'LOOK').media);
    expect(html).toContain('쇼핑몰 이미지 저장소에 이미 올린 이미지의 주소');
    expect(html).toContain('메인에는 첫 번째 이미지만 보입니다');
    expect(html).toContain('1번째 이미지 주소');
    expect(html).toContain('value="https://cdn.example.test/displays/a-pc.jpg"');
    expect(html).toContain('src="https://cdn.example.test/displays/a-pc.jpg" alt=""');
    expect(html).toContain('value="샘플 사진"');
  });

  it('하한(1장)이면 빼기가 없고, 빈 주소 칸은 이미지 없음 · 칸 오류를 보인다', () => {
    const html = render(createForm('LOOK').media, {
      'media.0.url': ['이미지 주소를 넣어 주세요.'],
    });
    expect(html).not.toContain('빼기');
    expect(html).toContain('이미지 없음');
    expect(html).toContain('이미지 주소를 넣어 주세요.');
  });

  it('여러 장이면 빼기를 두고, 상한(10장)이면 추가를 막는다', () => {
    const two = editForm(section('LOOK', { items: [mediaItem(), mediaItem()] }), 'LOOK').media;
    expect(render(two)).toContain('aria-label="2번째 이미지 빼기"');
    expect(disabledButton(render(two), '이미지 주소 추가')).toBe(false);
    const ten = Array.from({ length: 10 }, () => two[0]!);
    expect(disabledButton(render(ten), '이미지 주소 추가')).toBe(true);
  });

  it('영상은 주소 칸 없이 대표 사진과 안내만 둔다', () => {
    const video = mediaItem({
      mediaType: { code: 'VIDEO', name: '영상', description: '' },
      pcUrl: 'https://cdn.example.test/v.mp4',
      pcPosterUrl: 'https://cdn.example.test/v.jpg',
    });
    const html = render(editForm(section('LOOK', { items: [video] }), 'LOOK').media);
    expect(html).not.toContain('1번째 영상 주소');
    expect(html).toContain('영상 주소는 여기서 바꿀 수 없습니다');
    expect(html).toContain('src="https://cdn.example.test/v.jpg"');
  });
});
