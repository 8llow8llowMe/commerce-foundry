import { notFound } from 'next/navigation';

// 어떤 화면에도 맞지 않는 주소를 [locale] 의 not-found 로 보낸다 — 언어별 레이아웃 · 문구 안에서 404 를 그린다
export default function MissingPage(): never {
  notFound();
}
