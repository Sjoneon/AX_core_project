import { useState } from 'react'
import { customerText as text, list, sourceUrl } from '../customerDisplay.js'
import { productName } from '../data/products.js'
import Icon from './Icon.jsx'

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

function Ranges({ value, inWarranty }) {
  const parts = list(value.base?.parts).map(text).filter(Boolean)
  return <>
    <p><strong>{range(value.base)}</strong>{parts.length > 0 && ` (${parts.join('·')})`}</p>
    {list(value.separate).map((part) => <p key={part.id}>{text(part.name)} 고장이면 {range(part)}{part.is_core && inWarranty ? ' · 주요 부품 보증이 적용되면 달라질 수 있어요' : ''}</p>)}
  </>
}

// With observation answers that match evidence, their range comes first and the whole range stays visible.
function Estimate({ estimate, observations, codeSeen, inWarranty }) {
  if (estimate?.status !== 'OK') return <p>이 증상은 원인 부품을 특정할 근거가 없어 금액은 점검 후 확인할 수 있어요.</p>
  const narrowed = estimate.narrowed?.status === 'OK' ? estimate.narrowed : null
  return <>
    {narrowed ? <>
      <p className="card-label">답하신 내용 기준</p>
      <Ranges value={narrowed} inWarranty={inWarranty} />
      <p>전체 가능 범위 {range(estimate.overall)}</p>
    </> : <>
      <Ranges value={estimate} inWarranty={inWarranty} />
      {list(estimate.separate).length > 0 && <p>전체 범위 {range(estimate.overall)}</p>}
      {estimate.narrowed?.status === 'NO_MATCH' && <p className="card-note">답하신 내용으로는 부품을 좁히지 못해 전체 범위로 안내해요.</p>}
    </>}
    {observations.length > 0 && <p className="card-note">답하신 내용: {observations.join(' · ')}</p>}
    {codeSeen && <p>표시창의 오류 코드를 채팅에 입력해 주시면 그 코드 기준으로 다시 안내해 드려요.</p>}
    <p className="card-note">부품비·기술료·평일 출장비를 합친 참고 금액이며 견적이 아니에요.{inWarranty && ' 보증이 적용되면 무상일 수 있어요.'}</p>
  </>
}

function Evidence({ candidates }) {
  return list(candidates).map((candidate) => <p key={candidate.id}>{text(candidate.name)}: {range(candidate)}
    {candidate.repair_cases > 0 && ` · 국내 수리 사례 ${candidate.repair_cases}건`}
    {candidate.official_facts > 0 && ` · LG 공식 오류 설명 ${candidate.official_facts}건`}
    {candidate.guides > 0 && ` · 수리업체 가이드 ${candidate.guides}건`}</p>)
}

// Steps and observation questions advance in the browser; only "solved" or the final answers go to the server.
function SelfCheck({ card, version, active, disabled, onSelfCheck }) {
  const [index, setIndex] = useState(0)
  const [asking, setAsking] = useState(null)
  const [answers, setAnswers] = useState({})
  const steps = list(card.steps).map(text).filter(Boolean)
  const questions = list(card.questions).filter((question) => question?.id && list(question.options).length > 0)
  if (steps.length === 0 && questions.length === 0) return null
  if (!active) return <section className="tool-result consultation-card" aria-label="자가점검">
    {steps.length > 0 ? <>
      <h3>직접 확인해 볼 항목</h3>
      <ol className="self-check-steps">{steps.map((step, i) => <li key={i}>{step}</li>)}</ol>
    </> : <>
      <h3>원인을 좁히는 질문</h3>
      <ol className="self-check-steps">{questions.map((question) => <li key={question.id}>{text(question.text)}</li>)}</ol>
    </>}
  </section>
  const finish = (observations) => onSelfCheck('NOT_RESOLVED', steps.length, version,
    steps.length > 0 ? `${steps.length}단계까지 모두 확인했지만 해결되지 않았어요` : '확인 질문에 답했어요', observations)
  const current = steps.length === 0 ? asking ?? 0 : asking
  if (current !== null) {
    const question = questions[current]
    const answer = (value) => {
      const all = { ...answers, [question.id]: value }
      if (current + 1 < questions.length) { setAnswers(all); setAsking(current + 1) } else finish(all)
    }
    return <section className="tool-result consultation-card" aria-label="원인 확인 질문">
      <h3>원인을 좁히는 질문 · {current + 1}/{questions.length}</h3>
      {current === 0 && steps.length > 0 && <p className="card-note">안내한 단계로는 해결되지 않았어요. 수리비 범위를 좁히기 위해 눈으로 보거나 들어서 알 수 있는 것만 여쭤볼게요.</p>}
      <p>{text(question.text)}</p>
      {question.note && <p className="card-note">{text(question.note)}</p>}
      <div className="tool-actions">{list(question.options).map((option) => <button type="button" key={option.value} disabled={disabled} onClick={() => answer(option.value)}>{text(option.label)}</button>)}</div>
    </section>
  }
  const last = index === steps.length - 1
  const solved = () => onSelfCheck('SOLVED', index + 1, version, `${index + 1}단계에서 해결됐어요`)
  const next = () => !last ? setIndex(index + 1) : questions.length > 0 ? setAsking(0) : finish({})
  return <section className="tool-result consultation-card" aria-label="자가점검">
    <h3>{card.source === 'LG_ERROR_GUIDE' ? 'LG 안내대로 확인해 보세요' : '직접 확인해 보세요'} · {index + 1}/{steps.length}단계</h3>
    <ol className="self-check-steps">{steps.slice(0, index + 1).map((step, i) => <li key={i} className={i < index ? 'checked' : undefined}>{step}</li>)}</ol>
    <p>확인한 뒤 해결됐나요?</p>
    <p className="card-note">무슨 뜻인지 모르거나 확인하기 어려운 항목은 ‘아니요’를 눌러 넘어가 주세요.</p>
    <div className="tool-actions">
      <button type="button" disabled={disabled} onClick={solved}>해결됐어요</button>
      <button type="button" disabled={disabled} onClick={next}>{last ? '아니요, 해결되지 않았어요' : '아니요, 다음 단계'}</button>
    </div>
  </section>
}

