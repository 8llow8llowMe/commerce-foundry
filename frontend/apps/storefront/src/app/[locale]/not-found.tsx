import { Link } from '@/components/i18n/Link';
import { getI18n } from '@/lib/i18n/server';

// 메뉴에 링크만 있고 아직 만들지 않은 화면도 여기로 온다. 404 상태는 그대로 두고 문구만 부드럽게 한다.
export default async function NotFound() {
  const { dict } = await getI18n();
  return (
    <main className="mx-auto flex max-w-xl flex-col items-center gap-4 px-4 py-24 text-center">
      <p className="font-display text-lg tracking-[0.2em] text-muted-fg uppercase">Coming soon</p>
      <h1 className="font-serif text-xl">{dict.notFound.title}</h1>
      <p className="text-sm text-muted-fg">{dict.notFound.body}</p>
      <Link href="/" className="mt-4 text-sm underline underline-offset-4 hover:text-primary">
        {dict.notFound.home}
      </Link>
    </main>
  );
}
