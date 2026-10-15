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

test('manual links in the reply render as anchors instead of markdown', () => {
  const html = render({ content: '근거입니다. [제조사 설명서](https://gscs-manual.lge.com/CNZ/MFL71401523/ko-kr/main.html#topic_45)', tools: {} })
  assert.match(html, /<a href="https:\/\/gscs-manual\.lge\.com\/CNZ\/MFL71401523\/ko-kr\/main\.html#topic_45" target="_blank" rel="noreferrer">제조사 설명서<\/a>/)
  assert.doesNotMatch(html, /\]\(|참고 자료/)
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

test('purchase-based warranty displays expiry separately from unconfirmed free-repair conditions', () => {
  const html = render({ content: '등록 구매일 기준 보증기간을 확인했어요.', warranty: {
    category: 'REFRIGERATOR', headline: '등록 구매일 기준 제품 전체 보증기간 안입니다.',
    asOfDate: '2026-10-15', basisSource: 'PURCHASE', basisDate: '2026-05-05',
    productWarranty: { name: '제품 전체 보증', periodMonths: 12, expiresOn: '2027-05-05',
      periodStatus: 'IN_WARRANTY', applicability: 'NEEDS_CONFIRMATION',
      partFee: 'CONFIRM_REQUIRED', laborFee: 'CONFIRM_REQUIRED', visitFee: 'CONFIRM_REQUIRED' },
    partWarranties: [], notes: [],
  } })
  for (const expected of ['구매일 2026-05-05', '보증기간 안', '만료일: 2027-05-05', '무상 수리와 비용 적용은']) assert.ok(html.includes(expected), expected)
  assert.doesNotMatch(html, /아래 조건이 이 제품에 적용되는지|조건 충족 시 무상|0원/)
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

test('service connection card states the region and official booking instead of an empty center lookup', () => {
  const connection = (status, region_status, base_region) => envelope({ directory_status: 'NOT_INTEGRATED', region_status, base_region, centers: [], booking_status: 'NOT_SUBMITTED', route }, status)
  const known = render({ content: '점검 안내입니다.', tools: { find_service_centers: connection('OK', 'KNOWN', { sido: '제주', sigungu: '제주시' }) } })
  assert.match(known, /A\/S 연결 <small>공식 연결<\/small>/)
  assert.match(known, /제주 제주시 설치 제품 · 점검은 LG 공식 출장 접수로 신청할 수 있어요/)
  assert.match(known, /아직 접수·예약하지 않았습니다/)
  assert.doesNotMatch(known, /조회 완료|센터 자료가 없습니다/)
  const missing = render({ content: '점검 안내입니다.', tools: { find_service_centers: connection('NEEDS_INPUT', 'REQUIRED', { sido: '', sigungu: '' }) } })
  assert.match(missing, /지역 확인 필요/)
  assert.match(missing, /주소 검색으로 설치 주소를 다시 저장해 주세요/)
  assert.ok(missing.includes(route.apply_url))
  const saved = render({ content: '이전 안내입니다.', tools: { find_service_centers: envelope({ base_region: { sido: '서울', sigungu: '강남구' }, fallback_level: 'SIGUNGU', centers: [{ id: 'C1', name: '대치서비스센터', address: '서울 강남구' }], route }) } })
  assert.match(saved, /저장 당시의 센터 안내입니다/)
  assert.match(saved, /대치서비스센터/)
})

test('a missing reviewed plan is described as such, not as a blocked approval', () => {
  const html = render({ content: '설명입니다.', tools: { get_diagnostic_plan: envelope({ skip_reason: 'NOT_VERIFIED', steps: [], route }, 'NOT_VERIFIED') } })
  assert.match(html, /자가검진 <small>검토된 절차 없음<\/small>/)
  assert.match(html, /검토를 마친 단계별 자가점검 절차가 아직 없어요/)
  assert.doesNotMatch(html, /실행 승인 미확인/)
})

test('summary response shows one card with warranty, cost and A/S instead of separate tool cards', () => {
  const card = { type: 'SUMMARY', lead: '안내한 항목을 모두 확인했지만 해결되지 않았어요. 점검이 필요해요.',
    warranty: { status: 'IN_WARRANTY', text: '구매일 2026-05-05 기준 제품 보증기간 안이에요(만료 2027-05-05). 무상 수리 여부는 점검 때 확정돼요.' },
    estimate: { status: 'OK', base: { min: 48195, max: 184042, parts: ['제상 센서', '응축기(기계실) 팬모터', '메인 PCB'] },
      separate: [{ id: 'R01', name: '컴프레서', min: 231395, max: 427509, is_core: true }], overall: { min: 48195, max: 427509 },
      candidates: [{ id: 'R01', name: '컴프레서', min: 231395, max: 427509, repair_cases: 6, official_facts: 1, guides: 0 }] },
    region: { sido: '인천', sigungu: '연수구' }, centers: [], route, handoff_summary: '품목: 냉장고' }
  const html = render({ content: '안내한 항목을 모두 확인했지만 해결되지 않았어요.', warranty: { productWarranty: { periodStatus: 'IN_WARRANTY' }, partWarranties: [] }, tools: {
    card: envelope(card), check_warranty: envelope({ productWarranty: { periodStatus: 'IN_WARRANTY' }, partWarranties: [] }),
    find_service_centers: envelope({ directory_status: 'NOT_INTEGRATED', region_status: 'KNOWN', route }), prepare_service_handoff: envelope({ summary_text: '품목: 냉장고', route }),
  } })
  assert.equal((html.match(/class="tool-result/g) || []).length, 1)
  for (const expected of ['점검 안내', '보증기간 안이에요', '약 48,000원 ~ 184,000원', '컴프레서 고장이면 약 231,000원 ~ 428,000원', '전체 범위 약 48,000원 ~ 428,000원',
    '인천 연수구 설치 제품', '출장 수리 접수', '상담원 연결 1544-7777', '상담 내용 복사', '국내 수리 사례 6건']) assert.ok(html.includes(expected), expected)
  assert.doesNotMatch(html, /제품 전체 보증<\/h3>|상담원 전달 요약|A\/S 연결 <small>/)
})

test('the self-check card starts at its first step and sends no chat options of its own question', () => {
  const question = { id: 'SELF_CHECK', text: '안내한 항목을 확인한 뒤 해결됐나요?', options: ['해결됐어요', '아니요, 해결되지 않았어요'] }
  const steps = ['정전 여부와 전원 플러그 연결을 확인해 주세요.', '보관 온도를 확인해 주세요.', '문 닫힘 상태를 확인해 주세요.']
  const message = { content: '‘냉장·냉동이 잘되지 않아요’ 증상으로 확인했어요.', tools: { consultation: envelope({ question }), card: envelope({ type: 'SELF_CHECK', steps }) } }
  const html = render(message)
  assert.equal((html.match(/class="tool-result/g) || []).length, 1)
  assert.match(html, /직접 확인해 보세요 · 1\/3단계/)
  assert.ok(html.includes(steps[0]))
  assert.ok(!html.includes(steps[1]))
  assert.match(html, />해결됐어요<\/button>/)
  assert.match(html, />아니요, 다음 단계<\/button>/)
  assert.doesNotMatch(html, /안내한 항목을 확인한 뒤|해결되지 않았어요<\/button>|제조사 근거/)
  // History shows the whole list without answers; a single step answers "not solved" at once.
  const saved = render(message, false)
  for (const step of steps) assert.ok(saved.includes(step), step)
  assert.doesNotMatch(saved, /<button/)
  assert.match(render({ ...message, tools: { ...message.tools, card: envelope({ type: 'SELF_CHECK', steps: [steps[0]] }) } }), />아니요, 해결되지 않았어요<\/button>/)
})