// Two or more registered products of the category: the customer picks one, and the label matches the saved history bubble.
function ProductChoice({ card, version, active, disabled, onChooseProduct }) {
  const products = list(card.products).filter((product) => Number.isInteger(product?.id))
  if (!active || products.length === 0) return null
  return <section className="tool-result consultation-card" aria-label="제품 선택">
    <h3>상담할 제품을 선택해 주세요</h3>
    <div className="product-choices">{products.map((product) => {
      const category = productName(product.category), name = text(product.name) || category, model = text(product.model)
      return <button type="button" className="product-choice" key={product.id} disabled={disabled}
        onClick={() => onChooseProduct(product.id, version, model ? `${name} · ${model}` : name)}>
        <span className="product-choice-icon"><Icon name={product.category} size={26} /></span>
        <span className="product-choice-text">
          <small>{category}</small>
          <strong>{name}</strong>
          <span>{model ? `모델명 ${model}` : '모델명 미입력'}</span>
        </span>
        <Icon name="chevron" size={18} />
      </button>
    })}</div>
  </section>
}

// One card per reply: the product choice, the self-check steps, or the summary with warranty, reference repair cost and official A/S routes.
export default function ConsultationCard({ card, version, active, disabled, onSelfCheck, onChooseProduct }) {
  const [copyStatus, setCopyStatus] = useState('')
  if (card?.type === 'PRODUCT_CHOICE') return <ProductChoice card={card} version={version} active={active} disabled={disabled} onChooseProduct={onChooseProduct} />
  if (card?.type === 'SELF_CHECK') return <SelfCheck card={card} version={version} active={active} disabled={disabled} onSelfCheck={onSelfCheck} />
  if (card?.type !== 'SUMMARY') return null
  const estimate = card.estimate || {}
  const inWarranty = card.warranty?.status === 'IN_WARRANTY'
  const region = [text(card.region?.sido), text(card.region?.sigungu)].filter(Boolean).join(' ')
  const summary = text(card.handoff_summary)
  const official = card.official && typeof card.official === 'object' ? card.official : null
  async function copy() {
    try { await navigator.clipboard.writeText(summary); setCopyStatus('상담 내용을 복사했어요.') }
    catch { setCopyStatus('복사하지 못했어요. 상담원에게 증상을 직접 말씀해 주세요.') }
  }
  return <section className="tool-result consultation-card" aria-label="점검 안내">
    <h3>점검 안내</h3>
    <dl>
      {official && <div><dt>LG 안내</dt><dd>
        <p>{text(official.shown)}{text(official.meaning) && ` · ${text(official.meaning)}`}</p>
        {text(official.cause) && <p>원인: {text(official.cause)}</p>}
        {list(official.contact).map(text).filter(Boolean).map((line) => <p key={line}>{line}</p>)}
      </dd></div>}
      <div><dt>보증</dt><dd>{text(card.warranty?.text) || '보증기간을 확인하지 못했어요.'}</dd></div>
      <div><dt>예상 수리비</dt><dd><Estimate estimate={estimate} observations={list(card.observations).map(text).filter(Boolean)} codeSeen={card.error_code_seen === true} inWarranty={inWarranty} /></dd></div>
      <div><dt>A/S 연결</dt><dd>
        <p>{region ? `${region} 설치 제품 · ` : ''}출장 수리를 접수하거나 가까운 센터를 찾을 수 있어요.</p>
        {list(card.centers).slice(0, 3).map((center, index) => <p key={center.id || index}>{text(center.name)} · {text(center.address)}</p>)}
        <Routes route={card.route} />
      </dd></div>
    </dl>
    {summary && <div className="tool-actions"><button type="button" onClick={copy}>상담 내용 복사</button><span role="status">{copyStatus}</span></div>}
    {estimate.status === 'OK' && <details><summary>수리비 근거</summary>
      {estimate.narrowed?.status === 'OK' && <><p className="card-label">답하신 내용과 연결된 근거</p><Evidence candidates={estimate.narrowed.candidates} /><p className="card-label">증상 전체 후보</p></>}
      <Evidence candidates={estimate.candidates} />
      <p>금액 기준: 한국 기준 예상 수리비 표 v3(평일 출장비 포함)</p>
    </details>}
  </section>
}
