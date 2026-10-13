export const PRODUCTS = [
  { id: 'REFRIGERATOR', name: '냉장고', hint: '김치냉장고 포함', example: '냉장고가 시원하지 않아요.' },
  { id: 'WASHER', name: '세탁기', hint: '세탁 · 탈수 · 배수', example: '탈수할 때 소리가 크고 많이 흔들려요.' },
  { id: 'DRYER', name: '건조기', hint: '건조 · 소음 · 작동', example: '건조가 끝나도 옷이 축축해요.' },
  { id: 'DISHWASHER', name: '식기세척기', hint: '세척 · 배수 · 건조', example: '세척이 끝나도 그릇에 음식물이 남아요.' },
]

export const productName = (category) => category === 'AIR_CONDITIONER'
  ? '에어컨 · 지원 종료'
  : PRODUCTS.find((product) => product.id === category)?.name || '일반'
