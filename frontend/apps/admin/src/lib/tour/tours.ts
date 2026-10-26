/**
 * 어드민 온보딩 투어 · 도움말 내용(#228). 화면 요소는 `data-tour="<target>"` 속성으로 가리킨다 —
 * 클래스 · 문구가 바뀌어도 투어가 깨지지 않게, 투어가 가리키는 요소에만 단다.
 *
 * 문구는 평문만 쓴다. 투어 라이브러리(driver.js)가 제목 · 설명을 innerHTML 로 넣기 때문이다(운영 데이터를 넣지 않는다).
 * 화면이 생기면(상품 등록 #29 · 주문 · 송장 #30 · 리포트 #221) 여기에 화면과 투어를 더한다.
 */

export type TourStep = {
  /** `data-tour` 값. null 이면 화면 가운데 안내(가리킬 요소 없음) */
  target: string | null;
  title: string;
  body: string;
  side?: 'top' | 'right' | 'bottom' | 'left';
  /**
   * 없을 수도 있는 요소(빈 목록 · 템플릿을 고르기 전의 표 등). 없으면 이 단계만 빼고 투어는 돈다.
   * 아니면(필수) 요소가 없을 때 투어를 시작하지 않고 본 것으로도 남기지 않는다 — 다음에 들어올 때 다시 본다.
   */
  optional?: boolean;
};

export type Tour = {
  /** 진행 상태 저장 키. 내용이 크게 바뀌면 뒤에 버전을 올려 다시 보이게 한다 */
  id: string;
  steps: TourStep[];
};

export type HelpTopic = {
  /** 도움말 패널 제목(화면 이름) */
  title: string;
  /** 화면 한두 줄 설명 */
  summary: string;
  faq: { question: string; answer: string }[];
  /** 이 화면의 투어. 없으면 "다시 보기" 를 두지 않는다 */
  tour: Tour | null;
};

/** 콘솔에 처음 들어오면 어느 화면이든 먼저 보이는 투어 — 메뉴 · 알림 · 도움말 */
export const CONSOLE_TOUR: Tour = {
  id: 'console-v1',
  steps: [
    {
      target: null,
      title: '어드민에 오신 것을 환영합니다',
      body: '처음 쓰는 화면에서는 이렇게 주요 요소를 하나씩 짚어 드립니다. 언제든 닫을 수 있고, 다시 보려면 위쪽 도움말(?)을 누르세요.',
    },
    {
      target: 'nav',
      title: '메뉴',
      body: '상품 · 전시 · 배송 · 회원 · 운영 화면이 여기 모여 있습니다. 지금 보고 있는 화면은 밝게 표시됩니다.',
      side: 'right',
    },
    {
      target: 'notifications',
      title: '알림',
      body: '처리할 일이나 작업 결과가 생기면 숫자가 붙습니다. 오래 걸리는 작업을 시작하면 그 옆에 진행 표시도 나타납니다.',
      side: 'bottom',
    },
    {
      target: 'help',
      title: '도움말',
      body: '화면마다 짧은 설명과 자주 묻는 질문이 있습니다. 이 안내를 다시 보고 싶을 때도 여기서 엽니다.',
      side: 'bottom',
    },
  ],
};

const STOCKS_TOUR: Tour = {
  id: 'stocks-v1',
  steps: [
    {
      target: 'product-search',
      title: '상품 찾기',
      body: '상품명으로 찾습니다. 재고는 상품을 고른 뒤 옵션(SKU)별로 봅니다.',
    },
    {
      target: 'product-list',
      title: '상품 고르기',
      body: '상품을 누르면 옵션별 판매 가능 수량 · 결제 대기 수량을 보고 조정할 수 있습니다. 숨긴 상품은 아직 고를 수 없습니다.',
      side: 'top',
      optional: true,
    },
  ],
};

const STOCK_DETAIL_TOUR: Tour = {
  id: 'stock-detail-v1',
  steps: [
    {
      target: 'stock-table',
      title: '옵션별 재고',
      body: '판매 가능 수량은 결제를 기다리는 주문 몫이 이미 빠진 값입니다. 실사 수량을 넣을 때는 결제 대기 수량을 빼고 넣어 주세요.',
      side: 'top',
    },
    {
      target: null,
      title: '조정과 이력',
      body: '줄마다 "조정" 으로 수량 · 관리 여부를 바꾸고 "이력" 으로 지난 조정을 봅니다. 그사이 주문으로 수량이 바뀌었으면 덮어쓰지 않고 다시 불러옵니다.',
    },
  ],
};

