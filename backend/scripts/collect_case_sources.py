#!/usr/bin/env python3
"""Collect the source posts of the domestic repair cases once, skipping pages already in the source registry.

The case table is 국내사례_오늘가치_정규화.csv from 수리비_정규화_과정/수리비정규화_패키지_v3.zip. Posts are saved
to a local folder (never committed) and registered in collected_sources.json. Requests are 1 second apart.

  python -I backend/scripts/collect_case_sources.py --cases <csv> --out <folder>
  python -I backend/scripts/collect_case_sources.py --cases <csv> --out <folder> --register-existing
"""
import argparse
import csv
import re
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # -I leaves the script folder off sys.path
from source_registry import SourceRegistry

UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36'


def fetch_url(url: str) -> str:
    # Naver blog bodies live in the PostView frame, not in the outer page.
    match = re.match(r'https?://(?:m\.)?blog\.naver\.com/([^/?#]+)/(\d+)', url)
    return f'https://blog.naver.com/PostView.naver?blogId={match.group(1)}&logNo={match.group(2)}' if match else url


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--cases', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--register-existing', action='store_true', help='register posts already in --out without requesting them')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    registry = SourceRegistry()
    fetched = skipped = failed = 0
    for row in csv.DictReader(open(args.cases, encoding='utf-8-sig')):
        url, label = row['출처'], f"repair_case:{row['브랜드']}/{row['ID']}"
        path = args.out / f"{row['브랜드']}_{row['ID']}.html"
        if registry.seen(url):
            registry.record(url, label); skipped += 1; continue
        if args.register_existing:
            if path.exists(): registry.record(url, label, path.read_bytes()); fetched += 1
            continue
        try:
            request = urllib.request.Request(fetch_url(url), headers={'User-Agent': UA, 'Accept-Language': 'ko-KR,ko'})
            data = urllib.request.urlopen(request, timeout=20).read()
            path.write_bytes(data); registry.record(url, label, data); fetched += 1
        except Exception as error:
            registry.record(url, label, status=f'FAILED:{type(error).__name__}'); failed += 1
        time.sleep(1.0)
    registry.save()
    print(f'registered {fetched}, already collected {skipped}, failed {failed}; registry {registry.path}')


if __name__ == '__main__':
    main()
