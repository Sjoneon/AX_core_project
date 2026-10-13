import { useEffect, useRef, useState } from 'react'
import { useAuthenticatedRequest } from './api.js'
import { loadPostcode } from './postcode.js'
import { productName } from './data/products.js'
import Brand from './components/Brand.jsx'
import Icon from './components/Icon.jsx'
import ProductDialog from './ProductDialog.jsx'
import './MyPage.css'

export default function MyPage({ session, onLogout, onBack }) {
  const [products, setProducts] = useState([])
  const [isLoading, setIsLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [editor, setEditor] = useState(null)
  const [isSaving, setIsSaving] = useState(false)
  const [saveError, setSaveError] = useState('')
  const [notice, setNotice] = useState('')
  const [postcodeStatus, setPostcodeStatus] = useState('loading')
  const savingRef = useRef(false)
  const request = useAuthenticatedRequest(session, onLogout)

  useEffect(() => {
    const controller = new AbortController()
    const signal = AbortSignal.any([controller.signal, AbortSignal.timeout(10_000)])
    request('/api/products', { signal })
      .then((data) => { if (!signal.aborted) setProducts(data) })
      .catch(() => { if (!controller.signal.aborted) setLoadError('제품을 불러오지 못했습니다. 다시 시도해 주세요.') })
      .finally(() => { if (!controller.signal.aborted) setIsLoading(false) })
    return () => controller.abort()
  }, [request, attempt])

  useEffect(() => {
    let active = true
    loadPostcode().then(() => { if (active) setPostcodeStatus('ready') })
      .catch(() => { if (active) setPostcodeStatus('error') })
    return () => { active = false }
  }, [])

  function retry() {
    setIsLoading(true)
    setLoadError('')
    setAttempt((previous) => previous + 1)
  }

  function edit(item) {
    setSaveError('')
    setNotice('')
    setEditor({ item })
  }

  function closeEditor() {
    if (!savingRef.current) setEditor(null)
  }

  async function save(body) {
    if (savingRef.current) return
    savingRef.current = true
    setIsSaving(true)
    setSaveError('')
    const item = editor.item
    try {
      const data = await request(item ? `/api/products/${item.id}` : '/api/products', {
        method: item ? 'PUT' : 'POST', body: JSON.stringify(body), signal: AbortSignal.timeout(15_000),
      })
      setProducts((previous) => item ? previous.map((product) => product.id === data.id ? data : product) : [data, ...previous])
      setNotice(item ? '제품 정보를 수정했어요.' : '제품을 등록했어요.')
      setEditor(null)
    } catch (exception) {
      setSaveError(exception.name === 'TimeoutError' ? '저장 요청 시간이 초과되었습니다. 입력한 정보를 확인한 뒤 다시 시도해 주세요.'
        : exception instanceof TypeError ? '서버에 연결하지 못했습니다. 다시 시도해 주세요.' : exception.message)
    } finally {
      savingRef.current = false
      setIsSaving(false)
    }
  }

  return (
    <div className="mypage-shell">
      <a className="skip-link" href="#mypage-main">내 제품 목록으로 이동</a>
      <header className="header">
        <button className="brand-home" type="button" onClick={onBack} disabled={isSaving} aria-label="상담 화면으로 돌아가기"><Brand /></button>
        <div className="header-account">
          <span className="signed-in-email" title={session.email}>{session.email}</span>
          <button className="logout-button" type="button" onClick={onBack} disabled={isSaving}>상담으로 돌아가기</button>
          <button className="logout-button" type="button" onClick={() => onLogout()} disabled={isSaving}>로그아웃</button>
        </div>
      </header>
      <main className="mypage-main" id="mypage-main">
        <div className="mypage-intro">
          <div><p className="eyebrow">MY HOMECARE</p><h1>마이페이지</h1><p className="mypage-email">{session.email}</p><p>우리 집 가전의 구매 정보와 설치 주소를 관리하세요.</p></div>
          {!isLoading && !loadError && <div className="product-count"><strong>{products.length}</strong><span>등록 제품</span></div>}
        </div>
        {notice && <p className="product-success" role="status">{notice}</p>}
        <section className="products-panel" aria-labelledby="my-products-title" aria-busy={isLoading}>
          <div className="products-heading"><div><h2 id="my-products-title">내 제품 관리</h2><p>모르는 정보는 비워두고, 필요할 때 추가해 주세요.</p></div>
            <button className="send-button" type="button" onClick={() => edit(null)} disabled={isLoading || Boolean(loadError)}><Icon name="plus" size={18} />제품 등록</button>
          </div>
          {isLoading ? <p className="products-empty" role="status">제품을 불러오는 중…</p>
            : loadError ? <div className="products-empty"><p className="error" role="alert">{loadError}</p><button className="text-button" type="button" onClick={retry}>다시 불러오기</button></div>
              : products.length === 0 ? <div className="products-empty"><Icon name="plus" size={28} /><h3>등록한 제품이 없습니다.</h3><p>제품 등록 버튼을 눌러 우리 집 가전을 추가해 주세요.</p></div>
                : <table className="my-products-table">
                  <caption className="sr-only">등록한 제품 목록</caption>
                  <thead><tr><th scope="col">품목</th><th scope="col">표시 이름</th><th scope="col">모델명</th><th scope="col">구매일</th><th scope="col">설치 주소</th><th scope="col">관리</th></tr></thead>
                  <tbody>{products.map((item) => <tr key={item.id}>
                    <td data-label="품목"><span className="my-product-category"><Icon name={item.category} size={20} />{productName(item.category)}</span></td>
                    <th scope="row" data-label="표시 이름">{item.name || productName(item.category)}</th>
                    <td data-label="모델명">{item.model || '미입력'}</td>
                    <td data-label="구매일">{item.purchaseDate || '미입력'}</td>
                    <td data-label="설치 주소"><div>{item.postcode && <small>{item.postcode}<br /></small>}{item.address || (!item.postcode ? '미입력' : '')}{item.addressDetail && <><br />{item.addressDetail}</>}</div></td>
                    <td className="my-product-edit"><button className="logout-button" type="button" aria-label={`${item.name || productName(item.category)} 수정`} onClick={() => edit(item)}>수정</button></td>
                  </tr>)}</tbody>
                </table>}
        </section>
      </main>
      {editor && <ProductDialog initial={editor.item} onSave={save} onClose={closeEditor} isSaving={isSaving} saveError={saveError} postcodeStatus={postcodeStatus} />}
    </div>
  )
}
