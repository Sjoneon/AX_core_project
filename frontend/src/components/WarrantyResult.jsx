import { productName } from '../data/products.js'
import { customerText as text, list, sourceUrl } from '../customerDisplay.js'

const statusLabels = { IN_WARRANTY: '보증기간 안', EXPIRED: '만료', UNKNOWN: '추가 확인 필요' }
const feeLabels = { CONDITIONAL_FREE: '조건 충족 시 무상', PAID: '유상', CONFIRM_REQUIRED: '추가 확인 필요' }
const duration = (months) => months == null ? '기간 확인 필요' : months % 12 === 0 ? `${months / 12}년` : `${months}개월`

function WarrantyRow({ row }) {
  const status = statusLabels[row.periodStatus] ? row.periodStatus : 'UNKNOWN'
  return (
    <article className="warranty-row">
      <div className="warranty-row-heading"><strong>{text(row.name) || '보증 정책'}</strong><span>{duration(row.periodMonths)}</span></div>
      <p className={`warranty-status ${status.toLowerCase()}`}>{statusLabels[status]}</p>
      {row.expiresOn && <p>만료일: {text(row.expiresOn)}</p>}
      {row.applicability === 'NEEDS_CONFIRMATION' && <p>아래 조건이 이 제품에 적용되는지 확인해야 합니다.</p>}
      <dl className="warranty-fees">
        <div><dt>부품비</dt><dd>{feeLabels[row.partFee] || feeLabels.CONFIRM_REQUIRED}</dd></div>
        <div><dt>기술료</dt><dd>{feeLabels[row.laborFee] || feeLabels.CONFIRM_REQUIRED}</dd></div>
        <div><dt>출장비</dt><dd>{feeLabels[row.visitFee] || feeLabels.CONFIRM_REQUIRED}</dd></div>
      </dl>
      {list(row.conditions).length > 0 && <ul>{row.conditions.map((condition) => <li key={condition}>{text(condition)}</li>)}</ul>}
    </article>
  )
}

// 저장된 제품 정보로 조회한 제품 전체·주요 부품 보증 결과
export default function WarrantyResult({ result }) {
  return (
    <section className="warranty-result" aria-label={`${productName(result.category)} 보증 확인 결과`}>
      <p className="warranty-headline">{text(result.headline)}</p>
      <p>기준일 {text(result.asOfDate) || '확인 필요'}{result.basisDate && ` · ${result.basisSource === 'DELIVERY' ? '배송일' : '구매일'} ${text(result.basisDate)}`}</p>
      <h3>제품 전체 보증</h3>
      <WarrantyRow row={result.productWarranty || {}} />
      <h3>주요 부품별 별도 보증</h3>
      <p>다른 부품의 보증기간까지 연장되는 것은 아닙니다.</p>
      {list(result.partWarranties).length > 0 ? result.partWarranties.map((row, index) => <WarrantyRow key={row.policyId || index} row={row} />) : <p>확인된 주요 부품 정책이 없습니다.</p>}
      {result.coverage_periods?.parts_retention?.status === 'UNAVAILABLE' && <p>부품보유기간은 제조일 기준이며 확인된 자료가 없어 제공하지 않습니다.</p>}
      <details><summary>적용 조건과 근거 보기</summary>
        <ul>{list(result.notes).map((note) => <li key={note}>{text(note)}</li>)}</ul>
        {sourceUrl(result.sourceUrl) && <a href={sourceUrl(result.sourceUrl)} target="_blank" rel="noreferrer">LG 공식 보증 정책</a>}
        {result.policyVerifiedOn && <p>정책 확인일 {text(result.policyVerifiedOn)}</p>}
      </details>
    </section>
  )
}
