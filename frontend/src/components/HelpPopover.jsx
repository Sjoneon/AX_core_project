import { useId, useRef, useState } from 'react'
import * as Popover from '@radix-ui/react-popover'
import Icon from './Icon.jsx'
import './HelpPopover.css'

export default function HelpPopover({ term, title, children }) {
  const [open, setOpen] = useState(false)
  const [container, setContainer] = useState(null)
  const triggerRef = useRef(null)
  const titleId = useId()

  function changeOpen(nextOpen) {
    // A native modal makes portals outside its dialog inert.
    if (nextOpen) setContainer(triggerRef.current?.closest('dialog') || null)
    setOpen(nextOpen)
  }

  return (
    <Popover.Root open={open} onOpenChange={changeOpen}>
      <Popover.Trigger asChild>
        <button ref={triggerRef} type="button" className="hc-help-trigger" aria-label={`${term} 도움말 열기`}>
          <Icon name="info" size={20} />
        </button>
      </Popover.Trigger>
      <Popover.Portal container={container}>
        <Popover.Content className="hc-help-popover" sideOffset={8} align="start" collisionPadding={16}
          aria-labelledby={titleId} onEscapeKeyDown={(event) => {
            event.preventDefault()
            event.stopPropagation()
            setOpen(false)
          }}>
          <div className="hc-help-popover-head">
            <h3 id={titleId}>{title || `${term} 찾는 방법`}</h3>
            <Popover.Close asChild>
              <button type="button" className="hc-help-close" aria-label="도움말 닫기"><Icon name="close" size={18} /></button>
            </Popover.Close>
          </div>
          <div className="hc-help-popover-body">{children}</div>
          <Popover.Arrow className="hc-help-arrow" />
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  )
}
