/**
 * 어드민 온보딩 투어 · 도움말 내용(#228). 화면 요소는 `data-tour="<target>"` 속성으로 가리킨다 —
 * 클래스 · 문구가 바뀌어도 투어가 깨지지 않게, 투어가 가리키는 요소에만 단다.
 *
 * 문구는 평문만 쓴다. 투어 라이브러리(driver.js)가 제목 · 설명을 innerHTML 로 넣기 때문이다(운영 데이터를 넣지 않는다).
 * 화면이 생기면(리포트 #221 등) 여기에 화면과 투어를 더한다.
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
      body: '상품 · 주문 · 전시 · 배송 · 회원 · 운영 화면이 여기 모여 있습니다. 지금 보고 있는 화면은 밝게 표시됩니다.',
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

/**
 * 사이즈표 입력. v2 — AI 초안(#218 · BE #217)이 열려 초안 요청 · 검토 단계를 더했다.
 * 검토 칸(`size-ai-review`)은 초안을 표에 불러왔을 때만 있어 선택이다. 투어는 초안을 요청하지 않는다(유료 AI 호출).
 */
const SIZE_CHART_EDITOR_TOUR: Tour = {
  id: 'size-chart-editor-v2',
  steps: [
    {
      target: 'size-template',
      title: '템플릿 고르기',
      body: '측정 항목과 단위는 템플릿이 정합니다. 고르면 치수 표가 나타납니다. 맞는 템플릿이 없으면 "템플릿 관리" 에서 먼저 만드세요.',
    },
    {
      target: 'size-ai-draft',
      title: 'AI 초안',
      body: '기존 상세 설명 · 다른 표의 치수 부분만 붙여넣고 요청하면 고른 템플릿에 맞춘 초안을 만듭니다. 생성 중에는 다른 화면으로 가도 되고, 끝나면 알림으로 알려 드립니다. 지금은 텍스트만 받습니다.',
    },
    {
      target: 'size-grid',
      title: '치수 넣기',
      body: '사이즈마다 치수를 숫자로 넣습니다. 엑셀 · 구글 시트에서 표를 복사해 아무 칸에 붙여넣으면 그 칸부터 채워집니다.',
      side: 'top',
      optional: true,
    },
    {
      target: 'size-ai-review',
      title: '초안 검토',
      body: '초안을 표에 불러오면 빈 치수를 채우고 AI 가 표시한 경고를 하나씩 확인합니다. 모두 확인해야 적용할 수 있습니다.',
      side: 'top',
      optional: true,
    },
    {
      target: 'size-save',
      title: '저장 · 초안 적용',
      body: '저장하면 스토어 상품 상세의 사이즈 가이드에 바로 보입니다. 초안을 검토 중이면 이 버튼이 "초안 적용" 이 되고, 그사이 누가 표를 고쳤으면 덮어쓰지 않습니다.',
      side: 'top',
    },
  ],
};

/**
 * 알림 센터 · 백그라운드 작업(#214 · #272). 알림 버튼은 상단 바에 늘 있어 필수, 목록 조건 · 표는 작업 목록에만 있어
 * 선택이다(작업 상세에서도 같은 투어가 알림 단계만으로 돈다). 상단 작업 진행 표시는 지켜볼 작업이 있을 때만 그려져 가리키지 않는다.
 */
