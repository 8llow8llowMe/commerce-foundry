import Link from 'next/link';

/** 없거나 삭제된 상품 · 형식이 틀린 주소(#418). 주소의 아이디는 다시 보이지 않는다(#305) */
export default function ProductNotFound() {
  return (
    <div role="status" className="flex max-w-xl flex-col gap-3 rounded-md bg-muted px-5 py-6">
      <p className="font-medium">삭제됐거나 없는 상품입니다.</p>
      <p className="text-sm text-muted-fg">
        주소가 맞는지 확인하거나 상품 목록에서 다시 찾아 주세요.
      </p>
      <Link href="/products" className="text-sm text-fg underline underline-offset-4">
        상품 목록으로 돌아가기
      </Link>
    </div>
  );
}
