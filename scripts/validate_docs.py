"""Validate rendered Pages links and main-branch repository references offline."""
import argparse
from collections import Counter
from html import unescape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit


class Document(HTMLParser):
    """Collect actual rendered anchors, links and assets, including canonical URLs."""

    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.ids = set()
        self.links = []
        self.duplicates = []
        self.feed(text)

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        identity = attributes.get('id')
        if identity:
            if identity in self.ids:
                self.duplicates.append(identity)
            self.ids.add(identity)
        if tag == 'a' and attributes.get('name'):
            self.ids.add(attributes['name'])
        for name in ('href', 'src'):
            if attributes.get(name):
                self.links.append(attributes[name])


def markdown_anchors(text):
    """Resolve GitHub heading fragments, ignoring fenced code and numbering duplicates."""
    anchors = set()
    counts = Counter()
    fence = None
    for line in text.splitlines():
        marker = re.match(r'^\s{0,3}(`{3,}|~{3,})', line)
        if marker:
            token = marker[1]
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            continue
        if fence is not None:
            continue
        heading = re.match(r'^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$', line)
        if not heading:
            continue
        title = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', heading[1])
        title = unescape(re.sub(r'<[^>]+>', '', title)).lower()
        slug = re.sub(r'[^\w\- ]', '', title).replace(' ', '-')
        suffix = counts[slug]
        counts[slug] += 1
        anchors.add(slug + (f'-{suffix}' if suffix else ''))
    anchors.update(Document(text).ids)
    return anchors


def validate(directory, repository, baseurl='/shardpilot'):
    """Reject broken rendered links and source references; inventory other HTTPS URLs."""
    root = Path(directory).resolve()
    repository = Path(repository).resolve()
    if not (root / 'index.html').is_file():
        raise ValueError('Documentation artifact has no index.html')
    baseurl = baseurl.rstrip('/')
    if baseurl and (not baseurl.startswith('/') or '..' in baseurl.split('/')):
        raise ValueError('Invalid site base URL')
    pages = {path: Document(path.read_text(encoding='utf-8')) for path in root.rglob('*.html')}
    failures = []
    external = set()
    internal_count = 0
    repository_count = 0
    repository_anchors = {}
    for page, document in sorted(pages.items()):
        source = page.relative_to(root).as_posix()
        failures.extend(f'{source}: duplicate anchor #{name}' for name in document.duplicates)
        for value in document.links:
            link = urlsplit(value)
            if link.scheme.lower() not in ('https', 'http', 'mailto', 'tel', ''):
                failures.append(f'{source}: unsupported link scheme: {value}')
                continue
            path = unquote(link.path)
            fragment = unquote(link.fragment)
            prefix = '/sjacek/shardpilot/blob/main/'
            if link.hostname == 'github.com' and path.startswith(prefix):
                repository_count += 1
                target = (repository / path[len(prefix):]).resolve()
                if not target.is_relative_to(repository) or not target.is_file():
                    failures.append(f'{source}: missing repository target: {value}')
                elif fragment:
                    if target not in repository_anchors:
                        repository_anchors[target] = markdown_anchors(target.read_text(encoding='utf-8'))
                    if fragment not in repository_anchors[target]:
                        failures.append(f'{source}: missing repository anchor: {value}')
                continue
            same_site = link.hostname == 'sjacek.github.io' and (
                path == baseurl or path.startswith(baseurl + '/'))
            if (link.scheme or link.netloc) and not same_site:
                if link.scheme in ('http', 'https') or link.netloc:
                    external.add(value)
                continue
            internal_count += 1
            if path.startswith('/'):
                if baseurl and path != baseurl and not path.startswith(baseurl + '/'):
                    failures.append(f'{source}: link omits site prefix: {value}')
                    continue
                target = root / path[len(baseurl):].lstrip('/')
            else:
                target = page.parent / path if path else page
            target = target.resolve()
            if not target.is_relative_to(root):
                failures.append(f'{source}: link escapes artifact: {value}')
                continue
            if target.is_dir():
                target /= 'index.html'
            if not target.is_file():
                failures.append(f'{source}: missing target: {value}')
                continue
            if fragment and (target not in pages or fragment not in pages[target].ids):
                failures.append(f'{source}: missing anchor: {value}')
    if failures:
        raise ValueError('\n'.join(failures))
    return {'pages': len(pages), 'internal_links_and_assets': internal_count,
            'repository_links': repository_count, 'external_urls': sorted(external)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('--repository', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--baseurl', default='/shardpilot')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    try:
        result = validate(args.directory, args.repository, args.baseurl)
    except ValueError as failure:
        parser.exit(1, str(failure) + '\n')
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f'PASS: {result["pages"]} HTML pages, {result["internal_links_and_assets"]} internal links/assets, '
          f'{result["repository_links"]} repository links; {len(result["external_urls"])} external URLs inventoried (not fetched).')


if __name__ == '__main__':
    main()
