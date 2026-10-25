import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ProductImage } from '@/lib/catalog/types';
import { ProductGallery } from './ProductGallery';

const image = (
  imageId: string,
  displayOrder: number,
  altText: string | null = null,
): ProductImage => ({
  imageId,
  url: `https://img.test/${imageId}.jpg`,
  width: 900,
  height: 1200,
  altText,
  mediaType: 'IMAGE',
  posterUrl: null,
  displayOrder,
});

describe('ProductGallery', () => {
  it('이미지마다 크게 보기 버튼을 두고, 첫 장만 바로 받는다', () => {
    const html = renderToStaticMarkup(
      <ProductGallery
        name="윤금 드레스"
        thumbnail={null}
        images={[image('a', 0, '정면'), image('b', 1)]}
      />,
    );
    expect(html).toContain('aria-label="정면 크게 보기"');
    expect(html).toContain('aria-label="윤금 드레스 2 크게 보기"');
    expect(html.match(/loading="eager"/g)).toHaveLength(1);
    expect(html).toContain('loading="lazy"');
  });

  it('확대 창은 닫힌 채로 그리고 위치(1 / 2)와 이전 · 다음 버튼을 둔다', () => {
    const html = renderToStaticMarkup(
      <ProductGallery
        name="윤금 드레스"
        thumbnail={null}
        images={[image('a', 0), image('b', 1)]}
      />,
    );
    expect(html).toMatch(/<dialog(?![^>]* open)/);
    expect(html.replace(/<!-- -->/g, '')).toMatch(/>\s*1 \/ 2\s*</);
    expect(html).toContain('aria-label="이전 이미지"');
    expect(html).toContain('aria-label="다음 이미지"');
  });

  it('한 장이면 이전 · 다음 버튼이 없고, 갤러리가 비면 대표 이미지를 쓴다', () => {
    const thumbnail = {
      imageId: 't',
      url: 'https://img.test/t.jpg',
      width: null,
      height: null,
      altText: null,
    };
    const html = renderToStaticMarkup(
      <ProductGallery name="윤금 드레스" thumbnail={thumbnail} images={[]} />,
    );
    expect(html).toContain('src="https://img.test/t.jpg"');
    expect(html).not.toContain('다음 이미지');
  });

  it('이미지가 하나도 없으면 자리 표시만 한다', () => {
    const html = renderToStaticMarkup(<ProductGallery name="x" thumbnail={null} images={[]} />);
    expect(html).toContain('이미지 준비 중');
    expect(html).not.toContain('<dialog');
  });

  it('이미지는 4:5 로 PC 에서 세로로 이어 붙이고, 모바일 위치는 점 대신 글자(1 / n)로 보인다', () => {
    const html = renderToStaticMarkup(
      <ProductGallery
        name="윤금 드레스"
        thumbnail={null}
        images={[image('a', 0), image('b', 1), image('c', 2)]}
      />,
    );
    expect(html).toContain('aspect-[4/5]');
    expect(html).not.toContain('aspect-[3/4]');
    expect(html).toContain('lg:flex-col');
    expect(html.replace(/<!-- -->/g, '')).toMatch(
      /<p aria-hidden="true" class="[^"]*lg:hidden">1 \/ 3<\/p>/,
    );
  });

  it('한 장이면 모바일 위치 글자를 두지 않는다', () => {
    const html = renderToStaticMarkup(
      <ProductGallery name="x" thumbnail={null} images={[image('a', 0)]} />,
    );
    expect(html).not.toContain('lg:hidden');
  });
});
