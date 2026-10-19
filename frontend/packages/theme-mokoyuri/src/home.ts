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
    // 시안의 'MOKOYURI 2026 COLLECTION' 라벨은 버튼 문구로 옮겼다(#59)
    cta: { label: '2026 컬렉션 보기', href: '/collection' },
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
    body: '숲의 빛을 닮은 분홍과 한복의 고운 선이 만나는 컬렉션. 한 벌의 옷이 한 장의 화보가 되는 순간을 담았습니다.',
    links: [{ label: 'SEE THE COLLECTION', href: '/collection' }],
    // 캡션은 시안 문구 — 고객사 확인 대상. 사진 3장은 같은 빛 · 같은 톤으로 촬영 요청
    media: [
      {
        image: null,
        placeholder: '컬렉션 화보 1 · 전폭(PC 가로 · 모바일 세로 크롭) · 같은 빛 · 같은 톤',
        caption: '숲으로 들어서는 아침',
      },
      {
        image: null,
        placeholder: '컬렉션 화보 2 · 전폭 · 치마 실루엣이 크게',
        caption: '바람에 흔들리는 이중 맞주름',
      },
      {
        image: null,
        placeholder: '컬렉션 화보 3 · 전폭 · 뒷모습 · 리본 여밈',
        caption: '돌아서는 순간의 리본',
      },
    ],
    layout: 'curtain',
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
    // 같은 드레스 전신 한 장을 단계마다 확대한다. focus 는 촬영본이 오면 실제 위치로 맞춘다
    frame: { image: null, placeholder: '윤금 드레스 전신 · 세로 4:5 · 고해상도(320% 확대용)' },
    points: [
      {
        title: '7개 패널 상체 구조',
        text: '몸의 곡선을 따라 나눈 패널로 단정하게 감싸는 실루엣.',
        focus: { x: 0.5, y: 0.28, scale: 1.8 },
      },
      {
        title: '이중 맞주름',
        text: '걸을 때마다 풍성하게 흔들리는 치마의 볼륨.',
        focus: { x: 0.42, y: 0.68, scale: 3.2 },
      },
      {
        title: '깨끼바느질',
        text: '솔기를 곱게 감싸는 전통 바느질로 마감한 안과 밖.',
        focus: { x: 0.6, y: 0.86, scale: 3.2 },
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
    // 문구는 시안 초안 — 고객사 확인 대상. 단락은 빈 줄로 나눈다(브랜드 철학 / 디자이너 이야기)
    title: '한복에서 시작한 로맨스',
    body: '한복의 여밈과 치마의 실루엣, 전통 문양에서 출발해 오늘 입는 옷을 만듭니다. 특별한 날의 옷이 아니라 매일 꺼내 입고 싶은 한 벌을 생각합니다.\n\n디자이너는 어린 시절 할머니의 저고리에서 처음 선의 아름다움을 배웠습니다. 그 곡선을 지금의 몸과 생활에 맞게 다시 그립니다.',
    links: [{ label: '브랜드 이야기', href: '/our-story' }],
    media: [
      { image: null, placeholder: '디자이너 · 작업실 인물 컷 · 세로 3:4 · 배래 곡선으로 자름' },
    ],
    layout: 'panel',
  },
];
