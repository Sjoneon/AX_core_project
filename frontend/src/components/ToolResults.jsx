import { useState } from 'react'

const labels = { OK: '조회 완료', NEEDS_INPUT: '입력 필요', PARTIAL: '조건별 참고값', NO_DATA: '자료 없음', NOT_VERIFIED: '실행 승인 미확인', BLOCKED_BY_SAFETY: '안전 확인 우선' }
const names = { get_diagnostic_plan: '자가검진', estimate_repair_cost: '예상 수리비', find_service_centers: 'A/S 센터', prepare_service_handoff: '상담원 전달 요약' }
const money = (value) => value == null ? '추가 확인 필요' : `${Number(value).toLocaleString('ko-KR')}원`
function Amount({ value }) {
  if (!value || value.status === 'UNKNOWN') return <>추가 확인 필요</>
  const middle = value.median == null ? '' : ` (가운데 값 ${money(value.median)})`
  if (value.min != null && value.max != null) return <>{value.min === value.max ? money(value.min) : `${money(value.min)} ~ ${money(value.max)}${middle}`}</>
  return <>{value.median == null ? '추가 확인 필요' : `${money(value.median)} (중앙 참고값)`}</>
}
const slots = { WEEKDAY: '평일', NIGHT_HOLIDAY: '야간·휴일' }
function Route({ route }) {
  if (!route) return null
  return <div className="tool-route"><a href={route.apply_url} target="_blank" rel="noreferrer">공식 출장 접수</a> · <a href={route.center_finder_url} target="_blank" rel="noreferrer">전국 센터 찾기</a> · <a href={`tel:${route.phone}`}>상담 {route.phone}</a></div>
}
export default function ToolResults({ tools, conversationId, request, active, onStateChanged }) {
  const [copyStatus, setCopyStatus] = useState('')
  const [planOverride, setPlanOverride] = useState(null)
  const [stepError, setStepError] = useState('')
  const [busy, setBusy] = useState(false)
  async function answerStep(code, answer, version) {
    setBusy(true); setStepError('')
    try { setPlanOverride(await request(`/api/conversations/${conversationId}/step-answer`, { method: 'POST', body: JSON.stringify({ step_code: code, answer, context_version: version }) })); onStateChanged?.() }
    catch (error) { setStepError(error.message) }
    finally { setBusy(false) }
  }
  async function copy(text) {
    try { await navigator.clipboard.writeText(text); setCopyStatus('요약을 복사했습니다.') }
    catch { setCopyStatus('아래 요약을 선택해 복사해 주세요.') }
  }
  return Object.entries(tools || {}).filter(([name]) => names[name]).map(([name, original]) => {
    const result = name === 'get_diagnostic_plan' && planOverride ? planOverride : original
    const data = result.data || {}
    return <section className="tool-result" key={name} aria-label={names[name]}>
      <h3>{names[name]} <small>{labels[result.status] || result.status}</small></h3>
      {name === 'get_diagnostic_plan' && <>
        {data.safety_notices?.map((notice) => <details key={notice.document_id} open><summary>안전 주의 · {notice.title}</summary><p className="source-content">{notice.content}</p></details>)}
        {data.skip_reason === 'RESOLVED' && <p>해결되었습니다. 새 증상은 새 상담으로 시작해 주세요.</p>}
        {data.steps?.map((step) => <div key={step.step_code}><p>{step.text || step.instruction}</p>{step.caution && <p>{step.caution}</p>}
          {active && <div className="tool-actions">해결됐나요? <button disabled={busy} onClick={() => answerStep(step.step_code, 'YES', result.meta.context_version)}>예</button><button disabled={busy} onClick={() => answerStep(step.step_code, 'NO', result.meta.context_version)}>아니요 · 다음 단계</button></div>}
        </div>)}
        {data.references?.map((ref) => <details key={ref.document_id}><summary>{ref.title} · 실행 승인 미확인</summary><p className="source-content">{ref.content}</p></details>)}
        {stepError && <p role="alert">{stepError}</p>}
      </>}
      {name === 'estimate_repair_cost' && <>
        {data.alternatives?.length ? data.alternatives.map((alternative, index) => <article className="cost-alternative" key={alternative.candidate_id || index}>
          <h4>{alternative.part_name || '수리 후기 참고 범위'} · 원인 후보</h4>
          <p>참고용 예상 범위(견적 아님): <Amount value={alternative.expected_range || alternative.reference_total_v3} /></p>
          {alternative.surcharge?.amount > 0 && <p>{slots[alternative.surcharge.time_slot]}{alternative.surcharge.month ? ` ${alternative.surcharge.month}월` : ''} 방문 기준 출장비 가산 {money(alternative.surcharge.amount)} 포함</p>}
          {alternative.confidence_grade && <p>신뢰도 {alternative.confidence_grade}{alternative.calibration_cases != null && ` · 국내 같은 부품 사례 ${alternative.calibration_cases}건`}</p>}
          {alternative.warranty_note && <p>{alternative.warranty_note}</p>}
          <p>후보별 대안이며 두 후보를 합산하지 않습니다.</p>
        </article>) : <p>신뢰할 수 있는 비용 자료가 부족합니다. 공식 점검 후 확인해 주세요.</p>}
        {data.basis?.description && <p>기준: {data.basis.description}</p>}
        <p>{data.notice}</p>
      </>}
      {name === 'find_service_centers' && <>
        {data.base_region && <p>{data.base_region.sido} {data.base_region.sigungu} 기준 · 거리순 아님</p>}
        {data.centers?.map((center) => <article key={center.id}><strong>{center.name}</strong><p>{center.address}</p><p>{center.hours}</p><a href={center.detail_url} target="_blank" rel="noreferrer">센터 운영·접수 정보 확인</a></article>)}
        {data.centers?.length === 0 && <p>등록된 지역 센터 자료가 없습니다. 전국 센터 찾기를 이용해 주세요.</p>}
        <Route route={data.route} />
      </>}
      {name === 'prepare_service_handoff' && <>
        <p className="source-content">{data.summary_text}</p><p>전달할 요약이 준비됐습니다. 실제 접수는 공식 채널에서 진행해 주세요.</p>
        <button type="button" onClick={() => copy(data.summary_text)}>요약 복사</button><span role="status">{copyStatus}</span><Route route={data.route} />
      </>}
      {result.warnings?.map((warning) => <p key={warning}>{warning}</p>)}
      {result.caveats?.length > 0 && <details><summary>안내 기준</summary>{result.caveats.map((caveat) => <p key={caveat}>{caveat}</p>)}</details>}
      {result.evidence?.length > 0 && <details><summary>근거 보기</summary>{result.evidence.map((source) => <p key={source.evidence_id}>{source.source_ref?.startsWith('https://') ? <a href={source.source_ref} target="_blank" rel="noreferrer">{source.source_type}</a> : source.source_ref} · {source.base_date}</p>)}</details>}
    </section>
  })
}
