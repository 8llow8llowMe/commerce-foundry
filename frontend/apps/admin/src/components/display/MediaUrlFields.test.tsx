import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { createForm, editForm, type MediaFormItem } from '@/lib/display/form';
import { mediaItem, section } from '@/lib/display/test-fixtures';
import { MediaUrlFields } from './MediaUrlFields';

const render = (media: MediaFormItem[], errors: Record<string, string[]> = {}) =>
  renderToStaticMarkup(
    <MediaUrlFields
      type="HERO"
      media={media}
      errorsOf={(key) => errors[key] ?? []}
      onChange={() => {}}
    />,
  );

const disabledButton = (html: string, label: string) =>
  new RegExp(`<button[^>]*disabled=""[^>]*>${label}<`).test(html);

describe('MediaUrlFields', () => {
  it('파일 올리기 · 주소 넣기를 안내하고, 주소 · 대체 텍스트 칸과 미리보기를 보인다', () => {
    const html = render(editForm(section('HERO', { items: [mediaItem()] }), 'HERO').media);
    expect(html).toContain('파일을 올리면 주소 칸이 채워집니다');
    expect(html).toContain('1번째 이미지 PC 파일 올리기');
    expect(html).toContain('1번째 이미지 모바일 파일 올리기 (선택)');
    expect(html).toContain('accept="image/jpeg,image/png,image/webp"');
    expect(html).toContain('메인에는 첫 번째 이미지만 보입니다');
    expect(html).toContain('1번째 이미지 PC 주소');
    expect(html).toContain('value="https://cdn.example.test/displays/a-pc.jpg"');
    expect(html).toContain('src="https://cdn.example.test/displays/a-pc.jpg" alt=""');
    expect(html).toContain('value="샘플 사진"');
    expect(html).toContain('1번째 이미지 대체 텍스트 (필수)');
    expect(html).toContain('aria-required="true"');
    expect(html).toContain('권장 이미지: PC 세로 4:5 · 1600×2000px 이상');
  });

  it('하한(1장)이면 빼기가 없고, 빈 주소 칸은 이미지 없음 · 칸 오류를 보인다', () => {
    const html = render(createForm('HERO').media, {
      'media.0.url': ['이미지 주소를 넣어 주세요.'],
    });
    expect(html).not.toContain('빼기');
    expect(html).toContain('이미지 없음');
    expect(html).toContain('이미지 주소를 넣어 주세요.');
  });

  it('여러 장이면 빼기를 두고, 상한(10장)이면 추가를 막는다', () => {
    const two = editForm(section('HERO', { items: [mediaItem(), mediaItem()] }), 'HERO').media;
    expect(render(two)).toContain('aria-label="2번째 이미지 빼기"');
    expect(disabledButton(render(two), '이미지 주소 추가')).toBe(false);
    const ten = Array.from({ length: 10 }, () => two[0]!);
    expect(disabledButton(render(ten), '이미지 주소 추가')).toBe(true);
  });

  it('올리는 중이면 줄 넣기 · 빼기를 막는다(끝난 업로드가 줄 위치로 주소를 넣는다)', () => {
    const two = editForm(section('HERO', { items: [mediaItem(), mediaItem()] }), 'HERO').media;
    const html = renderToStaticMarkup(
      <MediaUrlFields type="HERO" media={two} errorsOf={() => []} onChange={() => {}} uploading />,
    );
    expect(disabledButton(html, '이미지 주소 추가')).toBe(true);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*aria-label="2번째 이미지 빼기"/);
  });

  it('영상은 주소 칸 없이 대표 사진과 안내만 둔다', () => {
    const video = mediaItem({
      mediaType: { code: 'VIDEO', name: '영상', description: '' },
      pcUrl: 'https://cdn.example.test/v.mp4',
      pcPosterUrl: 'https://cdn.example.test/v.jpg',
    });
    const html = render(editForm(section('HERO', { items: [video] }), 'HERO').media);
    expect(html).not.toContain('1번째 영상 주소');
    expect(html).not.toContain('파일 올리기');
    expect(html).toContain('영상 주소는 여기서 바꿀 수 없습니다');
    expect(html).toContain('src="https://cdn.example.test/v.jpg"');
    expect(html).toContain('1번째 영상 대체 텍스트 (선택)');
  });

  it('히어로는 PC · 모바일 주소 칸과 썸네일을 따로 두고, 모바일이 비면 PC 사진을 쓴다고 알린다(#71)', () => {
    const renderHero = (mobileUrl: string | null) =>
      renderToStaticMarkup(
        <MediaUrlFields
          type="HERO"
          media={editForm(section('HERO', { items: [mediaItem({ mobileUrl })] }), 'HERO').media}
          errorsOf={() => []}
          onChange={() => {}}
        />,
      );
    const html = renderHero('https://cdn.example.test/displays/a-mo.jpg');
    expect(html).toContain('1번째 이미지 PC 주소');
    expect(html).toContain('1번째 이미지 모바일 주소 (선택)');
    expect(html).toContain('src="https://cdn.example.test/displays/a-mo.jpg"');
    expect(html).toContain('PC 세로 4:5 · 1600×2000px 이상, 모바일 세로 4:5 · 1080×1350px 이상');
    expect(html).toContain('메인에는 첫 번째 이미지만 보입니다');
    expect(renderHero(null)).toContain('PC 사진을 씀');
  });

  it('디테일은 클로즈업마다 설명 칸을 두고, 넣은 순서대로 모두 보인다고 안내한다', () => {
    const html = renderToStaticMarkup(
      <MediaUrlFields
        type="DETAIL"
        media={
          editForm(section('DETAIL', { items: [mediaItem({}, '손바느질 깃')] }), 'DETAIL').media
        }
        errorsOf={(key) => (key === 'media.0.caption' ? ['설명 오류'] : [])}
        onChange={() => {}}
      />,
    );
    expect(html).toContain('1번째 이미지 설명 (선택)');
    expect(html).toContain('value="손바느질 깃"');
    expect(html).toContain('설명 오류');
    expect(html).toContain('메인에는 넣은 순서대로 모두 보입니다');
    expect(html).not.toContain('모바일 주소');
  });
});
