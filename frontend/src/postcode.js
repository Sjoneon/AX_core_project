let scriptPromise

export function loadPostcode() {
  if (window.kakao?.Postcode || window.daum?.Postcode) return Promise.resolve()
  if (!scriptPromise) {
    scriptPromise = new Promise((resolve, reject) => {
      const script = document.createElement('script')
      script.src = 'https://t1.kakaocdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js'
      script.async = true
      const timer = window.setTimeout(fail, 15_000)
      function fail() {
        window.clearTimeout(timer)
        script.remove()
        scriptPromise = undefined
        reject(new Error('주소 검색을 불러오지 못했습니다. 주소를 직접 입력해 주세요.'))
      }
      script.onerror = fail
      script.onload = () => {
        if (!window.kakao?.Postcode && !window.daum?.Postcode) return fail()
        window.clearTimeout(timer)
        resolve()
      }
      document.head.append(script)
    })
  }
  return scriptPromise
}
