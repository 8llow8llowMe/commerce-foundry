/**
 * 화면 폭을 가득 채우는 브랜드 이름(#59 푸터). 글자 수가 브랜드마다 달라 SVG 로 그린다 —
 * 글자 크기는 폭에 넉넉히 들어가게 잡고, 남는 폭은 textLength 로 자간만 벌려 양 끝을 맞춘다(글자 모양은 늘리지 않는다).
 * 색은 currentColor — 푸터 바탕이 화보면 Porcelain, 아니면 본문색.
 */
const WIDTH = 1000;
/** 대문자 평균 글자 폭(em). 넓은 글자(M · W)가 많아도 자간이 음수가 되지 않게 넉넉히 잡는다 */
const ADVANCE_EM = 0.78;
/** 대문자 높이(em) — 디스플레이 세리프 기준. viewBox 를 대문자 높이에 맞춰 위아래 빈 줄을 없앤다 */
const CAP_HEIGHT_EM = 0.64;

export function Wordmark({ text }: { text: string }) {
  const label = text.toUpperCase();
  const fontSize = WIDTH / (Math.max(label.length, 1) * ADVANCE_EM);
  const height = Math.round(fontSize * CAP_HEIGHT_EM);

  return (
    <svg
      viewBox={`0 0 ${WIDTH} ${height}`}
      className="block h-auto w-full overflow-visible"
      aria-hidden="true"
      focusable="false"
    >
      <text
        x="0"
        y={height}
        textLength={WIDTH}
        lengthAdjust="spacing"
        fontSize={fontSize}
        fill="currentColor"
        className="font-display"
      >
        {label}
      </text>
    </svg>
  );
}
