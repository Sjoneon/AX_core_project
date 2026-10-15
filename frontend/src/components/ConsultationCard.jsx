import { useState } from 'react'
import { customerText as text, list, sourceUrl } from '../customerDisplay.js'

const won = (value) => typeof value === 'number' && Number.isFinite(value) ? `${(Math.round(value / 1000) * 1000).toLocaleString('ko-KR')}원` : null
const range = (value) => value && won(value.min) && won(value.max) ? `약 ${won(value.min)} ~ ${won(value.max)}` : null

function Routes({ route }) {
  const apply = sourceUrl(route?.apply_url), finder = sourceUrl(route?.center_finder_url)
  const phone = typeof route?.phone === 'string' && /^[\d ()+-]{5,25}$/.test(route.phone) ? route.phone : null
  return <div className="tool-route">
    {apply && <a href={apply} target="_blank" rel="noreferrer">출장 수리 접수</a>}
    {finder && <a href={finder} target="_blank" rel="noreferrer">가까운 센터 찾기</a>}
    {phone && <a href={`tel:${phone}`}>상담원 연결 {phone}</a>}
  </div>
}

function Estimate({ estimate, inWarranty }) {
  if (estimate?.status !== 'OK') return <p>이 증상은 원인 부품을 특정할 근거가 없어 금액은 점검 후 확인할 수 있어요.</p>
  const parts = list(estimate.base?.parts).map(text).filter(Boolean)
  return <>
    <p><strong>{range(estimate.base)}</strong>{parts.length > 0 && ` (${parts.join('·')})`}</p>
    {list(estimate.separate).map((part) => <p key={part.id}>{text(part.name)} 고장이면 {range(part)}{part.is_core && inWarranty ? ' · 주요 부품 보증이 적용되면 달라질 수 있어요' : ''}</p>)}
    {list(estimate.separate).length > 0 && <p>전체 범위 {range(estimate.overall)}</p>}
    <p className="card-note">부품비·기술료·평일 출장비를 합친 참고 금액이며 견적이 아니에요.{inWarranty && ' 보증이 적용되면 무상일 수 있어요.'}</p>
  </>
}

// Steps advance in the browser; only "solved" or "not solved after the last step" goes to the server.
function SelfCheck({ card, version, active, disabled, onSelfCheck }) {
  const [index, setIndex] = useState(0)
  const steps = list(card.steps).map(text).filter(Boolean)
  if (steps.length === 0) return null
  if (!active) return <section className="tool-result consultation-card" aria-label="자가점검">
    <h3>직접 확인해 볼 항목</h3>
    <ol className="self-check-steps">{steps.map((step, i) => <li key={i}>{step}</li>)}</ol>
  </section>
  const last = index === steps.length - 1
  const solved = () => onSelfCheck('SOLVED', index + 1, version, `${index + 1}단계에서 해결됐어요`)
  const next = () => last
    ? onSelfCheck('NOT_RESOLVED', steps.length, version, `${steps.length}단계까지 모두 확인했지만 해결되지 않았어요`)
    : setIndex(index + 1)
  return <section className="tool-result consultation-card" aria-label="자가점검">
    <h3>직접 확인해 보세요 · {index + 1}/{steps.length}단계</h3>
    <ol className="self-check-steps">{steps.slice(0, index + 1).map((step, i) => <li key={i} className={i < index ? 'checked' : undefined}>{step}</li>)}</ol>
    <p>확인한 뒤 해결됐나요?</p>
    <div className="tool-actions">
      <button type="button" disabled={disabled} onClick={solved}>해결됐어요</button>
      <button type="button" disabled={disabled} onClick={next}>{last ? '아니요, 해결되지 않았어요' : '아니요, 다음 단계'}</button>
    </div>
  </section>
}

// One card per reply: the self-check steps, or the summary with warranty, reference repair cost and official A/S routes.
export default function ConsultationCard({ card, version, active, disabled, onSelfCheck }) {
  const [copyStatus, setCopyStatus] = useState('')
  if (card?.type === 'SELF_CHECK') return <SelfCheck card={card} version={version} active={active} disabled={disabled} onSelfCheck={onSelfCheck} />
  if (card?.type !== 'SUMMARY') return null
  const estimate = card.estimate || {}
  const inWarranty = card.warranty?.status === 'IN_WARRANTY'
  const region = [text(card.region?.sido), text(card.region?.sigungu)].filter(Boolean).join(' ')
  const summary = text(card.handoff_summary)
  async function copy() {
    try { await navigator.clipboard.writeText(summary); setCopyStatus('상담 내용을 복사했어요.') }
    catch { setCopyStatus('복사하지 못했어요. 상담원에게 증상을 직접 말씀해 주세요.') }
  }
  return <section className="tool-result consultation-card" aria-label="점검 안내">
    <h3>점검 안내</h3>
    <dl>
      <div><dt>보증</dt><dd>{text(card.warranty?.text) || '보증기간을 확인하지 못했어요.'}</dd></div>
      <div><dt>예상 수리비</dt><dd><Estimate estimate={estimate} inWarranty={inWarranty} /></dd></div>
      <div><dt>A/S 연결</dt><dd>
        <p>{region ? `${region} 설치 제품 · ` : ''}출장 수리를 접수하거나 가까운 센터를 찾을 수 있어요.</p>
        {list(card.centers).slice(0, 3).map((center, index) => <p key={center.id || index}>{text(center.name)} · {text(center.address)}</p>)}
        <Routes route={card.route} />
      </dd></div>
    </dl>
    {summary && <div className="tool-actions"><button type="button" onClick={copy}>상담 내용 복사</button><span role="status">{copyStatus}</span></div>}
    {estimate.status === 'OK' && <details><summary>수리비 근거</summary>
      {list(estimate.candidates).map((candidate) => <p key={candidate.id}>{text(candidate.name)}: {range(candidate)}
        {candidate.repair_cases > 0 && ` · 국내 수리 사례 ${candidate.repair_cases}건`}
        {candidate.official_facts > 0 && ` · LG 공식 오류 설명 ${candidate.official_facts}건`}
        {candidate.guides > 0 && ` · 수리업체 가이드 ${candidate.guides}건`}</p>)}
      <p>금액 기준: 한국 기준 예상 수리비 표 v3(평일 출장비 포함)</p>
    </details>}
  </section>
}
