// Browser smoke test of the actual presentation components with local fixtures.
// No account, backend, model calls, external requests or saved-data mutations.
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createServer } from 'vite'

const resources = new URL('../../backend/src/main/resources/homecare/', import.meta.url)
const read = async (name) => JSON.parse(await readFile(new URL(name, resources), 'utf8'))
const raw = (await read('knowledge.json')).find((doc) => doc.id === 'T2-003')
const plan = (await read('approved-plans.json'))[0]
const route = await read('route.json')
const warrantyVerification = process.env.HOMECARE_WARRANTY_VERIFICATION_OUTPUT
  ? JSON.parse(await readFile(process.env.HOMECARE_WARRANTY_VERIFICATION_OUTPUT, 'utf8')) : null
const fixture = { raw, plan, route, warrantyVerification }
const entry = `
import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import Message from '/src/components/ConsultationMessage.jsx'
import Trace from '/src/components/ToolCallTrace.jsx'
import '/src/index.css'
import '/src/App.css'
const {raw, plan, route, warrantyVerification} = ${JSON.stringify(fixture)}
const wrap = (data, status = 'OK') => ({data, status, meta: {context_version: 7}})
const history = {id: 1, content: '저장한 자료입니다.\\n' + raw.text, sources: [{documentId: raw.id, title: raw.title, content: raw.text, file: 'homecare/knowledge.json'}]}
const final = {content: '미해결 상담의 참고 비용과 공식 경로입니다.', tools: {
  get_diagnostic_plan: wrap({skip_reason: 'ALL_DONE', steps: [], route}),
  estimate_repair_cost: wrap({alternatives: [{part_name: '메인 PCB', cost_source: 'USER_PART_REQUEST', expected_range: {min: 124569, median: 169319, max: 230145}, confidence_grade: '중간', calibration_cases: 4}], notice: '참고용이며 공식 점검 후 실제 비용이 결정됩니다.'}),
  check_warranty: wrap({category: 'WASHER', headline: '보증 확인', productWarranty: {name: '제품 전체', periodMonths: 12, periodStatus: 'IN_WARRANTY', partFee: 'CONDITIONAL_FREE', laborFee: 'CONFIRM_REQUIRED', visitFee: 'CONFIRM_REQUIRED'}, partWarranties: []}),
  find_service_centers: wrap({centers: [], route}),
  prepare_service_handoff: wrap({summary_text: '모델: TR15WV5\\n증상: UE\\n자가조치: 미해결', route})
}}
window.calls = []
const questions = [
  {id: 'SOUND', label: '작동 소리', text: '작동 소리가 나나요?', note: '조용한 제품은 소리가 작을 수 있어요.', options: [{value: 'YES', label: '소리가 나요'}, {value: 'NO', label: '소리가 안 나요'}]},
  {id: 'FROST', label: '냉동실 성에', text: '성에가 두껍게 끼어 있나요?', note: null, options: [{value: 'BACK_WALL', label: '안쪽 벽에 두껍게 있어요'}, {value: 'NONE', label: '없어요'}]},
]
function App() {
  const [phase, setPhase] = useState(0)
  const current = phase === 0 ? {content: '원문과 실제 제품 조건을 확인해 주세요.', tools: {get_diagnostic_plan: wrap({question: plan.preconditions[0], plan_scope: 'SAVED_SCOPE'}, 'NEEDS_INPUT')}}
    : phase === 1 ? {content: '다음 승인 단계입니다.', tools: {get_diagnostic_plan: wrap({steps: [plan.steps[0]]})}}
    : phase === 2 ? final
    : phase === 3 ? {content: '어떤 증상인가요?', tools: {consultation: wrap({question: {text: '어떤 증상인가요?', options: ['흔들림', '배수 불량']}})}}
    : phase === 4 ? {content: '위험 상황을 확인해 주세요.', tools: {safety: wrap({risk_status: 'SUSPECT', question: '현재 위험 상황인가요?'})}}
    : phase === 7 ? {content: '증상으로 확인했어요.', tools: {consultation: wrap({question: {id: 'SELF_CHECK', text: '안내한 항목을 확인한 뒤 해결됐나요?', options: ['해결됐어요', '아니요, 해결되지 않았어요']}}),
        card: wrap({type: 'SELF_CHECK', steps: ['첫째 항목을 확인해 주세요.', '둘째 항목을 확인해 주세요.', '셋째 항목을 확인해 주세요.'], questions})}}
    : phase === 8 ? {content: '점검 방법은 없어요.', tools: {card: wrap({type: 'SELF_CHECK', steps: [], questions})}}
    : phase === 5 ? {...warrantyVerification.response, content: warrantyVerification.response.reply}
    : warrantyVerification.savedAnswer
  window.setPhase = setPhase
  return <><main id="customer" style={{maxWidth: 800, margin: 'auto', padding: 16}}>
    <h1>상담 표시 검증</h1><div className="message-content"><Message message={history} active={false} />
    <Message key={phase} message={current} active onAnswerSelfCare={async (kind, payload) => {window.calls.push({kind, payload}); setPhase((value) => value + 1)}} onSelectSymptom={(event, option) => {event.preventDefault(); window.calls.push({kind: 'symptom', option})}} onAnswerRisk={(answer, version) => window.calls.push({kind: 'risk', answer, version})} onSelfCheck={(result, step, version, label, observations) => window.calls.push({kind: 'selfCheck', result, step, version, label, observations})} /></div>
  </main><aside id="dev"><Trace request={async () => []} conversationId={77} message={history} /></aside></>
}
createRoot(document.getElementById('root')).render(<App />)
`

