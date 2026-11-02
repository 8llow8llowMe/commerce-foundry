import type { Metadata } from 'next';
import { PracticeScreen } from '@/components/practice/PracticeScreen';

export const metadata: Metadata = { title: '업무 연습' };

/** 샘플 주문 업무 연습(#365). 도움말(?)에서 들어온다 — 메뉴에는 두지 않는다. 실서비스 API 를 부르지 않는다 */
export default function PracticePage() {
  return <PracticeScreen />;
}
