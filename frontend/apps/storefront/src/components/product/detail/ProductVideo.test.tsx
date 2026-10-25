import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import type { ProductImage } from '@/lib/catalog/types';
import { en } from '@/lib/i18n/dictionaries/en';
import { ProductVideo, VideoPlayer, VideoPoster } from './ProductVideo';

const video: ProductImage = {
  imageId: 'v1',
  url: 'https://img.test/v1.mp4',
  width: 1080,
  height: 1350,
  altText: '착용 영상',
  mediaType: 'VIDEO',
  posterUrl: 'https://img.test/v1-poster.jpg',
  displayOrder: 1,
};

describe('ProductVideo', () => {
  it('처음에는 포스터와 재생 버튼만 그리고 영상은 받지 않는다(자동재생 없음)', () => {
    const html = renderToStaticMarkup(<ProductVideo video={video} label="착용 영상" />);
    expect(html).not.toContain('<video');
    expect(html).toMatch(
      /<button type="button" aria-label="착용 영상 영상 재생"[^>]*aspect-\[4\/5\]/,
    );
    expect(html).toContain('src="https://img.test/v1-poster.jpg"');
    expect(html).toContain('loading="lazy"');
  });
});

describe('VideoPoster', () => {
  it('첫 칸이면 포스터를 바로 받고, 포스터가 없으면 빈 칸에 재생 표시만 둔다', () => {
    const eager = renderToStaticMarkup(
      <VideoPoster posterUrl="https://img.test/p.jpg" label="x" eager onPlay={() => {}} />,
    );
    expect(eager).toContain('loading="eager"');
    const empty = renderToStaticMarkup(
      <VideoPoster posterUrl={null} label="x" eager={false} onPlay={() => {}} />,
    );
    expect(empty).not.toContain('<img');
    expect(empty).toContain('<svg');
  });

  it('영문 스토어 문구', () => {
    const html = renderToStaticMarkup(
      <LocaleProvider locale="en" prefix="/en" dict={en}>
        <VideoPoster posterUrl={null} label="Front" eager={false} onPlay={() => {}} />
      </LocaleProvider>,
    );
    expect(html).toContain('aria-label="Play video: Front"');
  });
});

describe('VideoPlayer (누른 뒤)', () => {
  it('소리 끈 채 · 인라인 · 컨트롤로 그리고 autoplay 속성은 없다', () => {
    const html = renderToStaticMarkup(<VideoPlayer video={video} label="착용 영상" />);
    const tag = html.match(/<video[^>]*>/)?.[0] ?? '';
    expect(tag).toContain('src="https://img.test/v1.mp4"');
    expect(tag).toContain('poster="https://img.test/v1-poster.jpg"');
    expect(tag).toContain('aria-label="착용 영상 영상"');
    expect(tag).toContain('controls=""');
    expect(tag).toContain('muted=""');
    expect(tag).toContain('playsInline=""');
    expect(tag).toContain('preload="metadata"');
    expect(tag).toContain('aspect-[4/5]');
    expect(tag).not.toContain('autoplay');
    expect(tag).not.toContain('autoPlay');
    // 반복은 움직임 줄이기 설정을 본 뒤 브라우저에서 정한다
    expect(tag).not.toContain('loop');
  });
});