const browserPath = process.env.BROWSER_EXECUTABLE || (process.platform === 'win32' ? 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe' : '/usr/bin/chromium')
const profile = await mkdtemp(join(tmpdir(), 'homecare-stage7-'))
let browser, socket, server
const pending = new Map()
let sequence = 0
function cdp(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)) }, 10_000)
    pending.set(id, { resolve, reject, timer })
    socket.send(JSON.stringify({ id, method, params }))
  })
}
async function evaluate(expression) {
  const result = await cdp('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (result.exceptionDetails) throw new Error(JSON.stringify(result.exceptionDetails))
  return result.result.value
}
async function until(expression) {
  for (let attempt = 0; attempt < 100; attempt++) {
    if (await evaluate(expression)) return
    await new Promise((resolve) => setTimeout(resolve, 50))
  }
  throw new Error(`Browser condition failed: ${expression}`)
}
const report = []
const purchaseWarrantyChecks = []
try {
  server = await createServer({ logLevel: 'error', server: { host: '127.0.0.1', port: 0, strictPort: false }, plugins: [{
    name: 'local-display-fixtures',
    configureServer(vite) {
      vite.middlewares.use(async (request, response, next) => {
        if (request.url !== '/__display.html') return next()
        response.setHeader('Content-Type', 'text/html; charset=utf-8')
        const html = '<!doctype html><html lang="ko"><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body><div id="root"></div><script type="module" src="/\u0040id/virtual:display-fixture.jsx"></script></body></html>'
        try { response.end(await vite.transformIndexHtml('/__display.html', html)) } catch (error) { next(error) }
      })
    },
    resolveId(id) { if (id === 'virtual:display-fixture.jsx') return id },
    load(id) { if (id === 'virtual:display-fixture.jsx') return entry },
  }] })
  await server.listen()
  const origin = `http://127.0.0.1:${server.httpServer.address().port}`
  browser = spawn(browserPath, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { windowsHide: true, stdio: 'ignore' })
  const launched = new Promise((_, reject) => browser.once('error', reject))
  let port
  await Promise.race([launched, (async () => {
    for (let attempt = 0; attempt < 100; attempt++) {
      try { port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]); return } catch { await new Promise((resolve) => setTimeout(resolve, 100)) }
    }
    throw new Error('Browser did not expose the local debugging port')
  })()])
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
  socket = new WebSocket(targets.find((target) => target.type === 'page').webSocketDebuggerUrl)
  socket.addEventListener('message', (event) => {
    const value = JSON.parse(event.data), call = pending.get(value.id)
    if (!call) return
    clearTimeout(call.timer); pending.delete(value.id)
    if (value.error) call.reject(new Error(JSON.stringify(value.error)))
    else call.resolve(value.result)
  })
  await new Promise((resolve, reject) => { socket.addEventListener('open', resolve, { once: true }); socket.addEventListener('error', reject, { once: true }) })
  await cdp('Page.enable')
  await cdp('Network.enable')
  await cdp('Network.setBlockedURLs', { urls: ['https://*'] })
  await cdp('Page.navigate', { url: `${origin}/__display.html` })
  await until('!!document.querySelector("#customer .tool-actions button")')
  const customerText = () => evaluate('document.querySelector("#customer").textContent')
  assert.doesNotMatch(await customerText(), /current_QA_review|original_record_sha256|homecare\/|use_policy/)
  assert.match(await customerText(), /배수 불량/)
  assert.match(await evaluate('document.querySelector("#dev").textContent'), /current_QA_review/)
  await evaluate('document.querySelector("#customer .tool-actions button").click()')
  await until('window.calls.length === 1 && document.querySelector("#customer").textContent.includes("해결됐나요")')
  assert.deepEqual(await evaluate('window.calls[0]'), { kind: 'precondition', payload: { question_id: plan.preconditions[0].id, plan_scope: 'SAVED_SCOPE', answer: 'YES', context_version: 7 } })
  await evaluate('Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent.includes("다음 안내")).click()')
  await until('window.calls.length === 2 && document.querySelector("#customer").textContent.includes("요약 복사")')
  assert.deepEqual(await evaluate('window.calls[1]'), { kind: 'step', payload: { step_code: plan.steps[0].step_code, answer: 'NO', context_version: 7 } })
  for (const value of ['124,569원', '230,145원', '169,319원', '조건 충족 시 무상', '실제 접수는 공식 채널']) assert.ok((await customerText()).includes(value))
  await evaluate('Object.defineProperty(navigator, "clipboard", {configurable: true, value: {writeText: async text => {window.copied = text}}})')
  await evaluate('Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent === "요약 복사").click()')
  await until('document.querySelector("#customer").textContent.includes("요약을 복사했습니다")')
  assert.equal(await evaluate('window.copied'), '모델: TR15WV5\n증상: UE\n자가조치: 미해결')
  await evaluate('navigator.clipboard.writeText = async () => {throw new Error("permission denied")}')
  await evaluate('Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent === "요약 복사").click()')
  await until('document.querySelector("#customer").textContent.includes("선택해 복사")')
  for (const width of [1280, 390]) {
    await cdp('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 500 })
    await evaluate('document.querySelectorAll("#customer details").forEach(item => {item.open = true})')
    const dimensions = await evaluate('({width: innerWidth, content: document.querySelector("#customer").scrollWidth})')
    assert.ok(dimensions.content <= dimensions.width, JSON.stringify(dimensions))
    const capture = await cdp('Page.captureScreenshot', { format: 'png' })
    await writeFile(join(profile, `display-${width}.png`), Buffer.from(capture.data, 'base64'))
    report.push({ width, overflow: false, screenshot: join(profile, `display-${width}.png`) })
  }
  await evaluate('window.setPhase(3)')
  await until('document.querySelector("#customer").textContent.includes("어떤 증상인가요")')
  await evaluate('Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent === "흔들림").click()')
  assert.deepEqual(await evaluate('window.calls.at(-1)'), { kind: 'symptom', option: '흔들림' })
  await evaluate('window.setPhase(4)')
  await until('document.querySelector("#customer").textContent.includes("위험 상황")')
  await evaluate('Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent === "모르겠어요").click()')
  assert.deepEqual(await evaluate('window.calls.at(-1)'), { kind: 'risk', answer: 'UNSURE', version: 7 })
  // Self-check steps and observation questions advance in the browser; only the final answer is sent.
  await evaluate('window.setPhase(7)')
  await until('document.querySelector("#customer").textContent.includes("1/3단계")')
  const sentBefore = await evaluate('window.calls.length')
  for (const shown of ['2/3단계', '3/3단계']) {
    await evaluate('Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent === "아니요, 다음 단계").click()')
    await until(`document.querySelector("#customer").textContent.includes("${shown}")`)
  }
  assert.equal(await evaluate('window.calls.length'), sentBefore)
  assert.equal(await evaluate('document.querySelectorAll("#customer .self-check-steps li.checked").length'), 2)
  const click = (label) => evaluate(`Array.from(document.querySelectorAll("#customer button")).find(button => button.textContent === "${label}").click()`)
  await click('해결됐어요')
  assert.deepEqual(await evaluate('window.calls.at(-1)'), { kind: 'selfCheck', result: 'SOLVED', step: 3, version: 7, label: '3단계에서 해결됐어요' })
  await click('아니요, 해결되지 않았어요')
  await until('document.querySelector("#customer").textContent.includes("원인을 좁히는 질문 · 1/2")')
  assert.equal(await evaluate('window.calls.length'), sentBefore + 1)
  await click('소리가 안 나요')
  await until('document.querySelector("#customer").textContent.includes("원인을 좁히는 질문 · 2/2")')
  assert.equal(await evaluate('window.calls.length'), sentBefore + 1)
  await click('안쪽 벽에 두껍게 있어요')
  assert.deepEqual(await evaluate('window.calls.at(-1)'), { kind: 'selfCheck', result: 'NOT_RESOLVED', step: 3, version: 7, label: '3단계까지 모두 확인했지만 해결되지 않았어요', observations: { SOUND: 'NO', FROST: 'BACK_WALL' } })
  assert.equal(await evaluate('window.calls.length'), sentBefore + 2)
  // Without steps the questions come first and the outcome carries step 0.
  await evaluate('window.setPhase(8)')
  await until('document.querySelector("#customer").textContent.includes("원인을 좁히는 질문 · 1/2")')
  await click('소리가 나요')
  await until('document.querySelector("#customer").textContent.includes("원인을 좁히는 질문 · 2/2")')
  await click('없어요')
  assert.deepEqual(await evaluate('window.calls.at(-1)'), { kind: 'selfCheck', result: 'NOT_RESOLVED', step: 0, version: 7, label: '확인 질문에 답했어요', observations: { SOUND: 'YES', FROST: 'NONE' } })
  assert.equal(await evaluate('window.calls.length'), sentBefore + 3)
  if (warrantyVerification) {
    for (const phase of [5, 6]) {
      await evaluate(`window.setPhase(${phase})`)
      await until('!!document.querySelector("#customer .warranty-result .warranty-row")')
      const product = await evaluate('document.querySelector("#customer .warranty-result .warranty-row").textContent')
      assert.match(product, /보증기간 안/)
      assert.match(product, /2027-05-05/)
      assert.equal(await evaluate('document.querySelector("#customer .warranty-result .warranty-status").textContent'), '보증기간 안')
      assert.match(await evaluate('document.querySelector("#customer .warranty-result").textContent'), /구매일 2026-05-05/)
      assert.doesNotMatch(product, /조건 충족 시 무상|0원/)
      purchaseWarrantyChecks.push({ source: phase === 5 ? 'HTTP chat response' : 'saved history response', productText: product })
    }
    for (const width of [1280, 390]) {
      await cdp('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: width < 500 })
      await evaluate('document.querySelector("#customer .warranty-result").scrollIntoView({block: "start"})')
      const dimensions = await evaluate('({width: innerWidth, content: document.querySelector("#customer").scrollWidth})')
      assert.ok(dimensions.content <= dimensions.width, JSON.stringify(dimensions))
      const capture = await cdp('Page.captureScreenshot', { format: 'png' })
      const screenshot = join(profile, `purchase-warranty-${width}.png`)
      await writeFile(screenshot, Buffer.from(capture.data, 'base64'))
      report.push({ width, purchaseWarranty: true, overflow: false, screenshot })
    }
  }
  console.log(JSON.stringify({ passed: true, scope: warrantyVerification ? 'local fixtures plus captured authenticated backend/MCP and history responses; no real model' : 'local component fixtures; no backend or real model', checks: ['stored JSON', 'DEV originals', 'precondition payload', 'step payload', 'cost/warranty/service', 'clipboard success/failure', 'symptom answer', 'risk answer', 'self-check local steps and questions'], purchaseWarrantyChecks, viewports: report }, null, 2))
} finally {
  socket?.close()
  for (const call of pending.values()) clearTimeout(call.timer)
  browser?.kill()
  await server?.close()
  // Keep screenshots outside the workspace for visual review; remove only the
  // debugging-port file. The isolated profile never touches the user's browser.
  await rm(join(profile, 'DevToolsActivePort'), { force: true }).catch(() => {})
}
