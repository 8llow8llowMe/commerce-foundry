import sanitizeHtml from 'sanitize-html';

/**
 * 상품 상세 HTML(어드민이 쓴 원문, BE 가 정제하지 않는다)을 허용 목록으로 거른다. 서버 컴포넌트에서만 부른다.
 * 허용 목록 밖은 모두 버린다 — script · iframe · on* 속성 · javascript: URL · style 은 들어오지 않는다.
 */
export function sanitizeProductHtml(html: string): string {
  return sanitizeHtml(html, {
    allowedTags: (
      'p br hr h2 h3 h4 strong b em i u s small ul ol li blockquote figure figcaption img a ' +
      'table thead tbody tr th td span div'
    ).split(' '),
    allowedAttributes: {
      // target · rel · loading · decoding 은 아래 transformTags 가 덮어쓴 값만 남는다
      a: ['href', 'title', 'target', 'rel'],
      img: ['src', 'alt', 'width', 'height', 'loading', 'decoding'],
      th: ['colspan', 'rowspan', 'scope'],
      td: ['colspan', 'rowspan'],
    },
    allowedSchemes: ['https', 'http', 'mailto'],
    allowedSchemesByTag: { img: ['https', 'http'] },
    allowProtocolRelative: false,
    transformTags: {
      // 바깥 링크는 새 창 + opener 차단, 이미지는 지연 로딩
      a: sanitizeHtml.simpleTransform('a', { target: '_blank', rel: 'noopener noreferrer' }),
      img: sanitizeHtml.simpleTransform('img', { loading: 'lazy', decoding: 'async' }),
    },
  });
}
