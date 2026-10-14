import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { collectKnowledge, customerText, knowledgeView, sourceUrl, warrantyView } from './customerDisplay.js'

const resources = new URL('../../backend/src/main/resources/homecare/', import.meta.url)
const raw = JSON.parse(readFileSync(new URL('knowledge.json', resources), 'utf8'))
const structured = JSON.parse(readFileSync(new URL('knowledge-structured.json', resources), 'utf8'))
const original = (id) => raw.find((doc) => doc.id === id)
const projected = (id) => {
  const doc = structured.find((doc) => doc.id === id)
  return { ...doc, document_id: id, content: doc.displayText, title: doc.displayTitle, official_sources: doc.sources, model_applicability: doc.modelApplicability }
}
const forbidden = /current_QA_review|source_branch_index|original_record_sha256|fieldOrigins|DOM_selector|source_variants|use_policy|homecare\/|\.java\b|[a-f0-9]{64}/i

test('actual vibration document keeps its three separate conditions and the optional feature gate', () => {
  const doc = knowledgeView(projected('B17-MFL71792818-topic_66-009'))
  assert.ok(doc.symptoms.includes('소음 또는 진동이 심해요.'))
  assert.equal(doc.branches.length, 3)
  assert.match(doc.branches[2].conditions.join(' '), /해당 기능이 실제 있는 모델/)
  assert.ok(doc.sources[0].url.includes('#topic_66'))
  assert.equal(doc.content, '')
  assert.doesNotMatch(JSON.stringify(doc), /action_summary|수평 조정 다리로 수평을 맞춘다/)
})

test('stored wrappers retain symptoms, original and reviewed conditions, official sources and excluded models', () => {
  for (const id of ['B17-MFL71792818-topic_66-009', 'T2-003', 'B19-MFL05882501-topic_37-procedure']) {
    const saved = original(id)
    const snapshot = JSON.stringify(saved)
    const doc = knowledgeView({ documentId: id, title: saved.title, content: saved.text })
    assert.equal(doc.legacy, true)
    assert.doesNotMatch(JSON.stringify(doc), forbidden)
    assert.ok(doc.sources.length)
    assert.equal(JSON.stringify(saved), snapshot)
    if (id === 'T2-003') { assert.deepEqual(doc.symptoms, ['배수 불량']); assert.match(doc.causes[0], /호스/); assert.equal(doc.content, '') }
    if (id.startsWith('B19')) assert.deepEqual(doc.excludedModels, ['G646GBB021', 'G646GBB031'])
  }
  const doc = knowledgeView({ content: JSON.stringify({ record: { symptom: '증상', preconditions: ['원래 조건'] }, current_QA_review: { preconditions: ['검토 조건'], decision_summary: '내부 검토 이력' } }) })
  assert.deepEqual(doc.conditions, ['원래 조건', '검토 조건'])
  assert.doesNotMatch(JSON.stringify(doc), /내부 검토 이력/)
})

test('all 1542 preserved wrappers and projections produce display fields without metadata or execution fields', () => {
  assert.equal(raw.length, 1542)
  for (const source of [...raw, ...structured]) {
    const view = knowledgeView(source)
    assert.doesNotMatch(JSON.stringify(view), forbidden, source.id)
    assert.equal(view.steps, undefined, source.id)
    assert.equal(view.is_action_approved, undefined, source.id)
  }
})

test('one document in diagnostic, search and saved sources appears once, with safety first and supplements separate', () => {
  const doc = projected('B17-MFL71792818-topic_66-009')
  const safety = { document_id: 'safety', title: '안전 주의', content: '사용을 멈추세요.' }
  const supplement = { document_id: 'supplement', title: '보완 자료', preconditions: ['그림 확인'] }
  const tools = { get_diagnostic_plan: { data: { references: [{ ...doc, supplements: [supplement] }] } }, search_knowledge: { data: { documents: [doc], safety_notices: [safety, safety] } } }
  const sources = [{ documentId: doc.id, title: doc.title, content: doc.content, file: 'homecare/knowledge.json' }]
  const snapshot = JSON.stringify({ tools, sources })
  const result = collectKnowledge(tools, sources)
  assert.equal(result.length, 3)
  assert.equal(result[0].safety, true)
  assert.equal(result.filter((value) => value.id === doc.id).length, 1)
  assert.equal(result.find((value) => value.id === 'supplement').supplement, true)
  assert.equal(JSON.stringify({ tools, sources }), snapshot)
})

test('plain historical evidence remains readable and embedded, array or truncated JSON stays out of bubbles', () => {
  assert.equal(knowledgeView({ content: '기존 매뉴얼 설명', title: '기존 자료' }).content, '기존 매뉴얼 설명')
  for (const json of [JSON.stringify(original('T2-003')), '[{"sha256":"secret"}]', '{"record":{"id":']) {
    assert.doesNotMatch(customerText(`기존 답변\n${json}`), /record|sha256|secret|\{/)
  }
  assert.equal(customerText('OE 오류 · 124,569원 ~ 230,145원 · 2026-10-15'), 'OE 오류 · 124,569원 ~ 230,145원 · 2026-10-15')
  assert.doesNotMatch(customerText('homecare/knowledge.json backend/Tool.java\ncurrent_QA_review: test\n' + 'a'.repeat(64)), forbidden)
  assert.equal(knowledgeView({ content: '{"unknown":"internal"}' }).content, '')
  assert.equal(customerText(null), '')
})

test('unsafe links are omitted and missing warranty values are never recalculated', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html,x', '/local', 'https://user:secret@example.com', null]) assert.equal(sourceUrl(url), null)
  assert.equal(sourceUrl('https://www.lge.co.kr/support'), 'https://www.lge.co.kr/support')
  const saved = { productWarranty: { periodStatus: 'EXPIRED', expiresOn: '2020-01-01' } }
  assert.equal(warrantyView({ warranty: saved, tools: { check_warranty: { data: { productWarranty: { periodStatus: 'IN_WARRANTY' } } } } }), saved)
  assert.equal(warrantyView({ tools: { check_warranty: { data: saved } } }), saved)
  assert.equal(warrantyView({}), null)
})
