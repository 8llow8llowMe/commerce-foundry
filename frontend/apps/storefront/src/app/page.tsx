import { checkGateway } from '@cf/api-client/server';
import { brand } from '@cf/brand';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

// 임시 화면 — #24 에서 메인 화면으로 바꾼다.
export default async function HomePage() {
  const gateway = await checkGateway(gatewayUrl);

  return (
    <main className="mx-auto flex min-h-dvh max-w-xl flex-col justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">{brand.name}</h1>
      <p className="text-muted-fg">스토어프론트 초기 구성</p>
      <p>
        게이트웨이:{' '}
        <span className={gateway === 'UP' ? 'font-medium text-primary' : 'font-medium text-danger'}>
          {gateway}
        </span>
      </p>
    </main>
  );
}
