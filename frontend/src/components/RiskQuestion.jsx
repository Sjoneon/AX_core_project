// 현재 위험 확인 질문에 대한 대화 내 선택 버튼
export default function RiskQuestion({ safety, active, disabled, onAnswer }) {
  if (!active || safety?.data?.risk_status !== 'SUSPECT' || !safety.data.question) return null
  return (
    <div className="tool-actions" role="group" aria-label={safety.data.question}>
      {[['YES', '예'], ['NO', '아니요'], ['UNSURE', '모르겠어요']].map(([answer, label]) => (
        <button key={answer} type="button" disabled={disabled} onClick={() => onAnswer(answer, safety.meta.context_version)}>{label}</button>
      ))}
    </div>
  )
}
