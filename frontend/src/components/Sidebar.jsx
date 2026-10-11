import { useState } from 'react'
import Icon from './Icon.jsx'
import { productName } from '../data/products.js'

function dateGroup(date) {
  const now = new Date()
  const target = new Date(date)
  if (now.toDateString() === target.toDateString()) return '오늘'
  now.setDate(now.getDate() - 1)
  return now.toDateString() === target.toDateString() ? '어제' : '이전 상담'
}

export default function Sidebar({ conversations, activeId, onSelect, onNew, onDelete, onClose, onRetry, isBusy, isLoading, error }) {
  const [search, setSearch] = useState('')
  const visible = conversations.filter((item) =>
    `${productName(item.category)} ${item.title}`.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
  )

  return (
    <aside className="sidebar" aria-label="내 상담 내역">
      <div className="sidebar-top">
        <span className="eyebrow">MY HOMECARE</span>
        {onClose && <button className="icon-button" type="button" onClick={onClose} aria-label="상담 내역 닫기"><Icon name="close" /></button>}
      </div>
      <button className="new-chat" type="button" onClick={onNew} disabled={isBusy}><Icon name="plus" size={20} />새 상담 시작</button>
      <label className="history-search">
        <Icon name="search" size={18} />
        <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="상담 내역 검색" aria-label="상담 내역 검색" />
      </label>
      <div className="history-heading"><h2>내 상담 내역</h2><span>{conversations.length}</span></div>
      <div className="history-list" aria-busy={isLoading}>
        {error && <div className="history-error"><p role="alert">{error}</p><button className="text-button" type="button" onClick={onRetry} disabled={isBusy}>다시 불러오기</button></div>}
        {isLoading && <p className="history-notice" role="status">상담을 불러오는 중…</p>}
        {!isLoading && !error && visible.length === 0 && (
          <div className="history-empty"><Icon name="chat" size={28} /><p>{search ? '검색 결과가 없어요.' : '아직 상담 내역이 없어요.'}</p><small>{search ? '제목이나 제품 카테고리로 검색해 보세요.' : '첫 질문을 보내면 상담이 자동으로 저장돼요.'}</small></div>
        )}
        {['오늘', '어제', '이전 상담'].map((group) => {
          const items = visible.filter((item) => dateGroup(item.updatedAt) === group)
          return items.length > 0 && (
            <section className="history-group" key={group}>
              <h3>{group}</h3>
              {items.map((item) => (
                <div className="history-row" key={item.id}>
                  <button className={`history-item ${activeId === item.id ? 'is-active' : ''}`} type="button" onClick={() => onSelect(item.id)} disabled={isBusy} aria-current={activeId === item.id ? 'true' : undefined}>
                    <span className="history-product"><Icon name={item.category} size={17} />{productName(item.category)}<time dateTime={item.updatedAt}>{new Date(item.updatedAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', hour12: false })}</time></span>
                    <strong>{item.title}</strong>
                    <span className="history-date">{new Date(item.updatedAt).toLocaleDateString('ko-KR')} · 이어서 질문하기</span>
                  </button>
                  <button className="delete-chat" type="button" onClick={() => onDelete(item)} disabled={isBusy} aria-label={`${productName(item.category)} 상담 삭제: ${item.title}`} title="상담 삭제"><Icon name="trash" size={16} /></button>
                </div>
              ))}
            </section>
          )
        })}
      </div>
      <div className="sidebar-bottom"><Icon name="shield" size={18} /><p>상담 내역은 내 계정에 저장돼요.<br /><span>다시 로그인해도 확인할 수 있어요.</span></p></div>
    </aside>
  )
}