const JOBS_TOUR: Tour = {
  id: 'jobs-v1',
  steps: [
    {
      target: 'notifications',
      title: '알림 센터',
      body: '작업이 끝나거나 실패하면 알림이 쌓이고 숫자가 붙습니다. 알림을 누르면 그 작업 화면으로 가고, 모두 읽음으로 한 번에 정리할 수 있습니다.',
      side: 'bottom',
    },
    {
      target: 'job-filters',
      title: '작업 찾기',
      body: '상태 · 종류로 거릅니다. 실패한 작업만 모아 이유를 확인할 때 쓰세요.',
      optional: true,
    },
    {
      target: 'job-table',
      title: '진행과 결과',
      body: '진행률과 서버 안내(실패 이유 · 재시도 대기)를 그대로 보입니다. 작업을 누르면 상세에서 취소하거나 결과를 봅니다.',
      side: 'top',
      optional: true,
    },
    {
      target: null,
      title: '진행 표시',
      body: 'AI 사이즈표 초안처럼 오래 걸리는 작업을 시작하면 상단 바에 진행 표시가 생깁니다. 다른 화면으로 가도 계속 보이고, 끝나면 알림으로도 알려 드립니다.',
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

/** 국내 배송비 정책(#358 시작 체크리스트의 업무 화면). 서버 허용 목록(`operations.onboarding.guides`)에 `shipping-domestic` 이 있다(BE #412) */
export const SHIPPING_DOMESTIC_TOUR: Tour = {
  id: 'shipping-domestic-v1',
  steps: [
    {
      target: 'shipping-base-fee',
      title: '기본 배송비',
      body: '주문 한 건에 붙는 배송비와 무료배송 기준을 넣습니다. 무료배송 기준을 비우면 금액과 관계없이 배송비가 붙습니다.',
    },
    {
      target: 'shipping-remote-areas',
      title: '도서산간 추가 배송비',
      body: '우편번호 구간별로 더 받는 배송비입니다. 필요 없으면 비워 두어도 됩니다.',
      side: 'top',
      optional: true,
    },
    {
      target: 'policy-save',
      title: '저장',
      body: '저장해야 스토어 주문에 반영되고 시작 체크리스트의 업무도 완료됩니다. 이 안내를 끝까지 보는 것만으로는 완료되지 않습니다.',
      side: 'top',
    },
  ],
};

/**
 * 상품 등록(#29 · #358 시작 체크리스트의 업무 화면). 가리키는 앵커(`product-basic` · `product-images` · `product-price` ·
 * `product-options` · `product-skus` · `product-submit`)는 상품 등록 폼(`ProductCreateForm`)의 구역마다 달려 있다 —
 * 이름을 바꾸면 폼과 같이 바꾼다(폼 테스트가 이 정의의 앵커를 확인한다).
 * v2 — 이미지 첨부(BE #9 F2)가 열려 이미지 단계를 더했다. 서버 허용 목록(`operations.onboarding.guides`)에 `product-new` 가
 * 오르기 전에는 이 브라우저 기록으로만 정한다(`progress.ts` 의 `writableGuide`).
 * 안내는 위치 설명만 한다. 등록 버튼을 대신 누르지 않는다(투어 중에는 가리킨 요소를 누를 수 없다).
 */
export const PRODUCT_NEW_TOUR: Tour = {
  id: 'product-new-v2',
  steps: [
    {
      target: 'product-basic',
      title: '기본 정보',
      body: '상품명 · 요약 · 상세 설명을 넣습니다. 아래로 내려가며 이미지 · 카테고리 · 판매 · 노출 · 가격 · 프리오더 · 옵션을 차례로 채웁니다.',
    },
    {
      target: 'product-images',
      title: '이미지',
      body: '대표 이미지 한 장과 추가 이미지를 고르면 바로 올라갑니다. 올리는 동안에는 등록할 수 없습니다. 대체 텍스트는 이미지를 볼 수 없는 고객에게 읽어 줍니다. 이미지 없이도 등록할 수 있습니다.',
      side: 'top',
    },
    {
      target: 'product-price',
      title: '통화 · 가격',
      body: '판매가 · 정가 · 옵션 추가금은 모두 고른 통화로 받고 환율로 바꾸지 않습니다. 정가는 할인 전 가격이라 판매가보다 높아야 하고, 할인이 없으면 비워 둡니다.',
      side: 'top',
    },
    {
      target: 'product-options',
      title: '옵션',
      body: '색상 · 사이즈처럼 고객이 고르는 옵션 이름과 값을 넣습니다. 옵션이 없는 상품이면 비워 둡니다.',
      side: 'top',
    },
    {
      target: 'product-skus',
      title: '옵션 조합(SKU)',
      body: '옵션 값을 넣으면 조합마다 한 줄씩 생깁니다. 줄마다 추가금과 처음 재고를 정합니다. 등록한 뒤 재고는 재고 화면에서 조정합니다.',
      side: 'top',
    },
    {
      target: 'product-submit',
      title: '등록',
      body: '등록하면 상품 상세로 옮기고 시작 체크리스트의 업무도 완료됩니다. 이 안내를 끝까지 보는 것만으로는 완료되지 않습니다. 같은 내용으로 두 번 등록하면 상품이 두 개 생기니, 결과를 확인하지 못했다고 나오면 상품 목록에서 먼저 확인하세요.',
      side: 'top',
    },
  ],
};

/**
 * 상품 기본 정보 수정(#29). 앵커는 `ProductHeaderEditForm` 에 있다 — 서버에서 상품을 읽은 뒤에만 폼이 그려지고,
 * 권한이 없으면 폼 대신 권한 안내가 그려져 필수 요소가 없으니 투어가 시작하지 않는다.
 */
const PRODUCT_EDIT_TOUR: Tour = {
  id: 'product-edit-v1',
  steps: [
    {
      target: 'product-edit-text',
      title: '상품 문구',
      body: '상품명 · 요약 · 상세 설명을 고칩니다. 카테고리 · 가격 · 옵션 · 이미지는 상품 상세의 구역별 수정 링크에서 따로 고칩니다.',
    },
    {
      target: 'product-edit-status',
      title: '판매 상태 · 노출',
      body: '판매 상태와 스토어 노출을 바꿉니다. 노출을 끄면 고객에게 상품이 보이지 않지만 상품과 주문 기록은 그대로 남습니다.',
      side: 'top',
    },
    {
      target: 'product-edit-save',
      title: '저장',
      body: '바뀐 내용이 있을 때만 저장할 수 있습니다. 그사이 다른 곳에서 상품이 바뀌었으면 덮어쓰지 않고 알려 주니, 최신 내용을 불러와 다시 고치세요.',
      side: 'top',
    },
  ],
};

/**
 * 상품 목록(#208) — 상품 등록 시작점 · 조건 · 표. 빈 목록이면 표가 없어 선택 단계다.
 * 등록 자체는 상품 등록 화면의 투어(`product-new`)가 안내한다 — 여기서는 어디서 시작하는지만 가리킨다.
 */
const PRODUCTS_TOUR: Tour = {
  id: 'products-v1',
  steps: [
    {
      target: 'product-create',
      title: '상품 등록',
      body: '새 상품은 여기서 등록합니다. 숨김으로 등록해 두고 상세에서 확인한 뒤 노출할 수 있습니다.',
      side: 'bottom',
    },
    {
      target: 'product-filters',
      title: '상품 찾기',
      body: '상품명 · 카테고리 · 판매 상태 · 노출 · 판매 가능 여부로 찾고 정렬합니다. 숨긴 상품 · 판매 종료 상품도 목록에 나옵니다.',
    },
    {
      target: 'product-table',
      title: '상품 고르기',
      body: '상품명을 누르면 상세를 봅니다. 판매 가능은 재고를 반영한 값이고, 줄 끝의 재고 · 원가 · 사이즈표로 바로 갑니다. 숨긴 상품은 재고 · 원가 화면을 아직 열 수 없습니다.',
      side: 'top',
      optional: true,
    },
  ],
};

/**
 * 상품 상세(#418) — 구역별 수정 링크 · 관련 화면 · SKU · 판매 중지와 삭제. 서버에서 상품을 읽은 뒤에만 그려진다.
 * 투어는 위치만 알려 주고 판매 중지 · 삭제 버튼을 누르지 않는다.
 */
const PRODUCT_DETAIL_TOUR: Tour = {
  id: 'product-detail-v1',
  steps: [
    {
      target: 'product-edit-links',
      title: '구역별 수정',
      body: '기본 정보 · 가격 · 카테고리 · 이미지 · 옵션과 SKU · 맞춤 제작을 구역마다 따로 고칩니다. 한 구역을 저장해도 다른 구역은 바뀌지 않습니다.',
      side: 'bottom',
    },
    {
      target: 'product-related',
      title: '재고 · 원가 · 사이즈표',
      body: '이 상품의 재고 조정 · 원가 · 사이즈표 화면으로 갑니다. 재고는 상품 상세가 아니라 재고 화면에서 조정합니다.',
      side: 'bottom',
    },
    {
      target: 'product-detail-skus',
      title: 'SKU',
      body: '옵션 조합마다 하나씩 있는 판매 단위입니다. 판매 종료한 SKU 도 기록으로 남아 표시가 붙습니다.',
      side: 'top',
    },
    {
      target: 'product-delete',
      title: '판매 중지 · 삭제',
      body: '맨 아래에 있습니다. 누르면 확인 단계가 먼저 열립니다. 삭제하면 고객 화면과 상품 목록에서 빠지고 되돌릴 수 없으니, 판매만 멈추려면 기본 정보 수정에서 노출을 끄세요. 이미 들어온 주문 · 재고 기록은 남습니다.',
      side: 'top',
      optional: true,
    },
  ],
};

/** 결제 확인 목록(#30 · #504) — 조건 · 표 · 읽기 전용 안내. 빈 목록이면 표가 없어 선택 단계다 */
const PAYMENTS_TOUR: Tour = {
  id: 'payments-v1',
  steps: [
    {
      target: 'payment-filters',
      title: '결제 찾기',
      body: '결제 상태 · 주문 아이디 · 주문 회신 여부로 찾습니다. 위쪽 바로가기로 자주 보는 조건을 한 번에 고릅니다.',
    },
    {
      target: 'payment-table',
      title: '결제 보기',
      body: '결제 시도마다 상태 · 수단 · 원금 · 주문 회신을 봅니다. 원금은 환불 뒤 잔액이 아닙니다. 주문 아이디를 누르면 주문 상세로 갑니다.',
      side: 'top',
      optional: true,
    },
    {
      target: null,
      title: '읽기 전용',
      body: '이 화면은 저장된 기록만 보여 주고 결제사에 다시 조회하거나 승인 · 취소하지 않습니다. 주문 회신이 미수신이라고 해서 곧바로 복구할 결제는 아닙니다.',
    },
  ],
};

/** 주문 목록(#30) — 조건 · 표 · 일괄 변경 · 송장 일괄 등록. 빈 목록이면 표가 없어 선택 단계다 */
const ORDERS_TOUR: Tour = {
  id: 'orders-v2',
  steps: [
    {
      target: 'order-filters',
      title: '주문 찾기',
      body: '주문 상태와 주문일로 찾습니다. 주문일은 한국 날짜 기준이고 시작일 · 종료일을 모두 포함합니다.',
    },
    {
      target: 'order-table',
      title: '주문 고르기',
      body: '주문 이름을 누르면 상세와 상태 이력을 봅니다. 앞의 칸을 체크하면 아래에서 한 번에 상태를 바꿀 수 있습니다.',
      side: 'top',
      optional: true,
    },
    {
      target: 'order-bulk',
      title: '상태 일괄 변경',
      body: '고른 주문을 상품 준비중 · 제작 중으로 한 번에 바꿉니다. 바꿀 수 없는 주문은 결과에 이유가 나오고, 실패한 주문만 다시 보낼 수 있습니다.',
      side: 'top',
    },
    {
      target: 'order-shipment-bulk',
      title: '송장 일괄 등록',
      body: '상품 준비중 주문에 택배사 · 송장 번호를 넣어 한 번에 출고합니다. 표에서 고른 주문을 넣거나 엑셀에서 복사한 표를 붙여넣습니다. 출고된 송장은 바꿀 수 없으니 번호를 확인한 뒤 등록하세요.',
      side: 'top',
    },
  ],
};

/**
 * 주문 상세(#30) — 현재 상태 · 상태 변경 · 송장 등록 · 상태 이력. 바꿀 수 있는 상태가 없으면 버튼 대신 안내 문구가 같은 앵커를 단다.
 * 송장 등록은 상품 준비중 주문에만 있어 선택 단계다.
 */
const ORDER_DETAIL_TOUR: Tour = {
  id: 'order-detail-v2',
  steps: [
    {
      target: 'order-status',
      title: '현재 상태',
      body: '주문의 지금 상태입니다. 결제를 기다리는 주문은 결제 기한도 함께 보입니다.',
    },
    {
      target: 'order-status-actions',
      title: '상태 바꾸기',
      body: '지금 바꿀 수 있는 상태만 버튼으로 나옵니다. 누르면 사유를 적는 확인 단계가 열리고, 보내기 직전에 주문을 다시 읽어 그사이 바뀌었으면 보내지 않습니다. 배송 완료로 바꿀 때는 배송을 확인한 근거를 사유로 남깁니다. 버튼이 없으면 결제 · 출고 · 취소 같은 다른 흐름이 상태를 바꿉니다.',
    },
    {
      target: 'order-shipment',
      title: '송장 등록',
      body: '상품 준비중 주문이면 여기서 택배사 · 송장 번호를 넣어 출고합니다. 등록하면 주문이 배송 중으로 바뀌고, 출고된 송장은 바꿀 수 없습니다.',
      side: 'top',
      optional: true,
    },
    {
      target: 'order-histories',
      title: '상태 이력',
      body: '언제 어느 쪽(고객 · 운영자 · 연동 처리 등)이 어떤 상태로 바꿨는지와 적은 사유가 쌓입니다.',
      side: 'top',
    },
  ],
};

/**
 * 클레임 목록(#30) — 고객이 접수한 반품을 찾고 본다. 승인 · 거절 · 환불은 아직 이 화면에 없어 투어도 누르지 않는다.
 * 빈 목록이면 표가 없어 선택 단계다.
 */
const CLAIMS_TOUR: Tour = {
  id: 'claims-v1',
  steps: [
    {
      target: 'claim-filters',
      title: '클레임 찾기',
      body: '유형 · 상태로 거르거나 주문 아이디로 그 주문의 클레임만 봅니다.',
    },
    {
      target: 'claim-table',
      title: '클레임 보기',
      body: '클레임을 누르면 항목과 사유를, 주문을 누르면 주문 상세를 봅니다. 예상 환불액이 미산정이면 아직 금액이 정해지지 않은 것입니다(0원이 아닙니다).',
      side: 'top',
      optional: true,
    },
    {
      target: null,
      title: '처리는 준비 중',
      body: '반품 승인 · 거절 · 수동 환불은 아직 이 화면에서 하지 않습니다. 결제 후 취소의 다시 시도는 클레임 상세에서 합니다.',
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
      summary:
        '처음이라면 시작 체크리스트의 업무부터 해 보세요. 업무는 실제로 저장해야 완료되고, 안내를 본 것만으로는 완료되지 않습니다.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/products$/,
    topic: {
      title: '상품 목록',
      summary:
        '숨긴 상품까지 모든 상품을 찾고 판매 상태 · 재고 반영 판매 가능 여부를 봅니다. "상품 등록" 으로 새 상품을 등록하고, 상품을 눌러 상세에서 기본 정보를 고칩니다.',
      faq: COMMON_FAQ,
      tour: PRODUCTS_TOUR,
    },
  },
  {
    pattern: /^\/products\/new$/,
    topic: {
      title: '상품 등록',
      summary:
        '상품 정보 · 가격 · 옵션을 넣어 상품을 등록합니다. 옵션 값을 넣으면 조합(SKU) 표가 만들어지고, 조합마다 추가금 · 처음 재고를 정합니다.',
      faq: [
        {
          question: '등록 버튼을 눌렀는데 결과를 확인하지 못했다고 나와요.',
          answer:
            '상품이 이미 등록됐을 수 있습니다. 같은 내용으로 다시 등록하면 상품이 두 개 생기니, 상품 목록에서 먼저 확인하세요.',
        },
        {
          question: '이미지를 올리는 중이라며 등록이 안 돼요.',
          answer:
            '이미지가 다 올라갈 때까지 등록을 막아 둡니다. 올리기에 실패하면 칸 아래에 이유가 나오니 파일을 다시 고르세요.',
        },
        ...COMMON_FAQ,
      ],
      tour: PRODUCT_NEW_TOUR,
    },
  },
  {
    pattern: /^\/products\/[^/]+\/edit$/,
    topic: {
      title: '상품 기본 정보 수정',
      summary:
        '상품명 · 요약 · 상세 설명 · 판매 상태 · 노출을 고칩니다. 카테고리 · 가격 · 옵션 · 이미지는 상품 상세의 구역별 수정에서 고칩니다.',
      faq: [
        {
          question: '"그사이 다른 곳에서 이 상품이 바뀌었습니다" 가 떠요.',
          answer:
            '보고 있는 동안 다른 운영자가 상품을 고친 것입니다. "최신 내용 불러오기" 로 바뀐 내용을 확인한 뒤 다시 고치세요.',
        },
        ...COMMON_FAQ,
      ],
      tour: PRODUCT_EDIT_TOUR,
    },
  },
  {
    pattern: /^\/products\/[^/]+\/edit\/[^/]+$/,
    topic: {
      title: '상품 구역 수정',
      summary:
        '가격 · 카테고리 · 이미지 · 옵션 · SKU · 맞춤 제작을 구역마다 따로 저장합니다. 저장하면 상품 버전이 바뀌어, 그사이 다른 곳에서 고쳤으면 덮어쓰지 않고 알려 줍니다.',
      faq: [
        {
          question: '"그사이 다른 곳에서 이 상품이 바뀌었습니다" 가 떠요.',
          answer:
            '보고 있는 동안 상품의 다른 구역이 저장된 것입니다. "최신 내용 불러오기" 로 바뀐 내용을 확인한 뒤 다시 고치세요.',
        },
        {
          question: '옵션 · SKU 화면에서 폼이 잠겨 있어요.',
          answer:
            '다른 폼을 고치는 중이라서입니다. 저장할 때마다 상품이 바뀌어 다른 폼의 입력이 사라지므로, 고치던 폼을 저장하거나 되돌리면 풀립니다.',
        },
        ...COMMON_FAQ,
      ],
      tour: null,
    },
  },
  {
    pattern: /^\/products\/[^/]+$/,
    topic: {
      title: '상품 상세',
      summary:
        '상품 정보 · 옵션 조합(SKU)을 보고, 구역별 수정 링크로 기본 정보 · 가격 · 카테고리 · 이미지 · 옵션 · SKU · 맞춤 제작을 고칩니다. 재고는 재고 화면에서 조정합니다.',
      faq: COMMON_FAQ,
      tour: PRODUCT_DETAIL_TOUR,
    },
  },
  {
    pattern: /^\/payments$/,
    topic: {
      title: '결제 확인',
      summary:
        '저장된 결제 시도와 주문 회신 상태를 봅니다. 결제사에 다시 조회하거나 승인 · 취소하지 않습니다.',
      faq: [
        {
          question: '주문 회신이 "미수신" 이에요.',
          answer:
            '결제 서비스가 주문 쪽 성공 회신을 아직 받지 못했다는 기록입니다. 주문 상세에서 주문 상태를 먼저 확인하세요.',
        },
        ...COMMON_FAQ,
      ],
      tour: PAYMENTS_TOUR,
    },
  },
  {
    pattern: /^\/orders$/,
    topic: {
      title: '주문',
      summary:
        '주문을 상태 · 주문일로 찾고, 여러 주문의 상태를 한 번에 바꿉니다. 받는 분 같은 개인정보는 가려서 보입니다.',
      faq: [
        {
          question: '일괄 변경에서 일부 주문만 실패했어요.',
          answer:
            '결과 표의 이유를 확인하세요. 그사이 고객이 취소했거나 지금 상태에서 바꿀 수 없는 주문입니다. "실패한 주문만 다시 시도" 는 실패한 주문만 다시 보냅니다.',
        },
        {
          question: '송장 등록 · 취소 · 반품 처리는 어디서 하나요?',
          answer:
            '송장은 상품 준비중 주문을 이 화면 아래 송장 일괄 등록이나 주문 상세에서 등록합니다. 고객이 접수한 반품은 클레임 화면에서 보고, 운영자의 취소 · 반품 처리(승인 · 거절 · 환불)는 준비 중입니다.',
        },
        ...COMMON_FAQ,
      ],
      tour: ORDERS_TOUR,
    },
  },
  {
    pattern: /^\/orders\/[^/]+$/,
    topic: {
      title: '주문 상세',
      summary:
        '주문 항목 · 금액 · 배송지 · 상태 이력을 보고, 지금 바꿀 수 있는 상태로 바꿉니다. 버튼이 없으면 결제 · 출고 · 취소 같은 다른 흐름이 상태를 바꿉니다.',
      faq: [
        {
          question: '"그사이 주문 상태가 바뀌었습니다" 가 떠요.',
          answer:
            '보고 있는 동안 고객 취소 · 다른 운영자 처리 등으로 주문이 바뀐 것입니다. 화면이 최신 상태로 바뀌었으니 확인한 뒤 다시 진행하세요.',
        },
        {
          question: '배송 완료로 바꿀 때 사유가 꼭 필요한가요?',
          answer:
            '네. 고객의 반품 · 구매 확정 기간이 배송 완료부터 시작해서, 배송을 확인한 근거를 남겨야 합니다.',
        },
        ...COMMON_FAQ,
      ],
      tour: ORDER_DETAIL_TOUR,
    },
  },
  {
    pattern: /^\/claims$/,
    topic: {
      title: '클레임',
      summary:
        '고객이 접수한 반품과 결제 뒤 취소를 유형 · 상태 · 주문 아이디로 찾아봅니다. 환불 실패 · 재고 복원 실패로 거르면 확인이 필요한 건만 봅니다. 반품 승인 · 거절 · 수동 환불은 아직 이 화면에서 하지 않습니다.',
      faq: [
        {
          question: '예상 환불액이 "미산정" 이에요.',
          answer:
            '아직 환불 금액이 정해지지 않았다는 뜻입니다. 0원이 아니니 고객에게 금액을 안내하기 전에 확정을 기다리세요.',
        },
        ...COMMON_FAQ,
      ],
      tour: CLAIMS_TOUR,
    },
  },
  {
    pattern: /^\/claims\/[^/]+$/,
    topic: {
      title: '클레임 상세',
      summary:
        '클레임 접수 내용과 항목별 수량 · 사유를 봅니다. 결제 후 취소는 처리 상황(다음 자동 확인 · 결제 취소 기한)을 보고, 다시 시도할 수 있을 때만 "다시 시도" 가 보입니다. 주문 링크로 주문 상세와 상태 이력을 함께 확인하세요.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/practice$/,
    topic: {
      title: '샘플 주문 업무 연습',
      summary:
        '샘플 주문으로 주문 확인 → 상품 준비 → 송장 입력, 주문 취소 → 환불 결과를 직접 눌러 봅니다. 실제 주문 · 결제 · 재고 · 배송에 반영되지 않고, 연습을 마쳐도 업무 완료로 세지 않습니다.',
      faq: [
        {
          question: '연습하다 잘못 눌렀어요.',
          answer:
            '위쪽 "처음부터 다시" 를 누르면 샘플 주문이 처음 상태로 돌아갑니다. 화면을 떠나도 처음 상태로 돌아갑니다.',
        },
        ...COMMON_FAQ,
      ],
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
        '템플릿을 고르고 사이즈별 치수를 넣어 저장합니다. 치수 텍스트로 AI 초안을 받아 검토한 뒤 적용할 수도 있습니다. 저장 · 적용하면 스토어 상품 상세에 표로 보입니다.',
      faq: [
        {
          question: 'AI 초안을 요청했는데 아무 변화가 없어요.',
          answer:
            '생성에는 시간이 걸립니다. 끝나면 알림으로 알려 드리고, 화면에 다시 들어오면 진행 중이거나 검토 대기인 초안을 이어서 보여 줍니다. 결과 확인 버튼으로 직접 확인할 수도 있습니다.',
        },
        {
          question: 'AI 초안이 바로 스토어에 보이나요?',
          answer:
            '아니요. 표에 불러와 빈칸을 채우고 경고를 확인한 뒤 초안 적용을 눌러야 반영됩니다. 초안은 7일이 지나면 만료됩니다.',
        },
        {
          question: '이미지로 된 사이즈표도 AI 로 옮길 수 있나요?',
          answer: '아직은 텍스트만 받습니다. 이미지는 표에 직접 입력해 주세요.',
        },
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
    pattern: /^\/shipping-policies\/domestic$/,
    topic: {
      title: '국내 배송비 정책',
      summary: '국내 배송비 기준을 정합니다. 바꾼 기록은 아래 변경 이력에 남습니다.',
      faq: COMMON_FAQ,
      tour: SHIPPING_DOMESTIC_TOUR,
    },
  },
  {
    pattern: /^\/shipping-policies\/international$/,
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
        '오래 걸리는 작업의 진행 상태와 결과를 봅니다. 실행 중인 작업은 위쪽 진행 표시에서도 볼 수 있고, 끝나면 알림 센터로 알려 드립니다.',
      faq: COMMON_FAQ,
      tour: JOBS_TOUR,
    },
  },
  {
    pattern: /^\/admins$/,
    topic: {
      title: '관리자 계정',
      summary:
        '어드민에 로그인할 운영자를 추가하고, 역할(대표 관리자 · 매니저 · 스태프)을 바꾸거나 계정을 끄고 다시 켭니다. 역할을 바꾸거나 끈 계정은 바로 로그아웃됩니다. 꺼진 계정도 목록에 남고 "꺼짐" 으로 거를 수 있습니다. 내 계정의 역할 · 사용 여부는 다른 대표 관리자가 바꾸고, 마지막 대표 관리자는 내리거나 끌 수 없습니다. 비밀번호를 잊은 동료에게는 "재설정 링크" 를 보냅니다. 휴대폰을 잃은 동료는 "OTP 복구" 로 2단계 인증을 새로 바꿔 줄 수 있습니다 — 새 복구 코드는 한 번만 보이니 확인된 별도 경로로 두 개 이상 전해 주세요.',
      faq: COMMON_FAQ,
      tour: null,
    },
  },
  {
    pattern: /^\/account\/password$/,
    topic: {
      title: '내 비밀번호 변경',
      summary: '내 비밀번호를 바꿉니다. 바꾸면 로그아웃되고 새 비밀번호로 다시 로그인합니다.',
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
