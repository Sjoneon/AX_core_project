import { useEffect, useRef, useState } from 'react'
import { PRODUCTS } from './data/products.js'
import Icon from './components/Icon.jsx'

function purchaseDate(value) {
  if (!value) return null
  if (!/^[0-9]{8}$/.test(value)) throw new Error('구매일을 실제 날짜 8자리로 입력해 주세요. 예: 20250315')
  const year = Number(value.slice(0, 4))
  const month = Number(value.slice(4, 6))
  const day = Number(value.slice(6, 8))
  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)
  const formatted = `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const part = (type) => parts.find((item) => item.type === type).value
  const today = `${part('year')}-${part('month')}-${part('day')}`
  if (year < 1 || date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day || formatted > today) {
    throw new Error('구매일은 오늘 이하의 실제 날짜여야 합니다.')
  }
  return formatted
}

export default function ProductDialog({ initial, onSave, onClose, isSaving, saveError, postcodeStatus }) {
  const [form, setForm] = useState({
    category: initial?.category || PRODUCTS[0].id,
    name: initial?.name || '', model: initial?.model || '',
    purchaseDate: (initial?.purchaseDate || '').replaceAll('-', ''),
    postcode: initial?.postcode || '', address: initial?.address || '', addressDetail: initial?.addressDetail || '',
  })
  const [error, setError] = useState('')
  const [addressError, setAddressError] = useState('')
  const dialogRef = useRef(null)
  const detailRef = useRef(null)
  const alive = useRef(false)
  const savingRef = useRef(isSaving)

  useEffect(() => { savingRef.current = isSaving }, [isSaving])
  useEffect(() => {
    const dialog = dialogRef.current
    const trigger = document.activeElement
    alive.current = true
    dialog.showModal()
    return () => {
      alive.current = false
      dialog.close()
      if (trigger instanceof HTMLElement && trigger.isConnected) trigger.focus()
    }
  }, [])

  function change(event) {
    const { name, value } = event.target
    setForm((previous) => ({ ...previous, [name]: name === 'purchaseDate' ? value.replace(/[^0-9]/g, '').slice(0, 8) : value }))
  }

  function searchAddress() {
    const Postcode = window.kakao?.Postcode || window.daum?.Postcode
    setAddressError('')
    try {
      new Postcode({
        oncomplete(data) {
          if (!alive.current || savingRef.current) return
          setForm((previous) => ({ ...previous, postcode: data.zonecode,
            address: data.userSelectedType === 'R' ? data.roadAddress : data.jibunAddress, addressDetail: '' }))
          detailRef.current?.focus()
        },
      }).open()
    } catch {
      setAddressError('주소 검색창을 열지 못했습니다. 주소를 직접 입력해 주세요.')
    }
  }

  function submit(event) {
    event.preventDefault()
    if (isSaving) return
    setError('')
    try {
      const body = Object.fromEntries(Object.entries(form).map(([key, value]) => [key, value.trim() || null]))
      body.purchaseDate = purchaseDate(form.purchaseDate)
      if (body.addressDetail && !body.address) throw new Error('상세 주소를 입력하려면 기본 주소가 필요합니다.')
      onSave(body)
    } catch (exception) {
      setError(exception.message)
    }
  }

  return (
    <dialog className="product-dialog" ref={dialogRef} aria-labelledby="product-dialog-title"
      onCancel={(event) => { event.preventDefault(); if (!isSaving) onClose() }}>
      <div className="product-dialog-heading">
        <h2 id="product-dialog-title">{initial ? '제품 정보 수정' : '제품 등록'}</h2>
        <button className="icon-button" type="button" onClick={onClose} disabled={isSaving} aria-label="제품 입력 닫기"><Icon name="close" /></button>
      </div>
      <p className="product-help">품목만 선택해도 등록할 수 있어요. 나머지 정보는 나중에 추가해 주세요.</p>
      <form className="product-form" onSubmit={submit} aria-busy={isSaving}>
        <fieldset disabled={isSaving}>
          <legend className="sr-only">제품 정보</legend>
          <div className="product-fields">
            <label>품목 <span className="required-label">필수</span><select name="category" value={form.category} onChange={change} required autoFocus>
              {PRODUCTS.map((product) => <option key={product.id} value={product.id}>{product.name}</option>)}
            </select></label>
            <label>표시 이름 <small>선택</small><input name="name" value={form.name} onChange={change} maxLength={40} placeholder="예: 주방 냉장고" /></label>
            <label>모델명 <small>선택</small><input name="model" value={form.model} onChange={change} maxLength={80} placeholder="모르면 비워두세요" /></label>
            <label>구매일 <small>선택</small><input name="purchaseDate" value={form.purchaseDate} onChange={change} inputMode="numeric" pattern="[0-9]{8}" maxLength={8} placeholder="예: 20250315" aria-describedby="purchase-date-help" />
              <small id="purchase-date-help" className="product-help">연도·월·일을 숫자 8자리로 입력해 주세요.</small></label>
          </div>
          <div className="address-heading"><h3>제품 설치 주소</h3><small>선택</small></div>
          <div className="postcode-row">
            <label>우편번호<input name="postcode" value={form.postcode} onChange={change} inputMode="numeric" pattern="[0-9]{5}" maxLength={5} autoComplete="postal-code" title="우편번호는 숫자 5자리로 입력해 주세요" /></label>
            <button className="logout-button" type="button" onClick={searchAddress} disabled={postcodeStatus !== 'ready'}>{postcodeStatus === 'loading' ? '검색 준비 중…' : '주소 검색'}</button>
          </div>
          <label>기본 주소<input name="address" value={form.address} onChange={change} maxLength={160} autoComplete="address-line1" /></label>
          <label>상세 주소<input ref={detailRef} name="addressDetail" value={form.addressDetail} onChange={change} maxLength={100} autoComplete="address-line2" placeholder="동·호수 등을 입력해 주세요" /></label>
        </fieldset>
        {postcodeStatus === 'error' && <p className="product-help" role="status">주소 검색을 불러오지 못했습니다. 주소를 직접 입력해 주세요.</p>}
        {addressError && <p className="error" role="alert">{addressError}</p>}
        {(error || saveError) && <p className="error" role="alert">{error || saveError}</p>}
        <div className="product-actions">
          <button className="logout-button" type="button" onClick={onClose} disabled={isSaving}>취소</button>
          <button className="send-button" type="submit" disabled={isSaving}>{isSaving ? '저장 중…' : initial ? '수정 완료' : '제품 등록'}</button>
        </div>
      </form>
    </dialog>
  )
}
