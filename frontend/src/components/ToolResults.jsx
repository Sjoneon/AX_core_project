import { useState } from 'react'

const labels = { OK: '조회 완료', NEEDS_INPUT: '입력 필요', PARTIAL: '조건별 참고값', NO_DATA: '자료 없음', NOT_VERIFIED: '실행 승인 미확인', BLOCKED_BY_SAFETY: '안전 확인 우선' }
const names = { get_diagnostic_plan: '자가검진', get_repair_candidates: '원인 후보 확인', estimate_repair_cost: '예상 수리비', find_service_centers: 'A/S 센터', prepare_service_handoff: '상담원 전달 요약', followup: '후속 조회 안내' }
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
    const result = original
    const data = result.data || {}
    return <section className="tool-result" key={name} aria-label={names[name]}>
      <h3>{names[name]} <small>{labels[result.status] || result.status}</small></h3>
      {name === 'get_diagnostic_plan' && <>
        {data.safety_notices?.map((notice) => <details key={notice.document_id} open><summary>안전 주의 · {notice.title}</summary><p className="source-content">{notice.content}</p></details>)}
        {data.skip_reason === 'RESOLVED' && <p>해결되었습니다. 새 증상은 새 상담으로 시작해 주세요.</p>}
        {data.question && <div><p>{data.question.text}</p>
          {data.question.source && <a href={data.question.source} target="_blank" rel="noreferrer">조건 확인에 필요한 원문·그림 보기</a>}
          {active && <div className="tool-actions">{[['YES', '예'], ['NO', '아니요'], ['UNSURE', '모르겠어요']].map(([answer, label]) => <button key={answer} disabled={busy} onClick={() => answerSelfCare('precondition', { question_id: data.question.id, plan_scope: data.plan_scope, answer, context_version: result.meta.context_version }, `${data.question.text}\n${label}`)}>{label}</button>)}</div>}
        </div>}
        {data.skip_reason === 'ALL_DONE' && <p>검토된 단계를 모두 마쳤지만 해결되지 않았습니다.</p>}
        {data.skip_reason === 'NO_SELF_CARE' && <p>검토 결과 이 조건에 제공할 자가조치가 없습니다.</p>}
        {data.skip_reason === 'PRECONDITION_NOT_MET' && <p>선행 조건이 충족되지 않아 조치를 중지했습니다.</p>}
        {data.skip_reason === 'PRECONDITION_UNCONFIRMED' && <p>선행 조건을 확인하지 못해 조치를 중지했습니다.</p>}
        {data.followup && <p>{data.followup}</p>}
        {(data.followup || data.skip_reason === 'NOT_VERIFIED') && <Route route={data.route} />}
        {data.steps?.map((step) => <div key={step.step_code}><p>{step.text || step.instruction}</p>{step.caution && <p>{step.caution}</p>}
          {active && <div className="tool-actions">해결됐나요? {[['YES', '예'], ['NO', '아니요 · 다음 안내']].map(([answer, label]) => <button key={answer} disabled={busy} onClick={() => answerSelfCare('step', { step_code: step.step_code, answer, context_version: result.meta.context_version }, `${step.text || step.instruction} — 해결됐나요?\n${answer === 'YES' ? '예' : '아니요'}`)}>{label}</button>)}</div>}
        </div>)}
        {data.references?.map((ref) => <details key={ref.document_id}><summary>{ref.title} · 실행 승인 미확인</summary><p className="source-content">{ref.content}</p></details>)}
        {stepError && <p role="alert">{stepError}</p>}
      </>}
      {name === 'estimate_repair_cost' && <>
        {data.alternatives?.length ? data.alternatives.map((alternative, index) => <article className="cost-alternative" key={alternative.candidate_id || index}>
          <h4>{alternative.part_name || '수리 후기 참고 범위'} · {alternative.cost_source === 'USER_PART_REQUEST' ? '문의한 부품 그룹' : '원인 후보'}</h4>
          {alternative.candidate_basis && <p>{alternative.candidate_basis}</p>}
          <p>참고용 예상 범위(견적 아님): <Amount value={alternative.expected_range || alternative.reference_total_v3} /></p>
          {alternative.surcharge?.amount > 0 && <p>{slots[alternative.surcharge.time_slot]}{alternative.surcharge.month ? ` ${alternative.surcharge.month}월` : ''} 방문 기준 출장비 가산 {money(alternative.surcharge.amount)} 포함</p>}
          {alternative.confidence_grade && <p>신뢰도 {alternative.confidence_grade}{alternative.calibration_cases != null && ` · 국내 같은 부품 사례 ${alternative.calibration_cases}건`}</p>}
          {alternative.warranty_note && <p>{alternative.warranty_note}</p>}
          <p>후보별 대안이며 두 후보를 합산하지 않습니다.</p>
        </article>) : <p>신뢰할 수 있는 비용 자료가 부족합니다. 공식 점검 후 확인해 주세요.</p>}
        {data.basis?.description && <p>기준: {data.basis.description}</p>}
        <p>{data.notice}</p>
        {data.missing_information?.map((reason) => <p key={reason}>{reason}</p>)}
      </>}
      {name === 'get_repair_candidates' && <>
        <p>현재 제품의 실제 원인은 아직 확인되지 않았습니다.</p>
        {data.cause_candidates?.map((cause, index) => <article key={`${cause.evidence_id}-${index}`}><p>{cause.description}</p>{cause.source_urls?.filter((url) => url.startsWith('https://')).slice(0, 1).map((url) => <a key={url} href={url} target="_blank" rel="noreferrer">제조사 근거 확인</a>)}</article>)}
        {data.supported_candidates?.filter((candidate) => candidate.basis === 'USER_PART_REQUEST').map((candidate) => <p key={candidate.id}>{candidate.name} 비용 문의 · 실제 고장 확인 결과는 아닙니다.</p>)}
        {data.missing_information?.map((reason) => <p key={reason}>{reason}</p>)}
      </>}
      {name === 'followup' && <><p>일부 후속 조회를 마치지 못했습니다. 공식 상담 경로를 이용하거나 다음 대화에서 다시 문의해 주세요.</p><Route route={data.route} /></>}
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
