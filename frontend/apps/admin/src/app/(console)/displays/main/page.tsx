import type { Metadata } from 'next';
import { connection } from 'next/server';
import { DisplayManager } from '@/components/display/DisplayManager';
import { parseOrigin } from '@/lib/display/preview';
import { storefrontOrigin } from '@/lib/env';

export const metadata: Metadata = { title: '메인 전시' };

export default async function MainDisplayPage() {
  // 미리보기 스토어프론트 주소는 실행 환경 값이다 — 빌드 때 굳지 않게 요청 시점에 읽는다
  await connection();
  return <DisplayManager previewOrigin={parseOrigin(storefrontOrigin)} />;
}