const SIZE_CHART_PICK_TOUR: Tour = {
  id: 'size-charts-v1',
  steps: [
    {
      target: 'product-search',
      title: '사이즈표를 넣을 상품 찾기',
      body: '상품명으로 찾아 고르면 그 상품의 사이즈표 입력 화면으로 갑니다. 숨긴 상품도 고를 수 있습니다.',
    },
  ],
};

const SIZE_CHART_EDITOR_TOUR: Tour = {
  id: 'size-chart-editor-v1',
  steps: [
    {
      target: 'size-template',
      title: '템플릿 고르기',
      body: '측정 항목과 단위는 템플릿이 정합니다. 고르면 치수 표가 나타납니다. 맞는 템플릿이 없으면 "템플릿 관리" 에서 먼저 만드세요.',
    },
    {
      target: 'size-grid',
      title: '치수 넣기',
      body: '사이즈마다 치수를 숫자로 넣습니다. 엑셀 · 구글 시트에서 표를 복사해 아무 칸에 붙여넣으면 그 칸부터 채워집니다.',
      side: 'top',
      optional: true,
    },
    {
      target: 'size-save',
      title: '저장',
      body: '저장하면 스토어 상품 상세의 사이즈 가이드에 바로 보입니다. 사이즈표가 없는 상품은 기존 안내가 그대로 보입니다.',
      side: 'top',
    },
  ],
};

const SIZE_TEMPLATES_TOUR: Tour = {
  id: 'size-chart-templates-v1',
  steps: [
    {
      target: 'template-new',
      title: '새 템플릿',
      body: '상의 · 하의처럼 재는 항목이 같은 상품끼리 템플릿 하나를 같이 씁니다. 항목 이름과 재는 방법(설명)을 적어 두면 고객에게도 보입니다.',
      side: 'left',
    },
    {
      target: 'template-list',
      title: '고치기 · 삭제',
      body: '상품 사이즈표가 쓰는 템플릿은 이름 · 설명 · 순서만 바꿀 수 있고 삭제할 수 없습니다. 구조가 다르면 새 템플릿을 만드세요.',
      side: 'top',
      optional: true,
    },
  ],
};

const COMMON_FAQ = [
  {
    question: '로그인이 자꾸 풀려요.',
    answer:
      '보안을 위해 로그인은 30분 동안 유지됩니다. 저장하다 풀리면 입력값은 그대로 남으니, 새 탭에서 다시 로그인한 뒤 저장하세요.',
  },
];

type Route = { pattern: RegExp; topic: HelpTopic };

