import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { editForm } from '@/lib/display/form';
import { mediaItem, section } from '@/lib/display/test-fixtures';
import { MediaFields } from './MediaFields';

const render = (type: 'HERO' | 'DETAIL', count: number, altText: string | null = '샘플 사진') => {
  const items = Array.from({ length: count }, () => mediaItem({ altText }));
  const form = editForm(section(type, { items }), type);
  return renderToStaticMarkup(
    <MediaFields type={type} media={form.media} errorsOf={() => []} onAltText={() => {}} />,
  );
};

describe('MediaFields', () => {
  it('썸네일은 읽기 전용(장식 alt), 교체는 업로드 기능 뒤라고 안내한다', () => {
    const html = render('DETAIL', 1);
    expect(html).toContain('src="https://cdn.example.test/displays/a-pc.jpg" alt=""');
    expect(html).toContain('이미지 · 영상 교체는 업로드 기능이 준비되면 지원합니다.');
    expect(html).toContain('value="샘플 사진"');
  });

  it('히어로에 여러 장이면 메인에는 첫 장만 보인다고 알린다', () => {
    expect(render('HERO', 2)).toContain('첫 번째 이미지 · 영상만 보입니다');
    expect(render('HERO', 1)).not.toContain('첫 번째 이미지 · 영상만 보입니다');
  });

  it('이미지 대체 텍스트는 필수로 표시하고, 유형별 권장 비율 · 해상도를 안내한다(#71)', () => {
    const html = render('DETAIL', 1, null);
    expect(html).toContain('1번째 이미지 대체 텍스트 (필수)');
    expect(html).toMatch(/<input[^>]*required=""/);
    expect(html).toContain('권장 이미지: 세로 3:4 · 1200×1600px 이상.');
  });

  it('히어로는 PC · 모바일 사진을 따로 보이고, 모바일이 없으면 PC 사진을 쓴다고 알린다', () => {
    const withMobile = renderToStaticMarkup(
      <MediaFields
        type="HERO"
        media={
          editForm(
            section('HERO', {
              items: [mediaItem({ mobileUrl: 'https://cdn.example.test/displays/a-mo.jpg' })],
            }),
            'HERO',
          ).media
        }
        errorsOf={() => []}
        onAltText={() => {}}
      />,
    );
    expect(withMobile).toContain('src="https://cdn.example.test/displays/a-pc.jpg"');
    expect(withMobile).toContain('src="https://cdn.example.test/displays/a-mo.jpg"');
    expect(withMobile).toContain(
      'PC 세로 4:5 · 1600×2000px 이상, 모바일 세로 4:5 · 1080×1350px 이상',
    );
    expect(render('HERO', 1)).toContain('PC 사진을 씀');
  });

  it('영상은 대체 텍스트를 필수로 받지 않는다', () => {
    const video = mediaItem({
      mediaType: { code: 'VIDEO', name: '영상', description: '' },
      pcPosterUrl: 'https://cdn.example.test/displays/poster.jpg',
    });
    const html = renderToStaticMarkup(
      <MediaFields
        type="HERO"
        media={editForm(section('HERO', { items: [video] }), 'HERO').media}
        errorsOf={() => []}
        onAltText={() => {}}
      />,
    );
    expect(html).toContain('1번째 영상 대체 텍스트 (선택)');
    expect(html).not.toMatch(/<input[^>]*required=""/);
  });
});
