'use client';

import { createContext, useContext, type ReactNode } from 'react';

// 레이아웃 밖(단독 테스트 렌더)에서는 로그아웃 상태다
const SessionContext = createContext(false);

/**
 * 클라이언트 컴포넌트가 로그인 여부(인증 쿠키 존재, `hasSession`)를 알게 한다. [locale] 레이아웃이 감싼다.
 * 장바구니처럼 회원 · 비회원 경로가 갈리는 곳에서 쓴다. 쿠키가 남아 있어도 세션이 끝났을 수 있어
 * API 가 401 이면 비회원 경로로 내려가고 화면을 다시 그린다(router.refresh).
 */
export function SessionProvider({
  signedIn,
  children,
}: {
  signedIn: boolean;
  children: ReactNode;
}) {
  return <SessionContext value={signedIn}>{children}</SessionContext>;
}

export function useSignedIn(): boolean {
  return useContext(SessionContext);
}
