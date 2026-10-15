#!/usr/bin/env python3
"""Local registry of collected source pages, so the same page is never collected twice.

The registry file (default: collected_sources.json at the repository root) is local working data and is not
published. Each entry is keyed by a normalized URL: host lowercased, mobile Naver blog and PostView forms folded
into blog.naver.com/<blog>/<post>, query and fragment dropped except where they identify the page.

  python -I backend/scripts/source_registry.py list
  python -I backend/scripts/source_registry.py check <url> [<url> ...]
"""
import hashlib
import json
import re
import sys
from datetime import date
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

DEFAULT = Path(__file__).resolve().parents[2] / 'collected_sources.json'
# Query parameters that identify the page itself on these hosts.
IDENTIFYING_QUERY = {'www.lge.co.kr': {'seq', 'shopId'}}


def normalize(url: str) -> str:
    parts = urlsplit(url.strip())
    host = parts.netloc.lower().removeprefix('m.') if parts.netloc.lower().startswith('m.blog.naver.com') else parts.netloc.lower()
    path = re.sub(r'/+$', '', parts.path) or '/'
    query = parse_qs(parts.query)
    if host == 'blog.naver.com':
        if path.lower().endswith('/postview.naver') or path.lower() == '/postview.nhn':
            blog, post = query.get('blogId', [''])[0], query.get('logNo', [''])[0]
            if blog and post: return f'blog.naver.com/{blog}/{post}'
        match = re.match(r'^/([^/]+)/(\d+)$', path)
        if match: return f'blog.naver.com/{match.group(1)}/{match.group(2)}'
    keep = sorted((k, v[0]) for k, v in query.items() if k in IDENTIFYING_QUERY.get(host, set()))
    return host + path + ('?' + '&'.join(f'{k}={v}' for k, v in keep) if keep else '')


class SourceRegistry:
    def __init__(self, path: Path = DEFAULT):
        self.path = Path(path)
        data = json.loads(self.path.read_text(encoding='utf-8')) if self.path.exists() else {}
        self.entries = {entry['key']: entry for entry in data.get('sources', [])}

    def seen(self, url: str) -> bool:
        return normalize(url) in self.entries

    def record(self, url: str, used_for: str, content: bytes | None = None, status: str = 'FETCHED') -> dict:
        key = normalize(url)
        entry = self.entries.setdefault(key, {'key': key, 'url': url, 'site': key.split('/')[0], 'used_for': [],
                                              'collected_at': date.today().isoformat(), 'status': status})
        if used_for not in entry['used_for']: entry['used_for'].append(used_for)
        if content is not None: entry.update(bytes=len(content), sha256=hashlib.sha256(content).hexdigest(), status=status)
        return entry

    def save(self) -> None:
        sites = {}
        for entry in self.entries.values(): sites[entry['site']] = sites.get(entry['site'], 0) + 1
        data = {'purpose': '수집한 출처 페이지 목록. 같은 정규화 URL(key)은 다시 수집하지 않는다.',
                'updated_at': date.today().isoformat(), 'count': len(self.entries),
                'sites': dict(sorted(sites.items(), key=lambda item: -item[1])),
                'sources': sorted(self.entries.values(), key=lambda entry: entry['key'])}
        self.path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    registry = SourceRegistry()
    if len(sys.argv) >= 2 and sys.argv[1] == 'list':
        for entry in registry.entries.values(): print(entry['key'], '|', ', '.join(entry['used_for']), '|', entry['collected_at'])
    elif len(sys.argv) >= 3 and sys.argv[1] == 'check':
        for url in sys.argv[2:]: print(('SEEN ' if registry.seen(url) else 'NEW  ') + normalize(url))
    else:
        sys.exit(__doc__)
