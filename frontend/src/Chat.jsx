import { useCallback, useEffect, useRef, useState } from 'react'
import Brand from './components/Brand.jsx'
import Icon from './components/Icon.jsx'
import Sidebar from './components/Sidebar.jsx'
import { PRODUCTS, productName } from './data/products.js'

export default function Chat({ session, onLogout }) {
  const [messages, setMessages] = useState([])
  const [conversations, setConversations] = useState([])
  const [conversationId, setConversationId] = useState(null)
  const [category, setCategory] = useState('')
  const [isLoading, setIsLoading] = useState(true)
  const [historyError, setHistoryError] = useState('')
  const [draft, setDraft] = useState('')
  const [isSending, setIsSending] = useState(false)
  const [error, setError] = useState('')
  const [mobileOpen, setMobileOpen] = useState(false)
  const conversationRef = useRef(null)
  const inputRef = useRef(null)
  const historyDialogRef = useRef(null)
  const sendingRef = useRef(false)
  const isBusy = isSending || isLoading
  const started = messages.length > 0
  const selectedProduct = PRODUCTS.find((product) => product.id === category) || PRODUCTS[0]

  const request = useCallback(async (path, options = {}) => {
    const response = await fetch(path, {
      ...options,
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${session.accessToken}`,
      },
      signal: options.signal || AbortSignal.timeout(35_000),
    })
    const data = await response.json().catch(() => null)
    if (response.status === 401) {
      onLogout('로그인이 만료되었습니다. 다시 로그인해 주세요.')
      throw new Error('다시 로그인해 주세요.')
    }
    if (!response.ok) {
      throw new Error(data?.message || '요청을 처리하지 못했습니다. 서버 실행 상태를 확인해 주세요.')
    }
    return data
  }, [session.accessToken, onLogout])

  const loadHistory = useCallback(async (signal) => {
    try {
      const data = await request('/api/conversations', { signal: signal || AbortSignal.timeout(10_000) })
      if (!signal?.aborted) {
        setConversations(data)
        setHistoryError('')
      }
    } catch (exception) {
      if (exception.name !== 'AbortError') {
        setHistoryError('상담 내역을 불러오지 못했습니다. 다시 시도해 주세요.')
      }
    }
  }, [request])

  useEffect(() => {
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)])
    request('/api/conversations', { signal })
      .then((data) => {
        if (!signal.aborted) setConversations(data)
      })
      .catch(() => {
        if (!controller.signal.aborted) setHistoryError('상담 내역을 불러오지 못했습니다. 다시 시도해 주세요.')
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false)
      })
    return () => controller.abort()
  }, [request])

  useEffect(() => {
    const conversation = conversationRef.current
    if (!isBusy && !mobileOpen) inputRef.current.focus({ preventScroll: true })
    conversation.scrollTop = messages.length ? conversation.scrollHeight : 0
  }, [messages, isBusy, mobileOpen])

  useEffect(() => {
    if (mobileOpen) historyDialogRef.current.showModal()
    else historyDialogRef.current.close()
  }, [mobileOpen])

  async function openConversation(id) {
    if (isBusy) return
    setIsLoading(true)
    setError('')
    try {
      const data = await request(`/api/conversations/${id}`)
      setConversationId(data.id)
      setCategory(data.category || '')
      setMessages(data.messages)
      setDraft('')
      setMobileOpen(false)
    } catch (exception) {
      setError(exception instanceof TypeError ? '서버에 연결하지 못했습니다.' : exception.message)
    } finally {
      setIsLoading(false)
    }
  }

  async function retryHistory() {
    setIsLoading(true)
    await loadHistory()
    setIsLoading(false)
  }

  async function sendMessage(event) {
    event.preventDefault()
    const message = draft.trim()
    if (!message || isBusy || sendingRef.current || (!started && !category)) return

    sendingRef.current = true
    setError('')
    setIsSending(true)
    setDraft('')
    setMessages((previous) => [...previous, { id: crypto.randomUUID(), role: 'user', content: message }])

    try {
      const data = await request('/api/chat', {
        method: 'POST',
        body: JSON.stringify({ message, conversationId, category: category || null }),
      })
      if (typeof data?.reply !== 'string' || !data.reply.trim()) {
        throw new Error('답변이 비어 있습니다. 다시 시도해 주세요.')
      }
      setMessages((previous) => [...previous, { id: crypto.randomUUID(), role: 'assistant', content: data.reply }])
      setConversationId(data.conversationId)
      await loadHistory()
    } catch (exception) {
      setMessages((previous) => previous.slice(0, -1))
      setDraft(message)
      setError(
        exception.name === 'TimeoutError'
          ? '응답 시간이 초과되었습니다. 다시 시도해 주세요.'
          : exception instanceof TypeError
            ? '서버에 연결하지 못했습니다. 서버 실행 상태를 확인해 주세요.'
            : exception.message,
      )
    } finally {
      sendingRef.current = false
      setIsSending(false)
    }
  }

  function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
      sendMessage(event)
    }
  }

  function startConversation() {
    if (isBusy) return
    setConversationId(null)
    setCategory('')
    setMessages([])
    setDraft('')
    setError('')
    setMobileOpen(false)
  }

  const sidebarProps = {
    conversations, activeId: conversationId, onSelect: openConversation, onNew: startConversation,
    onRetry: retryHistory, isBusy, isLoading, error: historyError,
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">상담 화면으로 이동</a>
      <header className="header">
        <button className="icon-button mobile-menu" type="button" onClick={() => setMobileOpen(true)} aria-label="상담 내역 열기"><Icon name="menu" /></button>
        <Brand />
        <span className="header-description">가전의 불편함, 함께 해결해요.</span>
        <div className="header-account">
          <span className="signed-in-email" title={session.email}>{session.email}</span>
          <button className="logout-button" type="button" onClick={() => onLogout()} disabled={isBusy}>로그아웃</button>
        </div>
      </header>
      <div className="workspace">
        <div className="desktop-sidebar"><Sidebar {...sidebarProps} /></div>
        <main className="chat-main" id="main">
          <div className="chat-toolbar">
            <div><span className="toolbar-icon"><Icon name={started ? category : 'chat'} size={21} /></span><h1>{started ? `${productName(category)} 상담` : '가전 상담'}</h1><span className="toolbar-label">{isLoading ? '불러오는 중' : isSending ? '답변 작성 중' : started ? '상담 중' : '새 상담'}</span></div>
            <span className="toolbar-help">모델명 없이 카테고리로 시작해요</span>
          </div>
          <div className="chat-scroll" ref={conversationRef} aria-busy={isBusy}>
            {!started ? (
              <div className="welcome">
                <div className="welcome-mark"><Icon name="spark" size={32} /></div>
                <p className="welcome-eyebrow">우리 집 가전을 위한 상담</p>
                <h2>어떤 가전이 불편하신가요?</h2>
                <p className="welcome-description">제품 카테고리를 고르고, <br />궁금한 점이나 발생한 증상을 알려주세요.</p>
                <div className="product-section">
                  <div className="section-label"><span><b>01</b>제품을 선택해 주세요</span><span>{PRODUCTS.length}개 카테고리</span></div>
                  <div className="product-grid">
                    {PRODUCTS.map((product) => (
                      <button className={`product-button ${category === product.id ? 'selected' : ''}`} type="button" key={product.id} aria-pressed={category === product.id} disabled={isBusy} onClick={() => setCategory(product.id)}>
                        <span className="product-illustration"><Icon name={product.id} size={34} /></span>
                        <strong>{product.name}</strong><small>{product.hint}</small>
                        {category === product.id && <span className="product-check"><Icon name="check" size={13} /></span>}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="quick-question"><span>이렇게 물어보세요</span><button type="button" disabled={isBusy} onClick={() => { setCategory(selectedProduct.id); setDraft(selectedProduct.example); inputRef.current.focus() }}>{selectedProduct.example}<Icon name="chevron" size={16} /></button></div>
              </div>
            ) : (
              <div className="messages" role="log" aria-label="상담 대화">
                <p className="conversation-date">{messages[0].createdAt ? new Date(messages[0].createdAt).toLocaleDateString('ko-KR', { month: 'long', day: 'numeric' }) : '새로운 상담'}</p>
                {messages.map((message) => (
                  <article className={`message ${message.role}`} key={message.id}>
                    {message.role === 'assistant' && <span className="assistant-avatar"><Icon name="spark" size={19} /></span>}
                    <div className="message-content"><span className="message-author">{message.role === 'user' ? '나' : 'HomeCare AI'}</span><p className="message-bubble">{message.content}</p></div>
                  </article>
                ))}
                {isSending && <p className="loading" role="status">HomeCare AI가 답변을 작성하고 있어요…</p>}
              </div>
            )}
          </div>
          <div className="composer-area">
            {error && <p className="error" role="alert">{error}</p>}
            <form className="chat-form" onSubmit={sendMessage}>
              <label className="sr-only" htmlFor="chat-message">제품 증상 또는 질문</label>
              <textarea id="chat-message" ref={inputRef} value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={handleKeyDown} placeholder={started ? '궁금한 점을 이어서 입력해 주세요.' : category ? `예: ${selectedProduct.example}` : '어떤 증상인지 편하게 적어주세요.'} maxLength={4000} readOnly={isBusy} required rows={2} aria-describedby="composer-hint" />
              <div className="composer-controls"><span className="character-count">{draft.length.toLocaleString()} / 4,000</span><button className="send-button" type="submit" disabled={isBusy || !draft.trim() || (!started && !category)}>{isSending ? '응답 기다리는 중' : started ? '보내기' : '상담 시작'}<Icon name="send" size={19} /></button></div>
            </form>
            <div className="composer-help" id="composer-hint"><span>{!started && !category ? '먼저 위에서 제품을 선택해 주세요.' : 'Enter 전송 · Shift + Enter 줄바꿈'}</span><span>AI는 각 질문에 독립적으로 답변해요.</span></div>
          </div>
        </main>
      </div>
      <dialog className="history-dialog" ref={historyDialogRef} aria-label="상담 내역" onCancel={() => setMobileOpen(false)} onClick={(event) => { if (event.target === event.currentTarget) setMobileOpen(false) }}>
        <Sidebar {...sidebarProps} onClose={() => setMobileOpen(false)} />
      </dialog>
    </div>
  )
}
