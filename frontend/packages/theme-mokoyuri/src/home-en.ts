import type { HomeSection } from '@cf/ui';

// MOKOYURI 영문 메인 (#31). 국문 home.ts 와 같은 구성 · 같은 화보 자리이고, 영문 문구는 초안이라 고객사 확인 대상이다.
// 화보는 촬영 전이라 image 가 null 이다 — placeholder 에 필요한 사진 규격을 적어 두고 그대로 고객사에 전달한다.
// 메인 전시 API(#63)가 생기면 이 파일 대신 API 응답을 쓴다. 화보 placeholder 는 촬영 안내라 국문 그대로 둔다.
export const homeEn: HomeSection[] = [
  {
    type: 'HERO',
    id: 'main-visual',
    title: ['The Poetry of', 'Everyday Romance'],
    subtitle: 'Graceful lines of tradition, romance for today.',
    cta: { label: '2026 Collection', href: '/collection' },
    secondaryCta: { label: 'SHOP', href: '/shop' },
    desktop: {
      image: null,
      placeholder:
        '시즌 대표 화보 · 세로 4:5 · 전신 · 자연광 · 치마 아랫단이 화면 아래로 잘리는 구도',
    },
    mobile: { image: null, placeholder: '시즌 대표 화보 · 모바일 세로 4:5' },
    video: null,
  },
  {
    type: 'PRODUCT_PICK',
    id: 'romantic-edit',
    title: 'The Romantic Edit',
    viewAll: { label: 'VIEW ALL', href: '/shop' },
    source: { categoryId: null, limit: 4 },
  },
  {
    type: 'STORY',
    id: 'collection-story',
    title: 'Pink Forest',
    body: 'A collection where forest-light pink meets the graceful lines of hanbok — each piece a picture of its own.',
    links: [{ label: 'SEE THE COLLECTION', href: '/collection' }],
    media: [
      { image: null, placeholder: '컬렉션 화보 · 세로 3:4' },
      { image: null, placeholder: '컬렉션 디테일 컷 · 세로 3:4' },
    ],
    layout: 'asymmetric',
  },
  {
    type: 'LOOK',
    id: 'shop-the-look',
    title: 'Shop the Look',
    body: 'Wear the look as styled. Discover every piece of the outfit together.',
    media: { image: null, placeholder: '착장 화보 · 세로 4:5 · 볼레로 + 스커트' },
    // 국문 home.ts 의 LOOK 과 같은 상품을 둔다
    productIds: [],
    link: { label: 'SHOP ALL', href: '/shop' },
  },
  {
    type: 'DETAIL',
    id: 'craft-details',
    title: 'The Beauty of Craft',
    body: 'Delicate stitching and traditional texture come together in clothes for today.',
    points: [
      {
        title: 'Seven-panel bodice',
        text: 'Panels cut along the curves of the body for a neat, enveloping silhouette.',
      },
      { title: 'Double box pleats', text: 'A full skirt that sways with every step.' },
      {
        title: 'Kkekki stitching',
        text: 'Traditional seam-enclosing stitches, finished inside and out.',
      },
    ],
    media: [
      { image: null, placeholder: '소재 클로즈업 · 자카드 · 3:4' },
      { image: null, placeholder: '리본 · 여밈 디테일 · 3:4' },
      { image: null, placeholder: '바느질 디테일 · 3:4' },
    ],
  },
  {
    type: 'STORY',
    id: 'our-story',
    title: 'From Hanbok, Toward Today',
    body: 'From the wrap of hanbok, the silhouette of its skirt and traditional motifs, we make clothes to wear today.',
    links: [{ label: 'OUR STORY', href: '/our-story' }],
    media: [{ image: null, placeholder: '브랜드 · 작업실 컷 · 가로 3:2' }],
    layout: 'centered',
  },
];
