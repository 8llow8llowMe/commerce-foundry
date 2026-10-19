import type { HomeSection } from '@cf/ui';

// MOKOYURI 메인 6개 영역 (#59). 문구는 시안이고 고객사 확인 대상이다.
// 화보는 촬영 전이라 image 가 null 이다 — placeholder 에 필요한 사진 규격을 적어 두고 그대로 고객사에 전달한다.
// 메인 전시 API(#63)가 생기면 이 파일 대신 API 응답을 쓴다.
export const home: HomeSection[] = [
  {
    type: 'HERO',
    id: 'main-visual',
    title: ['The Poetry of', 'Everyday Romance'],
    subtitle: '전통의 고운 선, 오늘의 로맨스.',
    cta: { label: 'EXPLORE COLLECTION', href: '/collection' },
    desktop: { image: null, placeholder: '시즌 대표 화보 · PC 가로 16:9 · 전신 · 자연광' },
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
    body: '숲의 빛을 닮은 분홍과 한복의 고운 선이 만나는 컬렉션. 한 벌의 옷이 한 장의 화보가 되는 순간을 담았습니다.',
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
    body: '화보 속 착장을 그대로. 함께 입은 옷을 한 번에 둘러보세요.',
    media: { image: null, placeholder: '착장 화보 · 세로 4:5 · 볼레로 + 스커트' },
    // 룩 API(#62) 전까지 구성 상품 id 를 여기 둔다. 실제 상품 등록 후 채운다
    productIds: [],
    link: { label: 'SHOP ALL', href: '/shop' },
  },
  {
    type: 'DETAIL',
    id: 'craft-details',
    title: 'The Beauty of Craft',
    body: '섬세한 바느질과 전통의 결이 만나 오늘의 특별한 옷이 됩니다.',
    points: [
      { title: '7개 패널 상체 구조', text: '몸의 곡선을 따라 나눈 패널로 단정하게 감싸는 실루엣.' },
      { title: '이중 맞주름', text: '걸을 때마다 풍성하게 흔들리는 치마의 볼륨.' },
      { title: '깨끼바느질', text: '솔기를 곱게 감싸는 전통 바느질로 마감한 안과 밖.' },
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
    body: '한복의 여밈과 치마의 실루엣, 전통 문양에서 출발해 오늘 입는 옷을 만듭니다.',
    links: [{ label: 'OUR STORY', href: '/our-story' }],
    media: [{ image: null, placeholder: '브랜드 · 작업실 컷 · 가로 3:2' }],
    layout: 'centered',
  },
];
