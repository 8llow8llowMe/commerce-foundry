import Link from 'next/link';

// 메뉴에 링크만 있고 아직 만들지 않은 화면도 여기로 온다. 404 상태는 그대로 두고 문구만 부드럽게 한다.
export default function NotFound() {
  return (
    <main className="mx-auto flex max-w-xl flex-col items-center gap-4 px-4 py-24 text-center">
      <p className="text-xs tracking-[0.2em] text-muted-fg uppercase">Coming soon</p>
      <h1 className="text-xl font-medium">준비 중이거나 없는 페이지입니다.</h1>
      <p className="text-sm text-muted-fg">주소를 확인하시거나 메인에서 다시 찾아 주세요.</p>
      <Link href="/" className="mt-4 text-sm underline underline-offset-4 hover:text-primary">
        메인으로
      </Link>
    </main>
  );
}
