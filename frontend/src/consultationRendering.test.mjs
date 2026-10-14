import test, { after, before } from 'node:test'
import assert from 'node:assert/strict'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { readFileSync } from 'node:fs'

let server, Message, Trace
before(async () => {
  server = await createServer({ server: { middlewareMode: true }, appType: 'custom', logLevel: 'error' })
  Message = (await server.ssrLoadModule('/src/components/ConsultationMessage.jsx')).default
  Trace = (await server.ssrLoadModule('/src/components/ToolCallTrace.jsx')).default
})
after(async () => { await server?.close() })
const render = (message, active = true, disabled = false) => renderToStaticMarkup(createElement(Message, { message, active, disabled }))
const envelope = (data, status = 'OK') => ({ status, data, meta: { context_version: 7 }, warnings: [], caveats: [] })
const source = { document_id: 'same', title: '진동 근거', symptoms: ['본체 흔들림'], preconditions: ['모델과 설치 상태 확인'], warnings: ['조건이 다르면 적용하지 마세요.'], official_sources: [{ url: 'https://www.lge.co.kr/support', locator: { section: '소음과 진동' } }] }
const route = { apply_url: 'https://www.lge.co.kr/support/service-engineer-reservation', center_finder_url: 'https://www.lge.co.kr/support/find-service-center', phone: '1544-7777' }

test('rendered response shows one structured document and keeps file paths, raw JSON and internal evidence out', () => {
  const html = render({ content: '진동 증상을 확인합니다.', sources: [{ documentId: 'same', content: '{"current_QA_review":{}}', file: 'homecare/knowledge.json' }], tools: {
    get_diagnostic_plan: envelope({ skip_reason: 'NOT_VERIFIED', references: [source], route }, 'NOT_VERIFIED'),
    search_knowledge: envelope({ documents: [source] }),
  } })
  assert.equal((html.match(/<summary>진동 근거<\/summary>/g) || []).length, 1)
  assert.match(html, /모델과 설치 상태 확인/)
  assert.match(html, /공식 출처 · 소음과 진동/)
  assert.doesNotMatch(html, /homecare\/|current_QA_review|document_id|OFFICIAL_MANUAL/)
  assert.doesNotMatch(html, /해결됐나요/)
})

test('real stored wrapper renders customer fields, preserves source bytes and exposes original only in DEV trace', () => {
  const docs = JSON.parse(readFileSync(new URL('../../backend/src/main/resources/homecare/knowledge.json', import.meta.url), 'utf8'))
  const doc = docs.find((item) => item.id === 'T2-003')
  const message = { id: 1, content: `자료를 확인했습니다.\n${doc.text}`, sources: [{ documentId: doc.id, title: doc.title, file: 'homecare/knowledge.json', content: doc.text }] }
  const snapshot = JSON.stringify(message)
  const html = render(message, false)
  assert.match(html, /배수 불량/)
  assert.match(html, /저장 당시 자료/)
  assert.match(html, /공식 출처/)
  assert.doesNotMatch(html, /current_QA_review|original_record_sha256|use_policy|homecare\/|&quot;record&quot;/)
  assert.equal(JSON.stringify(message), snapshot)
  const dev = renderToStaticMarkup(createElement(Trace, { message, conversationId: 1 }))
  assert.match(dev, /원본 답변과 검색 근거/)
  assert.match(dev, /current_QA_review/)
})

test('shared safety evidence is open once before any self-care controls', () => {
  const safety = { ...source, document_id: 'safety', title: '사용 중지 주의', warnings: ['이상이 있으면 사용을 멈추세요.'] }
  const html = render({ content: '현재 상태를 확인해 주세요.', tools: {
    get_diagnostic_plan: envelope({ safety_notices: [safety], question: { text: '조건이 맞나요?' } }),
    search_knowledge: envelope({ safety_notices: [safety], documents: [] }),
  } })
  assert.equal((html.match(/<summary>안전 주의 · 사용 중지 주의<\/summary>/g) || []).length, 1)
  assert.match(html, /<details open="">/)
  assert.ok(html.indexOf('안전 주의 근거') < html.indexOf('조건이 맞나요'))
})

