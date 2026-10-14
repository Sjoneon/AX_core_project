import test from 'node:test'
import assert from 'node:assert/strict'
import { readChatStream } from './chatStream.js'

const encoder = new TextEncoder()
const completed = { reply: '안녕 😀\n다음 줄', conversationId: 7, sources: [] }
const event = (name, data) => `event:${name}\ndata:${JSON.stringify(data)}\n\n`

function responseFor(chunks) {
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(chunk)
      controller.close()
    },
  }), { headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' } })
}

test('decodes fragmented Korean, emoji, CRLF, multiple frames and multiline JSON data', async () => {
  const body = ': keepalive\r\n\r\nevent:start\r\ndata:{}\r\n\r\n'
    + event('delta', { content: '안녕 😀' })
    + 'event:delta\ndata:{\ndata: "content": "\\n다음 줄"}\n\n'
    + event('done', completed)
  const bytes = encoder.encode(body)
  const deltas = []
  const result = await readChatStream(responseFor(Array.from(bytes, (byte) => new Uint8Array([byte]))), (delta) => deltas.push(delta))
  assert.deepEqual(result, completed)
  assert.equal(deltas.join(''), completed.reply)
})

test('delivers the first delta before the completion event arrives', async () => {
  let controller
  const response = new Response(new ReadableStream({ start(value) { controller = value } }), {
    headers: { 'Content-Type': 'text/event-stream' },
  })
  let firstDelta
  const first = new Promise((resolve) => { firstDelta = resolve })
  const result = readChatStream(response, firstDelta)
  controller.enqueue(encoder.encode(event('delta', { content: '안녕' })))
  assert.equal(await first, '안녕')
  controller.enqueue(encoder.encode(event('done', completed)))
  assert.deepEqual(await result, completed)
})

test('rejects a server error after a partial answer', async () => {
  const deltas = []
  const response = responseFor([encoder.encode(event('delta', { content: '미완성' }) + event('error', { message: '안전한 오류 안내' }))])
  await assert.rejects(readChatStream(response, (delta) => deltas.push(delta)), /안전한 오류 안내/)
  assert.deepEqual(deltas, ['미완성'])
})

test('requires a done event even when partial text was received', async () => {
  await assert.rejects(readChatStream(responseFor([encoder.encode(event('delta', { content: '미완성' }))]), () => {}), /답변 수신이 중단/)
})

test('rejects malformed events, empty replies and missing conversation IDs', async () => {
  for (const body of [
    'event:delta\ndata:{broken}\n\n',
    event('delta', { content: 42 }),
    event('done', { reply: ' ', conversationId: 7 }),
    event('done', { reply: '답변' }),
  ]) {
    await assert.rejects(readChatStream(responseFor([encoder.encode(body)]), () => {}))
  }
})

test('rejects JSON responses and propagates transport failures', async () => {
  await assert.rejects(readChatStream(new Response('{}'), () => {}), /스트리밍/)
  const response = new Response(new ReadableStream({ start(controller) { controller.error(new TypeError('연결 끊김')) } }), {
    headers: { 'Content-Type': 'text/event-stream' },
  })
  await assert.rejects(readChatStream(response, () => {}), /연결 끊김/)
})

test('cancels the reader on completion without waiting for the connection to close', async () => {
  let cancelled = false
  const response = new Response(new ReadableStream({
    start(controller) { controller.enqueue(encoder.encode(event('done', completed))) },
    cancel() { cancelled = true },
  }), { headers: { 'Content-Type': 'text/event-stream' } })
  await readChatStream(response, () => {})
  assert.equal(cancelled, true)
})
