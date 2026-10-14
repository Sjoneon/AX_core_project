import { productName } from '../data/products.js'

const statusLabels = { IN_WARRANTY: '보증기간 안', EXPIRED: '만료', UNKNOWN: '추가 확인 필요' }
const feeLabels = { CONDITIONAL_FREE: '조건 충족 시 무상', PAID: '유상', CONFIRM_REQUIRED: '추가 확인 필요' }
const duration = (months) => months == null ? '기간 확인 필요' : months % 12 === 0 ? `${months / 12}년` : `${months}개월`

function WarrantyRow({ row }) {
  return (
    <article className="warranty-row">
      <div className="warranty-row-heading"><strong>{row.name}</strong><span>{duration(row.periodMonths)}</span></div>
      <p className={`warranty-status ${row.periodStatus.toLowerCase()}`}>{statusLabels[row.periodStatus]}</p>
      {row.expiresOn && <p>만료일: {row.expiresOn}</p>}
      {row.applicability === 'NEEDS_CONFIRMATION' && <p>아래 조건이 이 제품에 적용되는지 확인해야 합니다.</p>}
      <dl className="warranty-fees">
        <div><dt>부품비</dt><dd>{feeLabels[row.partFee]}</dd></div>
        <div><dt>기술료</dt><dd>{feeLabels[row.laborFee]}</dd></div>
        <div><dt>출장비</dt><dd>{feeLabels[row.visitFee]}</dd></div>
      </dl>
      {row.conditions.length > 0 && <ul>{row.conditions.map((condition) => <li key={condition}>{condition}</li>)}</ul>}
    </article>
  )
}

// 저장된 제품 정보로 조회한 제품 전체·주요 부품 보증 결과
export default function WarrantyResult({ result }) {
  return (
    <section className="warranty-result" aria-label={`${productName(result.category)} 보증 확인 결과`}>
      <p className="warranty-headline">{result.headline}</p>
      <p>기준일 {result.asOfDate}{result.basisDate && ` · ${result.basisSource === 'DELIVERY' ? '배송일' : '구매일'} ${result.basisDate}`}</p>
      <h3>제품 전체 보증</h3>
      <WarrantyRow row={result.productWarranty} />
      <h3>주요 부품별 별도 보증</h3>
      <p>다른 부품의 보증기간까지 연장되는 것은 아닙니다.</p>
      {result.partWarranties.length > 0 ? result.partWarranties.map((row) => <WarrantyRow key={row.policyId} row={row} />) : <p>확인된 주요 부품 정책이 없습니다.</p>}
      <details><summary>적용 조건과 근거 보기</summary>
        <ul>{result.notes.map((note) => <li key={note}>{note}</li>)}</ul>
        <a href={result.sourceUrl} target="_blank" rel="noreferrer">LG 공식 보증 정책</a>
        <p>정책 확인일 {result.policyVerifiedOn}</p>
      </details>
    </section>
  )
}