test('symptom options, current self-care controls and saved historical instructions remain in the conversation', () => {
  const question = { text: '어떤 증상인가요?', options: ['흔들림', '배수 불량'] }
  const message = { content: question.text, tools: { consultation: envelope({ question }), get_diagnostic_plan: envelope({ steps: [{ step_code: 'UE_1', text: '승인된 단계 안내', caution: '제품이 멈췄는지 확인하세요.' }] }) } }
  const current = render(message)
  assert.equal((current.match(/어떤 증상인가요/g) || []).length, 1)
  assert.match(current, /해결됐나요/)
  assert.match(current, /아니요 · 다음 안내/)
  const history = render(message, false)
  assert.match(history, /승인된 단계 안내/)
  assert.match(history, /button type="button" disabled="">흔들림/)
  assert.doesNotMatch(history, /해결됐나요/)
  assert.doesNotMatch(render(message, true, true), /해결됐나요/)
  const pending = render({ content: '조건을 확인해 주세요.', tools: { get_diagnostic_plan: envelope({ question: { id: 'power', text: '전원이 꺼져 있나요?', source: 'https://www.lge.co.kr/support' }, plan_scope: 'test' }, 'NEEDS_INPUT') } })
  assert.match(pending, /전원이 꺼져 있나요/)
  assert.match(pending, /모르겠어요/)
  for (const risk_status of ['SUSPECT', 'CONFIRMED']) {
    const blocked = render({ ...message, tools: { ...message.tools, safety: envelope({ risk_status }) } })
    assert.match(blocked, /안전 확인이 우선/)
    assert.doesNotMatch(blocked, /승인된 단계 안내|해결됐나요/)
  }
  assert.doesNotMatch(render({ ...message, tools: { get_diagnostic_plan: envelope({ steps: [{ text: '미승인 단계' }] }, 'NOT_VERIFIED') } }), /미승인 단계/)
})

test('cost, warranty and service results retain verified amounts, unknowns, conditional fees and prepared-only meaning', () => {
  const html = render({ content: '참고 비용과 공식 상담 경로입니다.', tools: {
    check_warranty: envelope({ category: 'WASHER', headline: '보증 확인', asOfDate: '2026-10-15', productWarranty: { name: '제품 전체', periodMonths: 12, periodStatus: 'IN_WARRANTY', partFee: 'CONDITIONAL_FREE', laborFee: 'CONFIRM_REQUIRED', visitFee: 'CONFIRM_REQUIRED' }, partWarranties: [] }),
    estimate_repair_cost: envelope({ alternatives: [{ part_name: '메인 PCB', cost_source: 'USER_PART_REQUEST', expected_range: { min: 124569, max: 230145, median: 169319 }, confidence_grade: '중간', calibration_cases: 4 }, { expected_range: { status: 'UNKNOWN' } }], notice: '공식 점검 후 실제 비용이 결정됩니다.' }),
    find_service_centers: envelope({ centers: [], route }),
    prepare_service_handoff: envelope({ summary_text: '모델: TR15WV5\n증상: UE\n자가조치: 미해결', route }),
    followup: envelope({ route }, 'PARTIAL'),
  } })
  for (const expected of ['124,569원', '230,145원', '169,319원', '추가 확인 필요', '조건 충족 시 무상', '두 후보를 합산하지 않습니다', '실제 접수는 공식 채널', '일부 후속 조회를 마치지 못했습니다']) assert.ok(html.includes(expected), expected)
  assert.doesNotMatch(html, /NaN|undefined|OFFICIAL_|예약 완료/)
  assert.equal((html.match(/aria-label="세탁기 보증 확인 결과"/g) || []).length, 1)
})

test('malformed or incomplete saved results omit unsafe links, raw values and false handoff completion', () => {
  const html = render({ content: '{"record":', sources: [null, { content: '{"unknown":true}' }], tools: {
    get_diagnostic_plan: envelope({ references: [null], steps: null, question: { text: '현재 상태를 확인해 주세요.', source: 'javascript:alert(1)' } }),
    prepare_service_handoff: envelope({ route: { apply_url: 'javascript:alert(1)', phone: 'undefined' } }, 'ERROR'),
    check_warranty: envelope({ productWarranty: {}, notes: null, partWarranties: null }),
    find_service_centers: null,
    estimate_repair_cost: envelope({ alternatives: [{ expected_range: { min: 'not-a-number', max: null } }] }),
  } })
  assert.match(html, /전달할 요약을 준비하지 못했습니다/)
  assert.doesNotMatch(html, /javascript:|NaN|undefined|전달할 요약이 준비됐습니다|&quot;record&quot;/)
})
