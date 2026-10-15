import { Fragment } from 'react'
import { customerText, linkParts, list, warrantyView } from '../customerDisplay.js'
import WarrantyResult from './WarrantyResult.jsx'
import RiskQuestion from './RiskQuestion.jsx'
import ToolResults from './ToolResults.jsx'
import KnowledgeSources from './KnowledgeSources.jsx'
import ConsultationCard from './ConsultationCard.jsx'

// Live replies, follow-up actions and history all use the same display path.
export default function ConsultationMessage({ message, active, disabled, onAnswerRisk, onSelectSymptom, onAnswerSelfCare, onSelfCheck, onChooseProduct }) {
  const question = message.tools?.consultation?.data?.question
  // A response with one consultation card shows only that card; older saved replies keep their tool cards.
  const card = message.tools?.card?.data
  const warranty = card ? null : warrantyView(message)
  const reply = customerText(message.content)
  // The self-check card asks its own question, so its options are not sent as chat messages.
  const text = question?.id === 'SELF_CHECK' ? '' : customerText(question?.text)
  return <>
    <p className="message-bubble">{reply
      ? linkParts(reply).map((part, index) => part.href ? <a key={index} href={part.href} target="_blank" rel="noreferrer">{part.text}</a> : <Fragment key={index}>{part.text}</Fragment>)
      : '저장된 안내의 상세 내용은 아래 결과에서 확인해 주세요.'}</p>
    <KnowledgeSources tools={message.tools} sources={message.sources} safetyOnly />
    <RiskQuestion safety={message.tools?.safety} active={active} disabled={disabled} onAnswer={onAnswerRisk} />
    {text && <div className="consultation-controls" aria-label="증상 확인">
      {!reply.includes(text) && <p>{text}</p>}
      {list(question.options).length > 0 && <div className="tool-actions">{list(question.options).map((option) => <button type="button" key={option} disabled={!active || disabled} onClick={(event) => onSelectSymptom(event, option)}>{customerText(option)}</button>)}</div>}
    </div>}
    {card ? <ConsultationCard card={card} version={message.tools.card.meta?.context_version} active={active} disabled={disabled} onSelfCheck={onSelfCheck} onChooseProduct={onChooseProduct} /> : <>
      {warranty && <WarrantyResult result={warranty} />}
      <ToolResults tools={message.tools} active={active && !disabled} onAnswerSelfCare={onAnswerSelfCare} />
      <KnowledgeSources tools={message.tools} sources={message.sources} />
    </>}
  </>
}
