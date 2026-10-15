// Read-only presentation of new results and saved B30 wrappers. Never promotes a
// source procedure to an executable step or rewrites the saved conversation.
const object = (value) => value && typeof value === 'object' && !Array.isArray(value) ? value : {}
export const list = (value) => Array.isArray(value) ? value : []
const unique = (values) => [...new Set(values.filter(Boolean))]
const internal = /(?:sha256|current_QA_review|latest_applicability_update|fieldOrigins|field_verified_at|source_variants|use_policy|DOM_selector|candidate_set_id|context_version)/i
// A drive letter must stand alone, so the "s:/" inside "https://" is not a path.
const filePath = /(?:(?<![A-Za-z])[A-Za-z]:[\\/]|(?:backend|frontend|mcp_server|homecare)[\\/][\w./\\-]+|\b[\w.-]+\.(?:java|py|jsx?|jsonl?|csv)\b)/g
const markdownLink = /\[([^\]\n]+)\]\(([^)\s]+)\)/g
const hash = /\b[0-9a-f]{64}\b/gi

export function sourceUrl(value) {
  if (typeof value !== 'string') return null
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && !url.username && !url.password ? url.href : null
  } catch { return null }
}

// Splits [label](url) out of bubble text. Unsafe targets keep only their label.
export function linkParts(value) {
  const parts = []
  let last = 0
  for (const match of value.matchAll(markdownLink)) {
    if (match.index > last) parts.push({ text: value.slice(last, match.index) })
    parts.push({ text: match[1], href: sourceUrl(match[2]) })
    last = match.index + match[0].length
  }
  if (last < value.length) parts.push({ text: value.slice(last) })
  return parts
}

function parse(value) {
  if (typeof value !== 'string') return object(value)
  try { return object(JSON.parse(value)) } catch { return {} }
}

function jsonEnd(text, start) {
  const stack = []
  let quoted = false, escaped = false
  for (let index = start; index < text.length; index++) {
    const char = text[index]
    if (quoted) {
      if (escaped) escaped = false
      else if (char === '\\') escaped = true
      else if (char === '"') quoted = false
    } else if (char === '"') quoted = true
    else if (char === '{' || char === '[') stack.push(char)
    else if (char === '}' || char === ']') {
      if (stack.pop() !== (char === '}' ? '{' : '[')) return -1
      if (!stack.length) return index + 1
    }
  }
  return -1
}

