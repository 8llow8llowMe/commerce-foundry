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

  it('대체 텍스트가 비면 화면 낭독기가 건너뛴다고 알린다', () => {
    expect(render('DETAIL', 1, null)).toContain('화면 낭독기가 이 사진을 건너뜁니다');
  });
});
