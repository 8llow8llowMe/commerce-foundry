/**
 * 카카오(다음) 우편번호 서비스 — 국내 배송지의 우편번호 · 도로명 주소를 채운다. 키가 필요 없고, 누를 때만 스크립트를 불러온다.
 * 결과는 화면 입력값일 뿐이다 — 형식 검증은 BE 가 정본이고, 사용자가 고쳐 쓸 수도 있다.
 * https://postcode.map.kakao.com/guide
 */
const SCRIPT_SRC = 'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js';

export type PostcodeResult = { postalCode: string; address: string };

export type PostcodeData = {
  zonecode: string;
  roadAddress: string;
  jibunAddress: string;
  userSelectedType: 'R' | 'J';
  buildingName: string;
  apartment: 'Y' | 'N';
};

type PostcodeConstructor = new (options: { oncomplete: (data: PostcodeData) => void }) => {
  open: () => void;
};

declare global {
  interface Window {
    daum?: { Postcode?: PostcodeConstructor };
  }
}

let loading: Promise<PostcodeConstructor> | null = null;

function loadPostcode(): Promise<PostcodeConstructor> {
  const ready = window.daum?.Postcode;
  if (ready) return Promise.resolve(ready);
  loading ??= new Promise<PostcodeConstructor>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = SCRIPT_SRC;
    script.async = true;
    script.onload = () => {
      const Postcode = window.daum?.Postcode;
      if (Postcode) resolve(Postcode);
      else reject(new Error('postcode unavailable'));
    };
    script.onerror = () => reject(new Error('postcode unavailable'));
    document.head.append(script);
  }).catch((error: unknown) => {
    // 실패하면 다음 클릭에서 다시 불러온다
    loading = null;
    throw error;
  });
  return loading;
}

/**
 * 고른 주소를 우편번호 + 한 줄 주소로. 사용자가 고른 쪽(도로명 · 지번)을 쓰고, 도로명이면 건물명을 괄호로 붙인다(공동주택 · 법정동 관례).
 */
export function postcodeResult(data: PostcodeData): PostcodeResult {
  const road = data.userSelectedType === 'R' || !data.jibunAddress;
  const base = road ? data.roadAddress : data.jibunAddress;
  const building =
    road && data.buildingName && data.apartment === 'Y' ? ` (${data.buildingName})` : '';
  return { postalCode: data.zonecode, address: `${base}${building}` };
}

/**
 * 폼이 뜰 때 스크립트를 미리 불러 둔다. 팝업은 클릭과 같은 흐름에서 열어야 Safari 등이 막지 않는데,
 * 첫 클릭에 스크립트를 기다리면 그 흐름이 끊긴다. 실패는 여기서 삼키고 클릭 때 다시 시도한다.
 */
export function preloadPostcode(): void {
  loadPostcode().catch(() => {});
}

/**
 * 주소 검색 팝업을 연다. 불러 둔 스크립트가 있으면 클릭 흐름 안에서 바로 연다.
 * 스크립트를 불러오지 못하면 reject — 화면은 직접 입력을 안내한다.
 */
export function openPostcode(onSelect: (result: PostcodeResult) => void): Promise<void> {
  const open = (Postcode: PostcodeConstructor) =>
    new Postcode({ oncomplete: (data) => onSelect(postcodeResult(data)) }).open();
  const ready = window.daum?.Postcode;
  if (ready) {
    open(ready);
    return Promise.resolve();
  }
  return loadPostcode().then(open);
}
