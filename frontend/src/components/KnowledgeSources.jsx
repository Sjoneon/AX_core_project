import { collectKnowledge } from '../customerDisplay.js'

function Items({ label, values }) {
  if (!values.length) return null
  return <div><strong>{label}</strong><ul>{values.map((value) => <li key={value}>{value}</li>)}</ul></div>
}

export default function KnowledgeSources({ tools, sources, safetyOnly = false }) {
  const documents = collectKnowledge(tools, sources).filter((doc) => doc.safety === safetyOnly)
  if (!documents.length) return null
  return <section className="message-sources" aria-label={safetyOnly ? '안전 주의 근거' : '제조사 근거와 적용 조건'}>
    <p>{safetyOnly ? '안전 주의 · 다른 안내보다 먼저 확인해 주세요.' : '제조사 근거 · 실행 절차는 자가검진 안내에서 확인해 주세요.'}</p>
    {documents.map((doc, index) => <details key={doc.id || index} open={doc.safety}>
      <summary>{doc.safety ? '안전 주의 · ' : doc.supplement ? '별도 보완 자료 · ' : ''}{doc.title}</summary>
      <p>{doc.applicability} · 실행 승인과 별개입니다.</p>
      {doc.legacy && <p>저장 당시 자료에서 확인한 설명입니다. 현재 실행 승인이나 최신 정책을 뜻하지 않습니다.</p>}
      {doc.content && <p className="source-content">{doc.content}</p>}
      <Items label="증상·표시 의미" values={doc.symptoms} />
      <Items label="원문에 기재된 원인·상황" values={doc.causes} />
      <Items label="적용 조건" values={[...new Set([...doc.conditions, ...doc.requirements])]} />
      <Items label="주의사항" values={doc.warnings} />
      {doc.modelCodes.length > 0 && <p>자료에 기재된 모델: {doc.modelCodes.join(', ')}</p>}
      {doc.conditionalModels.length > 0 && <p>조건 확인이 필요한 모델: {doc.conditionalModels.join(', ')}</p>}
      {doc.excludedModels.length > 0 && <p>적용 제외 모델: {doc.excludedModels.join(', ')}</p>}
      {doc.branches.map((branch, branchIndex) => <div className="knowledge-branch" key={branchIndex}>
        {branch.description && <p>{branch.description}</p>}
        <Items label="이 상황의 조건" values={branch.conditions} />
        <Items label="이 상황의 주의사항" values={branch.warnings} />
      </div>)}
      {doc.sources.length > 0 ? <ul>{doc.sources.map((source, sourceIndex) => <li key={source.url}>
        <a href={source.url} target="_blank" rel="noreferrer">공식 출처{doc.sources.length > 1 ? ` ${sourceIndex + 1}` : ''}{source.location ? ` · ${source.location}` : ''}</a>
      </li>)}</ul> : <p>저장된 자료에 공식 출처 링크가 없습니다.</p>}
    </details>)}
  </section>
}
