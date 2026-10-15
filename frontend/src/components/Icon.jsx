export default function Icon({ name, size = 22 }) {
  const paths = {
    info: <><circle cx="12" cy="12" r="9" /><path d="M12 10.5v6" /><circle cx="12" cy="7.5" r="1" fill="currentColor" stroke="none" /></>,
    plus: <path d="M12 5v14M5 12h14" />,
    close: <path d="m6 6 12 12M18 6 6 18" />,
    trash: <path d="M4 6h16M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7m4-7v7" />,
    search: <><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4 4" /></>,
    chat: <path d="M20 11.5a8 8 0 0 1-8 8H5l-3 2V12a9 9 0 0 1 18-.5Z" />,
    send: <path d="M12 19V5m-6 6 6-6 6 6" />,
    check: <path d="m5 12 4 4L19 6" />,
    chevron: <path d="m9 5 7 7-7 7" />,
    menu: <path d="M4 6h16M4 12h16M4 18h16" />,
    shield: <><path d="m12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6Z" /><path d="m8 12 3 3 5-6" /></>,
    spark: <><path d="m12 3 2.5 6.5L21 12l-6.5 2.5L12 21l-2.5-6.5L3 12l6.5-2.5Z" /><path d="m20 2 .5 1.5L22 4l-1.5.5L20 6l-.5-1.5L18 4l1.5-.5Z" /></>,
    AIR_CONDITIONER: <><rect x="2" y="4" width="20" height="10" rx="3" /><path d="M5 10h14M7 17v3m5-3v4m5-4v3" /></>,
    REFRIGERATOR: <><rect x="6" y="2" width="12" height="20" rx="2" /><path d="M6 10h12M9 5v2m0 7v3" /></>,
    WASHER: <><rect x="3" y="2" width="18" height="20" rx="2" /><circle cx="12" cy="14" r="5" /><path d="M7 5h1m3 0h1m-4 9c2 3 6 3 8 0" /></>,
    DRYER: <><rect x="3" y="2" width="18" height="20" rx="2" /><circle cx="12" cy="14" r="5" /><path d="M7 5h1m3 0h1m-3 9c1-2 3 2 5 0" /></>,
    DISHWASHER: <><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 8h18M7 5.5h1m3 0h1M7 12v6m5-6v6m5-6v6M6 18h12" /></>,
  }

  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {paths[name] || paths.chat}
    </svg>
  )
}
