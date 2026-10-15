import { useState } from 'react'
import { customerText as text, list, sourceUrl } from '../customerDisplay.js'

const labels = { OK: '조회 완료', NEEDS_INPUT: '입력 필요', PARTIAL: '일부 확인', NO_DATA: '자료 없음', NOT_VERIFIED: '검토된 절차 없음', BLOCKED_BY_SAFETY: '안전 확인 우선', ERROR: '조회 실패' }
const names = { get_diagnostic_plan: '자가검진', get_repair_candidates: '원인 후보 확인', estimate_repair_cost: '예상 수리비', find_service_centers: 'A/S 연결', prepare_service_handoff: '상담원 전달 요약', followup: '후속 조회 안내' }
// A center directory is not integrated: the card is an official connection, not a completed lookup.
function statusLabel(name, result) {
  if (name === 'find_service_centers' && result.data?.directory_status === 'NOT_INTEGRATED') return result.status === 'OK' ? '공식 연결' : '지역 확인 필요'
  return labels[result.status] || '확인 필요'
}
const money = (value) => typeof value !== 'number' || !Number.isFinite(value) ? '추가 확인 필요' : `${value.toLocaleString('ko-KR')}원`
function Amount({ value }) {
  if (!value || value.status === 'UNKNOWN') return <>추가 확인 필요</>
  const middle = value.median == null ? '' : ` (가운데 값 ${money(value.median)})`
  if (value.min != null && value.max != null) return <>{value.min === value.max ? money(value.min) : `${money(value.min)} ~ ${money(value.max)}${middle}`}</>
  return <>{value.median == null ? '추가 확인 필요' : `${money(value.median)} (중앙 참고값)`}</>
}
const slots = { WEEKDAY: '평일', NIGHT_HOLIDAY: '야간·휴일' }
function Route({ route }) {
  if (!route) return null
  const apply = sourceUrl(route.apply_url), centers = sourceUrl(route.center_finder_url)
  const phone = typeof route.phone === 'string' && /^[\d ()+-]{5,25}$/.test(route.phone) ? route.phone : null
  return <div className="tool-route">{apply && <a href={apply} target="_blank" rel="noreferrer">공식 출장 접수</a>}{centers && <a href={centers} target="_blank" rel="noreferrer">전국 센터 찾기</a>}{phone && <a href={`tel:${phone}`}>상담 {phone}</a>}</div>
}
export default function ToolResults({ tools, active, onAnswerSelfCare }) {
  const [copyStatus, setCopyStatus] = useState('')
  const [stepError, setStepError] = useState('')
  const [busy, setBusy] = useState(false)
  async function answerSelfCare(kind, payload, label) {
    setBusy(true); setStepError('')
    try { await onAnswerSelfCare(kind, payload, label) }
    catch (error) { setStepError(error.message) }
    finally { setBusy(false) }
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); setCopyStatus('요약을 복사했습니다.') }
    catch { setCopyStatus('아래 요약을 선택해 복사해 주세요.') }
  }
  return Object.entries(tools || {}).filter(([name]) => names[name]).map(([name, original]) => {
    const result = original || {}
    const data = result.data || {}
    const evidence = [...new Map(list(result.evidence).map((source) => [sourceUrl(source.source_ref), source])).entries()].filter(([url]) => url)
    const hasKnowledge = list(data.references).length || list(data.safety_notices).length
    const summary = text(data.summary_text)
    const blocked = result.status === 'BLOCKED_BY_SAFETY' || ['SUSPECT', 'CONFIRMED'].includes(tools?.safety?.data?.risk_status)
    return <section className="tool-result" key={name} aria-label={names[name]}>
      <h3>{names[name]} <small>{statusLabel(name, result)}</small></h3>
      {name === 'get_diagnostic_plan' && <>
        {data.skip_reason === 'NOT_VERIFIED' && <p>이 제품·증상에는 검토를 마친 단계별 자가점검 절차가 아직 없어요. 제조사 근거는 설명용이며 실행 절차가 아닙니다.</p>}
        {data.skip_reason === 'RESOLVED' && <p>해결되었습니다. 새 증상은 새 상담으로 시작해 주세요.</p>}
        {blocked && <p>안전 확인이 우선입니다. 자가조치를 진행하지 말고 위험 안내를 확인해 주세요.</p>}
        {data.question && !blocked && <div><p>{text(data.question.text)}</p>
          {sourceUrl(data.question.source) && <a href={sourceUrl(data.question.source)} target="_blank" rel="noreferrer">조건 확인에 필요한 원문·그림 보기</a>}
          {active && <div className="tool-actions">{[['YES', '예'], ['NO', '아니요'], ['UNSURE', '모르겠어요']].map(([answer, label]) => <button type="button" key={answer} disabled={busy} onClick={() => answerSelfCare('precondition', { question_id: data.question.id, plan_scope: data.plan_scope, answer, context_version: result.meta?.context_version }, `${text(data.question.text)}\n${label}`)}>{label}</button>)}</div>}
        </div>}
        {data.skip_reason === 'ALL_DONE' && <p>검토된 단계를 모두 마쳤지만 해결되지 않았습니다.</p>}
        {data.skip_reason === 'NO_SELF_CARE' && <p>검토 결과 이 조건에 제공할 자가조치가 없습니다.</p>}
        {data.skip_reason === 'PRECONDITION_NOT_MET' && <p>선행 조건이 충족되지 않아 조치를 중지했습니다.</p>}
        {data.skip_reason === 'PRECONDITION_UNCONFIRMED' && <p>선행 조건을 확인하지 못해 조치를 중지했습니다.</p>}
        {data.followup && <p>{text(data.followup)}</p>}
        {(data.followup || data.skip_reason === 'NOT_VERIFIED') && <Route route={data.route} />}
        {!blocked && result.status === 'OK' && list(data.steps).map((step) => <div key={step.step_code}><p>{text(step.text || step.instruction)}</p>{step.caution && <p>{text(step.caution)}</p>}
          {active && <div className="tool-actions">해결됐나요? {[['YES', '예'], ['NO', '아니요 · 다음 안내']].map(([answer, label]) => <button type="button" key={answer} disabled={busy} onClick={() => answerSelfCare('step', { step_code: step.step_code, answer, context_version: result.meta?.context_version }, `${text(step.text || step.instruction)} — 해결됐나요?\n${answer === 'YES' ? '예' : '아니요'}`)}>{label}</button>)}</div>}
        </div>)}
        {stepError && <p role="alert">{text(stepError)}</p>}
      </>}
      {name === 'estimate_repair_cost' && <>
        {list(data.alternatives).length ? data.alternatives.map((alternative, index) => <article className="cost-alternative" key={alternative.candidate_id || index}>
          <h4>{text(alternative.part_name) || '수리 후기 참고 범위'} · {alternative.cost_source === 'USER_PART_REQUEST' ? '문의한 부품 그룹' : '원인 후보'}</h4>
          {alternative.candidate_basis && <p>{text(alternative.candidate_basis)}</p>}
          <p>참고용 예상 범위(견적 아님): <Amount value={alternative.expected_range || alternative.reference_total_v3} /></p>
          {alternative.surcharge?.amount > 0 && <p>{slots[alternative.surcharge.time_slot]}{alternative.surcharge.month ? ` ${alternative.surcharge.month}월` : ''} 방문 기준 출장비 가산 {money(alternative.surcharge.amount)} 포함</p>}
          {alternative.confidence_grade && <p>신뢰도 {text(alternative.confidence_grade)}{alternative.calibration_cases != null && ` · 국내 같은 부품 사례 ${alternative.calibration_cases}건`}</p>}
          {alternative.warranty_note && <p>{text(alternative.warranty_note)}</p>}
        </article>) : <p>신뢰할 수 있는 비용 자료가 부족합니다. 공식 점검 후 확인해 주세요.</p>}
        {list(data.alternatives).length > 1 && <p>후보별 대안이며 두 후보를 합산하지 않습니다.</p>}
        {data.basis?.description && <p>기준: {text(data.basis.description)}</p>}
        <p>{text(data.notice)}</p>
        {list(data.missing_information).map((reason) => <p key={reason}>{text(reason)}</p>)}
      </>}
      {name === 'get_repair_candidates' && <>
        <p>현재 제품의 실제 원인은 아직 확인되지 않았습니다.</p>
        {list(data.cause_candidates).map((cause, index) => <article key={`${cause.evidence_id}-${index}`}><p>{text(cause.description)}</p>{list(cause.source_urls).map(sourceUrl).filter(Boolean).slice(0, 1).map((url) => <a key={url} href={url} target="_blank" rel="noreferrer">제조사 근거 확인</a>)}</article>)}
        {list(data.supported_candidates).filter((candidate) => candidate.basis === 'USER_PART_REQUEST').map((candidate) => <p key={candidate.id}>{text(candidate.name)} 비용 문의 · 실제 고장 확인 결과는 아닙니다.</p>)}
        {list(data.missing_information).map((reason) => <p key={reason}>{text(reason)}</p>)}
      </>}
      {name === 'followup' && <><p>일부 후속 조회를 마치지 못했습니다. 공식 상담 경로를 이용하거나 다음 대화에서 다시 문의해 주세요.</p><Route route={data.route} /></>}
      {name === 'find_service_centers' && (data.directory_status === 'NOT_INTEGRATED' ? <>
        {data.region_status === 'KNOWN'
          ? <p>{`${text(data.base_region?.sido)} ${text(data.base_region?.sigungu)}`.trim()} 설치 제품 · 점검은 LG 공식 출장 접수로 신청할 수 있어요. 센터 방문을 원하면 공식 센터 찾기에서 접수 가능한 센터를 확인해 주세요.</p>
          : <p>설치 지역이 저장되어 있지 않아요. 마이페이지에서 주소 검색으로 설치 주소를 다시 저장해 주세요. 지금도 공식 출장 접수나 고객센터로 문의할 수 있어요.</p>}
        <p>아직 접수·예약하지 않았습니다.</p>
        <Route route={data.route} />
      </> : <>
        <p>저장 당시의 센터 안내입니다. 현재 운영·접수 가능 여부는 공식 페이지에서 확인해 주세요.</p>
        {data.base_region && <p>{text(data.base_region.sido)} {text(data.base_region.sigungu)} 기준 · 거리순 아님</p>}
        {list(data.centers).map((center) => <article key={center.id}><strong>{text(center.name)}</strong><p>{text(center.address)}</p><p>{text(center.hours)}</p>{sourceUrl(center.detail_url) && <a href={sourceUrl(center.detail_url)} target="_blank" rel="noreferrer">센터 운영·접수 정보 확인</a>}</article>)}
        <Route route={data.route} />
      </>)}
      {name === 'prepare_service_handoff' && <>
        {summary ? <><p className="source-content">{summary}</p><p>전달할 요약이 준비됐습니다. 실제 접수는 공식 채널에서 진행해 주세요.</p>
          <button type="button" onClick={() => copy(summary)}>요약 복사</button><span role="status">{copyStatus}</span></> : <p>전달할 요약을 준비하지 못했습니다. 공식 상담 경로에서 제품과 증상을 알려 주세요.</p>}<Route route={data.route} />
      </>}
      {list(result.warnings).map((warning) => text(warning) && <p key={warning}>{text(warning)}</p>)}
      {list(result.caveats).length > 0 && <details><summary>안내 기준</summary>{result.caveats.map((caveat) => <p key={caveat}>{text(caveat)}</p>)}</details>}
      {evidence.length > 0 && !hasKnowledge && <details><summary>근거 보기</summary>{evidence.map(([url, source]) => <p key={url}><a href={url} target="_blank" rel="noreferrer">{({ OFFICIAL_MANUAL: '공식 설명서', OFFICIAL_POLICY: '공식 정책', OFFICIAL_SERVICE: '공식 서비스 안내', REPAIR_CASE: '수리 사례 출처' })[source.source_type] || '자료 출처'}</a>{text(source.base_date) && ` · 기준일 ${text(source.base_date)}`}</p>)}</details>}
    </section>
  })
}