/** 경로 → 도움말. 위에서부터 먼저 맞는 것이다 */
const ROUTES: Route[] = [
  {
    pattern: /^\/$/,
    topic: {
      title: '어드민 홈',
      summary: '왼쪽 메뉴에서 관리할 화면을 고릅니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/products$/,
    topic: {
      title: '상품 목록',
      summary:
        '숨긴 상품까지 모든 상품을 찾고 판매 상태 · 재고 반영 판매 가능 여부를 봅니다. 상품 등록 · 수정 화면은 준비 중입니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/stocks$/,
    topic: {
      title: '재고',
      summary: '상품을 골라 옵션(SKU)별 재고를 보고 조정합니다.',
      faq: [
        {
          question: '판매 가능 수량과 실제 창고 수량이 달라요.',
          answer:
            '판매 가능 수량은 결제를 기다리는 주문 몫이 빠진 값입니다. 결제가 끝나지 않고 시간이 지나면 그 몫은 다시 돌아옵니다.',
        },
        ...COMMON_FAQ,
      ],
      tour: STOCKS_TOUR,
    },
  },
  {
    pattern: /^\/stocks\/[^/]+$/,
    topic: {
      title: '옵션별 재고',
      summary:
        '옵션별 수량을 조정하고 조정 이력을 봅니다. 수량은 바꿀 결과 수량을 그대로 넣습니다.',
      faq: [
        {
          question: '"그사이 수량이 바뀌었습니다" 가 떠요.',
          answer:
            '조정하는 동안 주문이 들어와 수량이 바뀐 것입니다. 화면이 최신 수량으로 바뀌었으니 확인한 뒤 다시 조정하세요.',
        },
        ...COMMON_FAQ,
      ],
      tour: STOCK_DETAIL_TOUR,
    },
  },
  {
    pattern: /^\/size-charts$/,
    topic: {
      title: '상품 사이즈표',
      summary: '사이즈표를 입력할 상품을 고릅니다.',
      faq: COMMON_FAQ,
      tour: SIZE_CHART_PICK_TOUR,
    },
  },
  {
    pattern: /^\/size-charts\/[^/]+$/,
    topic: {
      title: '사이즈표 입력',
      summary:
        '템플릿을 고르고 사이즈별 치수를 넣어 저장합니다. 저장하면 스토어 상품 상세에 표로 보입니다.',
      faq: [
        {
          question: '시트에서 붙여넣었는데 일부 칸이 빨갛게 나와요.',
          answer:
            '치수 칸에는 숫자만 넣을 수 있습니다(소수 둘째 자리까지). "52cm" 처럼 단위가 붙은 값은 숫자만 남기세요.',
        },
        {
          question: '다른 템플릿으로 바꾸면 입력한 값은요?',
          answer: '항목이 달라 치수는 지워지고 사이즈 이름만 남습니다.',
        },
        ...COMMON_FAQ,
      ],
      tour: SIZE_CHART_EDITOR_TOUR,
    },
  },
  {
    pattern: /^\/size-chart-templates$/,
    topic: {
      title: '사이즈표 템플릿',
      summary: '사이즈표의 측정 항목과 단위를 정합니다. 상품은 템플릿을 골라 치수만 넣습니다.',
      faq: [
        {
          question: '템플릿 단위를 바꾸거나 항목을 더할 수 없어요.',
          answer:
            '그 템플릿을 쓰는 상품 사이즈표가 있으면 구조를 바꿀 수 없습니다. 새 템플릿을 만들어 쓰세요.',
        },
        ...COMMON_FAQ,
      ],
      tour: SIZE_TEMPLATES_TOUR,
    },
  },
  {
    pattern: /^\/categories$/,
    topic: {
      title: '카테고리',
      summary: '스토어 카테고리 구조와 노출 여부를 관리합니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/displays\/main$/,
    topic: {
      title: '메인 전시',
      summary:
        '스토어 메인 화면 섹션의 순서 · 노출 · 내용을 고칩니다. 메인 반영은 최대 약 1분 걸립니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/shipping-policies\/(domestic|international)$/,
    topic: {
      title: '배송비 정책',
      summary: '국내 · 해외 배송비 기준을 정합니다. 바꾼 기록은 아래 변경 이력에 남습니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/members$/,
    topic: {
      title: '회원 목록',
      summary: '회원을 찾아봅니다. 개인정보는 가려서 보입니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/jobs(\/[^/]+)?$/,
    topic: {
      title: '백그라운드 작업',
      summary:
        '오래 걸리는 작업의 진행 상태와 결과를 봅니다. 실행 중인 작업은 위쪽 진행 표시에서도 볼 수 있습니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/audit-logs$/,
    topic: {
      title: '관리자 감사 로그',
      summary: '관리자가 한 일의 기록을 찾아봅니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
];

const FALLBACK: HelpTopic = {
  title: '도움말',
  summary: '이 화면의 설명은 준비 중입니다.',
  faq: COMMON_FAQ,
  tour: null,
};

export function helpTopicFor(pathname: string): HelpTopic {
  return ROUTES.find((route) => route.pattern.test(pathname))?.topic ?? FALLBACK;
}

/** 이 화면에서 처음이면 자동으로 볼 투어 순서 — 콘솔 투어가 먼저, 그다음 화면 투어 */
export function toursFor(pathname: string): Tour[] {
  const screen = helpTopicFor(pathname).tour;
  return screen ? [CONSOLE_TOUR, screen] : [CONSOLE_TOUR];
}

/** 모든 투어(콘솔 + 화면) — 아이디 중복 · 문구 검사용 */
export const ALL_TOURS: readonly Tour[] = [
  CONSOLE_TOUR,
  ...ROUTES.flatMap((route) => (route.topic.tour ? [route.topic.tour] : [])),
];
