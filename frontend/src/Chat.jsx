import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuthenticatedRequest } from './api.js'
import { readChatStream } from './chatStream.js'
import Brand from './components/Brand.jsx'
import Icon from './components/Icon.jsx'
import Sidebar from './components/Sidebar.jsx'
import { PRODUCTS, productName } from './data/products.js'

export default function Chat({ session, onLogout, isActive, onMyPage }) {
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
  const [deleteTarget, setDeleteTarget] = useState(null)
  const [isDeleting, setIsDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState('')
  const conversationRef = useRef(null)
  const inputRef = useRef(null)
  const historyDialogRef = useRef(null)
  const deleteDialogRef = useRef(null)
  const sendingRef = useRef(false)
  const streamAbortRef = useRef(null)
  const stickToBottomRef = useRef(true)
  const isBusy = isSending || isLoading || isDeleting
  const started = messages.length > 0
  const retired = category === 'AIR_CONDITIONER'
  const selectedProduct = PRODUCTS.find((product) => product.id === category) || PRODUCTS[0]

  const request = useAuthenticatedRequest(session, onLogout)

  useEffect(() => () => streamAbortRef.current?.abort(), [])

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
    if (!isActive) return
    const conversation = conversationRef.current
    if (!isBusy && !mobileOpen && !deleteTarget && !retired) inputRef.current.focus({ preventScroll: true })
    if (stickToBottomRef.current) conversation.scrollTop = messages.length ? conversation.scrollHeight : 0
  }, [messages, isBusy, mobileOpen, deleteTarget, isActive, retired])

  useEffect(() => {
    if (mobileOpen) historyDialogRef.current.showModal()
    else historyDialogRef.current.close()
  }, [mobileOpen])

  useEffect(() => {
    if (deleteTarget) deleteDialogRef.current.showModal()
    else deleteDialogRef.current.close()
  }, [deleteTarget])

  async function openConversation(id) {
    if (isBusy) return
    setIsLoading(true)
    setError('')
    try {
      const data = await request(`/api/conversations/${id}`)
      stickToBottomRef.current = true
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
    if (!message || isBusy || retired || sendingRef.current || (!started && !category)) return

    sendingRef.current = true
    setError('')
    setIsSending(true)
    setDraft('')
    stickToBottomRef.current = true
    const userId = crypto.randomUUID()
    const assistantId = crypto.randomUUID()
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(120_000)])
    streamAbortRef.current = controller
    let partialReply = ''
    setMessages((previous) => [...previous, { id: userId, role: 'user', content: message }])

    try {
      const response = await request('/api/chat/stream', {
        method: 'POST',
        rawResponse: true,
        headers: { Accept: 'text/event-stream' },
        body: JSON.stringify({ message, conversationId, category: category || null }),
        signal,
      })
      const data = await readChatStream(response, (chunk) => {
        if (controller.signal.aborted || !chunk) return
        partialReply += chunk
        const content = partialReply
        setMessages((previous) => previous.some((item) => item.id === assistantId)
          ? previous.map((item) => item.id === assistantId ? { ...item, content } : item)
          : [...previous, { id: assistantId, role: 'assistant', content }])
      })
      if (controller.signal.aborted) return
      setMessages((previous) => [
        ...previous.filter((item) => item.id !== assistantId),
        { id: assistantId, role: 'assistant', content: data.reply, sources: data.sources || [] },
      ])
      setConversationId(data.conversationId)
      await loadHistory()
    } catch (exception) {
      if (controller.signal.aborted) return
      setMessages((previous) => previous.filter((item) => item.id !== userId && item.id !== assistantId))
      setDraft(message)
      setError(
        exception.name === 'TimeoutError' || signal.reason?.name === 'TimeoutError'
          ? '응답 시간이 초과되었습니다. 다시 시도해 주세요.'
          : exception instanceof TypeError
            ? '서버에 연결하지 못했습니다. 서버 실행 상태를 확인해 주세요.'
            : exception.message,
      )
      await loadHistory()
    } finally {
      streamAbortRef.current = null
      sendingRef.current = false
      if (!controller.signal.aborted) setIsSending(false)
    }
  }

  function handleKeyDown(event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing && event.nativeEvent.keyCode !== 229) {
      sendMessage(event)
    }
  }

  function startConversation() {
    if (isBusy) return
    resetConversation()
  }

  function resetConversation() {
    stickToBottomRef.current = true
    setConversationId(null)
    setCategory('')
    setMessages([])
    setDraft('')
    setError('')
    setMobileOpen(false)
  }

  function beginDelete(item) {
    if (isBusy) return
    setDeleteError('')
    setDeleteTarget(item)
  }

  function cancelDelete() {
    if (isDeleting) return
    setDeleteTarget(null)
    setDeleteError('')
  }

  async function deleteConversation(event) {
    event.preventDefault()
    if (isDeleting || !deleteTarget) return
    const id = deleteTarget.id
    setIsDeleting(true)
    setDeleteError('')
    try {
      await request(`/api/conversations/${id}`, { method: 'DELETE', signal: AbortSignal.timeout(10_000) })
      setConversations((previous) => previous.filter((item) => item.id !== id))
      if (conversationId === id) resetConversation()
      setDeleteTarget(null)
    } catch (exception) {
      setDeleteError(
        exception.name === 'TimeoutError'
          ? '삭제 요청 시간이 초과되었습니다. 다시 시도해 주세요.'
          : exception instanceof TypeError
            ? '서버에 연결하지 못했습니다. 다시 시도해 주세요.'
            : exception.message,
      )
    } finally {
      setIsDeleting(false)
    }
  }

  const sidebarProps = {
    conversations, activeId: conversationId, onSelect: openConversation, onNew: startConversation,
    onRetry: retryHistory, onDelete: beginDelete, isBusy, isLoading, error: historyError,
  }

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main">상담 화면으로 이동</a>
      <header className="header">
        <button className="icon-button mobile-menu" type="button" onClick={() => setMobileOpen(true)} aria-label="상담 내역 열기"><Icon name="menu" /></button>
        <button className="brand-home" type="button" onClick={startConversation} disabled={isBusy} aria-label="HomeCare AI 초기 화면으로 이동"><Brand /></button>
        <span className="header-description">가전의 불편함, 함께 해결해요.</span>
        <div className="header-account">
          <span className="signed-in-email" title={session.email}>{session.email}</span>
          <button className="logout-button" type="button" onClick={onMyPage} disabled={isBusy || mobileOpen || Boolean(deleteTarget)}>마이페이지</button>
          <button className="logout-button" type="button" onClick={() => onLogout()} disabled={isBusy}>로그아웃</button>
        </div>
      </header>
      <div className="workspace">
        <div className="desktop-sidebar"><Sidebar {...sidebarProps} /></div>
        <main className="chat-main" id="main">
          <div className="chat-toolbar">
            <div><span className="toolbar-icon"><Icon name={started ? category : 'chat'} size={21} /></span><h1>{started ? `${productName(category)} 상담` : '가전 상담'}</h1><span className="toolbar-label">{isLoading ? '불러오는 중' : isSending ? '답변 작성 중' : retired ? '기록 조회' : started ? '상담 중' : '새 상담'}</span></div>
            <span className="toolbar-help">{retired ? '기존 기록을 조회할 수 있어요' : '모델명 없이 카테고리로 시작해요'}</span>
          </div>
          <div className="chat-scroll" ref={conversationRef} aria-busy={isBusy} onScroll={(event) => {
            const { scrollHeight, scrollTop, clientHeight } = event.currentTarget
            stickToBottomRef.current = scrollHeight - scrollTop - clientHeight < 80
          }}>
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
                    <div className="message-content">
                      <span className="message-author">{message.role === 'user' ? '나' : 'HomeCare AI'}</span>
                      <p className="message-bubble">{message.content}</p>
                      {message.role === 'assistant' && message.sources?.length > 0 && (
                        <div className="message-sources" aria-label="답변 생성에 전달한 검색 근거">
                          <p>검색 근거 · 가상 테스트 매뉴얼</p>
                          {message.sources.map((source) => (
                            <details key={source.documentId}>
                              <summary>{source.documentId} · {source.title}</summary>
                              <small>{productName(source.category)} · 버전 {source.version} · {source.file}</small>
                              <p className="source-content">{source.content}</p>
                            </details>
                          ))}
                        </div>
                      )}
                    </div>
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
              <textarea id="chat-message" ref={inputRef} value={draft} onChange={(event) => setDraft(event.target.value)} onKeyDown={handleKeyDown} placeholder={retired ? '에어컨 상담은 지원 종료되었습니다. 기존 기록만 조회할 수 있어요.' : started ? '궁금한 점을 이어서 입력해 주세요.' : category ? `예: ${selectedProduct.example}` : '어떤 증상인지 편하게 적어주세요.'} maxLength={4000} readOnly={isBusy || retired} disabled={retired} required rows={2} aria-describedby="composer-hint" />
              <div className="composer-controls"><span className="character-count">{draft.length.toLocaleString()} / 4,000</span><button className="send-button" type="submit" disabled={isBusy || retired || !draft.trim() || (!started && !category)}>{retired ? '지원 종료' : isSending ? '답변 받는 중' : started ? '보내기' : '상담 시작'}<Icon name="send" size={19} /></button></div>
            </form>
            <div className="composer-help" id="composer-hint"><span>{retired ? '새 상담 시작을 눌러 지원하는 품목을 선택해 주세요.' : !started && !category ? '먼저 위에서 제품을 선택해 주세요.' : 'Enter 전송 · Shift + Enter 줄바꿈'}</span><span>가상 매뉴얼 기반 테스트 상담입니다.</span></div>
          </div>
        </main>
      </div>
      <dialog className="history-dialog" ref={historyDialogRef} aria-label="상담 내역" onCancel={() => setMobileOpen(false)} onClick={(event) => { if (event.target === event.currentTarget) setMobileOpen(false) }}>
        <Sidebar {...sidebarProps} onClose={() => setMobileOpen(false)} />
      </dialog>
      <dialog className="delete-dialog" ref={deleteDialogRef} aria-labelledby="delete-title" aria-describedby="delete-description" onCancel={(event) => { event.preventDefault(); cancelDelete() }}>
        <form onSubmit={deleteConversation}>
          <h2 id="delete-title">상담 내역을 삭제할까요?</h2>
          <p className="delete-target">{deleteTarget?.title}</p>
          <p id="delete-description">이 상담의 질문과 답변이 모두 삭제됩니다. 삭제 후에는 복구할 수 없습니다.</p>
          {deleteError && <p className="error" role="alert">{deleteError}</p>}
          <div className="delete-actions">
            <button className="logout-button" type="button" onClick={cancelDelete} disabled={isDeleting} autoFocus>취소</button>
            <button className="send-button" type="submit" disabled={isDeleting}>{isDeleting ? '삭제 중…' : '삭제'}</button>
          </div>
        </form>
      </dialog>
    </div>
  )
}
