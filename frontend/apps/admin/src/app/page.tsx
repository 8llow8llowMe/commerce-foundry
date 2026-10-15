import { checkGateway } from '@cf/api-client/server';
import { brand } from '@cf/brand';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

// 임시 화면 — #29 에서 어드민 로그인·대시보드로 바꾼다.
export default async function AdminHomePage() {
  const gateway = await checkGateway(gatewayUrl);

  return (
    <main className="mx-auto flex min-h-dvh max-w-xl flex-col justify-center gap-4 px-4">
      <h1 className="text-2xl font-semibold">{brand.name} 어드민</h1>
      <p className="text-muted-fg">어드민 콘솔 초기 구성</p>
      <p>
        게이트웨이:{' '}
        <span className={gateway === 'UP' ? 'font-medium text-primary' : 'font-medium text-danger'}>
          {gateway}
        </span>
      </p>
    </main>
  );
}
