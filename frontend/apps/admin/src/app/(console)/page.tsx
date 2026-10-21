import { checkGateway } from '@cf/api-client/server';
import { brand } from '@cf/brand';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

// 임시 홈 — #29 에서 대시보드로 바꾼다.
export default async function AdminHomePage() {
  const gateway = await checkGateway(gatewayUrl);

  return (
    <div className="flex max-w-xl flex-col gap-3">
      <h1 className="text-xl font-semibold">{brand.name} 어드민</h1>
      <p className="text-sm text-muted-fg">왼쪽 메뉴에서 관리할 화면을 고르세요.</p>
      <p className="text-sm">
        게이트웨이:{' '}
        <span className={gateway === 'UP' ? 'font-medium text-primary' : 'font-medium text-danger'}>
          {gateway}
        </span>
      </p>
    </div>
  );
}