// Keep ordinary prose, but do not print embedded JSON (including a truncated
// wrapper from an old response or an unfinished stream) in the customer bubble.
export function customerText(value) {
  if (typeof value !== 'string') return ''
  let text = value
  for (let start = 0; start < text.length; start++) {
    if ((text[start] !== '{' && text[start] !== '[') || !/^(?:\{|\[)\s*(?:"|[\]}{])/.test(text.slice(start))) continue
    const end = jsonEnd(text, start)
    text = text.slice(0, start) + '자료의 상세 내용은 아래 근거에서 확인해 주세요.' + (end < 0 ? '' : text.slice(end))
    start += '자료의 상세 내용은 아래 근거에서 확인해 주세요.'.length - 1
  }
  return text.split('\n').filter((line) => !internal.test(line))
    .join('\n').replace(filePath, '참고 자료').replace(hash, '')
    .replace(/```(?:json)?\s*|```/g, '').trim()
}

function strings(value) {
  return unique(list(typeof value === 'string' ? [value] : value).map(customerText))
}

const fields = {
  symptoms: ['symptom', 'symptom_label', 'meaning', 'danger_symptom', 'trigger', 'display_meaning'],
  causes: ['cause', 'causes'],
  conditions: ['preconditions', 'input_preconditions', 'prerequisites', 'applicability_conditions', 'normal_operation_conditions', 'usage_conditions'],
  warnings: ['warnings', 'safety_note', 'safety_priority_note', 'reference_safety_notes', 'safety_constraints', 'safety_conditions', 'prohibits', 'use_limitation', 'material_restrictions', 'conflict_summary'],
}
function legacyFields(record, keys) {
  const content = object(record.content)
  const unknown = list(record.unknown_fields)
  return unique(keys.filter((key) => !unknown.includes(key)).flatMap((key) => [...strings(record[key]), ...strings(content[key])]))
}

const gates = {
  requires_original_diagram_confirmation: '원문의 그림과 실제 제품이 일치하는지 확인이 필요합니다.',
  requires_original_diagram_match: '원문의 그림과 실제 제품이 일치하는지 확인이 필요합니다.',
  requires_actual_control_or_component_match: '실제 제품의 조작부·부품이 원문과 일치하는지 확인이 필요합니다.',
  requires_actual_component_match: '실제 제품의 부품이 원문과 일치하는지 확인이 필요합니다.',
  requires_actual_WiFi_equipped_product: '실제 제품의 Wi-Fi 기능 확인이 필요합니다.',
  requires_actual_product_registration: '실제 제품의 앱 등록 상태 확인이 필요합니다.',
  requires_actual_interior_light: '실제 제품의 내부 조명 확인이 필요합니다.',
  requires_actual_hot_air_filter: '실제 제품의 열풍 필터 확인이 필요합니다.',
  requires_reassembly_reference_for_removed_parts: '분리된 부품의 재조립 참고 자료 확인이 필요합니다.',
  requires_actual_material_and_sensor_confirmation: '실제 제품의 재질과 센서 확인이 필요합니다.',
  requires_actual_replacement_part_match: '교체 부품과 실제 제품의 일치 여부 확인이 필요합니다.',
  requires_actual_liquid_detergent_cup: '실제 제품의 액체 세제 컵 확인이 필요합니다.',
  requires_actual_ThinQ_feature_and_registration: '실제 제품의 ThinQ 기능과 앱 등록 상태 확인이 필요합니다.',
  requires_actual_hose_spring: '실제 제품의 호스 스프링 확인이 필요합니다.',
  requires_actual_first_use: '제품의 실제 첫 사용 여부 확인이 필요합니다.',
  requires_current_panel_material_match: '현재 제품 패널의 재질이 원문과 일치하는지 확인이 필요합니다.',
  requires_actual_detached_drain_filter_identification: '실제로 분리된 배수 필터의 식별 확인이 필요합니다.',
  requires_installed_folding_shelf: '실제 제품에 접이식 선반이 설치돼 있는지 확인이 필요합니다.',
  requires_physical_confirmation: '실물의 구조와 상태 확인이 필요합니다.',
  requires_feature_confirmation: '제품에 해당 기능이 있는지 확인이 필요합니다.',
  requires_live_state_confirmation: '현재 제품의 작동 상태 확인이 필요합니다.',
  incomplete_source_steps_for_standalone_execution: '원문만으로 실행 절차를 확인할 수 없습니다.',
  not_for_standalone_execution: '이 자료만으로 조치를 실행할 수 없습니다.',
}
function requirements(value) {
  return unique(Object.values(object(value)).flatMap((group) => Object.entries(object(group))
    .filter(([key, enabled]) => gates[key] && enabled === true).map(([key]) => gates[key])))
}

function branches(value) {
  return Object.values(object(value)).flatMap((group) => list(group).map((branch) => {
    const item = object(branch)
    return {
      description: unique(['cause', 'subcase', 'condition', 'condition_summary', 'component_name_in_source'].flatMap((key) => strings(item[key]))).join(' · '),
      conditions: unique([...fields.conditions.flatMap((key) => strings(item[key])), ...requirements({ branch: item })]),
      warnings: fields.warnings.flatMap((key) => strings(item[key])),
    }
  })).filter((branch) => branch.description || branch.conditions.length || branch.warnings.length)
}

function officialSources(values) {
  const seen = new Set()
  return values.map((value) => typeof value === 'string' ? { url: value } : object(value))
    .flatMap((source) => {
      const url = sourceUrl(source.url || source.source_url)
      if (!url || seen.has(url)) return []
      seen.add(url)
      const locator = object(source.locator || source.source_locator)
      return [{ url, location: customerText(source.location) || strings([locator.section, locator.topic_title, locator.page != null ? `${locator.page}쪽` : '']).join(' · ') }]
    })
}

export function knowledgeView(source) {
  const doc = object(source)
  const embedded = parse(doc.content || doc.text)
  const wrapper = embedded.record ? embedded : doc.record ? doc : null
  const record = object(wrapper?.record)
  const legacy = Boolean(wrapper)
  const symptoms = legacy ? legacyFields(record, fields.symptoms) : strings(doc.symptoms)
  const causes = legacy ? legacyFields(record, fields.causes) : strings(doc.causes)
  const conditions = legacy ? unique([...legacyFields(record, fields.conditions), ...legacyFields(object(wrapper.current_QA_review), fields.conditions)]) : strings(doc.preconditions || doc.conditions)
  const warnings = legacy ? unique([...legacyFields(record, fields.warnings), ...legacyFields(object(wrapper.current_QA_review), fields.warnings)]) : strings(doc.warnings)
  const originalTitle = customerText(doc.title || doc.displayTitle)
  const id = doc.document_id || doc.documentId || doc.id || wrapper?.id
  const title = originalTitle && originalTitle !== id ? originalTitle : customerText(record.title || record.procedure_title || object(record.source_locator).topic_title) || '제조사 참고 자료'
  const prose = [...symptoms, ...causes]
  const content = legacy || Object.keys(embedded).length ? '' : customerText(doc.content || doc.displayText || doc.text)
  const urls = [...list(doc.official_sources || doc.sources || doc.sourceUrls), ...list(wrapper?.sourceUrls)]
  if (legacy) {
    urls.push(record.source_url, object(record.content).source_url)
    for (const key of ['additional_sources', 'additional_safety_sources', 'supporting_sources', 'supplemental_sources', 'related_procedure_sources', 'exact_manual_sources']) urls.push(...list(record[key]))
  }
  const applicability = object(doc.model_applicability || doc.modelApplicability || wrapper?.effective_model_lists)
  const modelCodes = unique([...strings(applicability.approved_model_codes), ...strings(applicability.original_model_codes), ...strings(doc.modelCodes), ...(legacy ? strings(record.model_codes || record.approved_model_codes) : [])])
  return {
    id, title, symptoms, causes, conditions, warnings,
    // Structured prose duplicates the fields above; only older plain-text sources need a body.
    content: [...prose, ...conditions, ...warnings].length || content === title ? '' : content,
    requirements: requirements(legacy ? { original: record, qa: wrapper.current_QA_review, update: wrapper.latest_applicability_update } : doc.requirements || doc.constraints),
    branches: branches(legacy ? Object.fromEntries(['action_branches', 'cause_branches', 'branches', 'conditional_subcases', 'model_feature_subcases', 'component_branches'].map((key) => [key, record[key]])) : doc.branches),
    sources: officialSources(urls.filter(Boolean)), modelCodes,
    excludedModels: strings(applicability.excluded_model_codes || record.excluded_model_codes),
    conditionalModels: strings(applicability.conditional_model_codes || record.conditional_model_codes),
    applicability: { MODEL_LISTED: '모델 목록에 포함 · 조건 확인 필요', VERIFIED_CATEGORY_COMMON: '검토된 품목 공통 자료', UNDETERMINED: '모델 적용 확인 필요' }[doc.applicability_status] || '모델 적용 확인 필요',
    legacy,
  }
}

export function collectKnowledge(tools, sources) {
  const diagnostic = object(object(tools).get_diagnostic_plan).data || {}
  const search = object(object(tools).search_knowledge).data || {}
  const entries = [
    ...list(diagnostic.safety_notices).map((doc) => ({ doc, safety: true })),
    ...list(search.safety_notices).map((doc) => ({ doc, safety: true })),
    ...list(diagnostic.references).map((doc) => ({ doc })),
    ...list(search.documents).map((doc) => ({ doc })),
    ...list(sources).map((doc) => ({ doc })),
  ]
  const found = new Map()
  for (const { doc, safety = false } of entries) {
    if (!doc || typeof doc !== 'object' || Array.isArray(doc)) continue
    const view = knowledgeView(doc)
    const key = view.id || `${view.title}\n${view.content}\n${view.symptoms.join('\n')}`
    const existing = found.get(key)
    if (!existing) found.set(key, { ...view, safety })
    else {
      // Prefer the structured result; old RagSource has fewer fields. Preserve
      // missing source links and safety priority from another occurrence.
      existing.safety ||= safety
      existing.sources = officialSources([...existing.sources, ...view.sources])
    }
    for (const supplement of list(object(doc).supplements)) {
      const extra = knowledgeView(supplement)
      if (!found.has(extra.id || extra.title)) found.set(extra.id || extra.title, { ...extra, supplement: true, safety })
    }
  }
  return [...found.values()].sort((a, b) => Number(b.safety) - Number(a.safety))
}

export function warrantyView(message) {
  const saved = object(message.warranty)
  const tool = object(object(message.tools).check_warranty)
  const data = Object.keys(saved).length ? saved : object(tool.data)
  return data.productWarranty ? data : null
}
