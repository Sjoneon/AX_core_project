import HelpPopover from './HelpPopover.jsx'

export default function ModelNameHelp({ category }) {
  return (
    <HelpPopover term="모델명">
      <p>제품 라벨에서 <strong>모델명 / Model</strong>이라고 적힌 항목을 확인해 주세요.</p>
      <section>
        <h4>라벨은 어디에 있나요?</h4>
        {(category === 'WASHER' || category === 'DRYER') && <>
          <p><strong>드럼 세탁기·건조기:</strong> 전면 에너지소비효율 라벨이나 문을 열었을 때 보이는 드럼 입구 주변 라벨을 확인해 주세요.</p>
          {category === 'WASHER' && <p><strong>통돌이 세탁기:</strong> 전면 에너지소비효율 라벨이나 본체 왼쪽 윗부분 라벨을 확인해 주세요. 일부 제품은 뚜껑을 열면 오른쪽에 ThinQ 라벨이 있습니다.</p>}
        </>}
        {category === 'REFRIGERATOR' && <>
          <p><strong>냉장고:</strong> 전면 에너지소비효율 라벨 또는 문 안쪽 벽면의 제품 정보 라벨을 확인해 주세요.</p>
          <p><strong>김치냉장고:</strong> 스탠드형은 문 안쪽 벽면, 뚜껑형은 뚜껑을 연 안쪽 벽면의 라벨을 확인해 주세요.</p>
        </>}
        {category === 'DISHWASHER' && <p><strong>식기세척기:</strong> 문을 열고 문 안쪽에 부착된 제품 정보 라벨을 확인해 주세요.</p>}
        <p className="hc-help-note">LG 제품의 일부 모델 기준입니다. 제조사와 모델에 따라 라벨 위치가 다를 수 있습니다.</p>
      </section>
      <section>
        <h4>라벨이 잘 보이지 않나요?</h4>
        <ul>
          <li>사용설명서 표지나 제품 정보 페이지를 확인해 주세요.</li>
          <li>LG ThinQ에 등록한 제품이 있다면 <strong>메뉴 → 제품 정보와 보증 → 해당 제품</strong>에서 확인해 주세요. 앱 버전이나 등록 상태에 따라 표시가 다를 수 있습니다.</li>
        </ul>
      </section>
      <section>
        <h4>제조번호와 구분해 주세요</h4>
        <p>모델명은 영문과 숫자가 섞여 있는 경우가 많습니다. 첫 글자만으로 구분하지 말고 <strong>모델명 / Model</strong> 항목을 입력해 주세요. <strong>제조번호 / S/N</strong>은 다른 정보입니다.</p>
      </section>
      <div className="hc-help-sources">
        <a href="https://www.lge.co.kr/support/notice-NTC20241113435001" target="_blank" rel="noopener noreferrer">LG 라벨 위치 안내 ↗</a>
        {category === 'REFRIGERATOR' && <a href="https://www.lge.co.kr/story/faq/refrigerators-faq" target="_blank" rel="noopener noreferrer">냉장고·김치냉장고 FAQ ↗</a>}
        {category === 'WASHER' && <a href="https://www.lge.co.kr/support/solutions-1006687" target="_blank" rel="noopener noreferrer">통돌이 모델명 확인 안내 ↗</a>}
        <a href="https://www.lge.co.kr/support/software-SW20241126439006" target="_blank" rel="noopener noreferrer">ThinQ 제품 정보 안내 ↗</a>
      </div>
    </HelpPopover>
  )
}
