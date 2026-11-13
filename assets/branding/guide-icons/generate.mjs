import { writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Keep guide glyphs in one coordinate system over the same Grinnotech panel.
const icons = [
  ['configuration', 'Configuration', 'configuration.md', '<path d="M19 23v24M29 23v24M39 23v24"/><g fill="#063D26"><circle cx="19" cy="30" r="3"/><circle cx="29" cy="40" r="3"/><circle cx="39" cy="28" r="3"/></g>'],
  ['maven-adapter', 'Maven adapter', 'maven.md', '<path d="M24 22v7M34 22v7M21 29h16v5a8 8 0 0 1-16 0ZM29 42v7"/>'],
  ['configuration-map', 'Configuration map', 'configuration.md#gradle-and-maven-configuration-map', '<path d="M17 29h24l-5-5M41 29l-5 5M41 41H17l5-5M17 41l5 5"/>'],
  ['architecture', 'Architecture', 'architecture.md', '<path d="m29 21 13 7-13 7-13-7ZM16 35l13 7 13-7M16 42l13 7 13-7"/>'],
  ['history-refresh', 'History refresh', 'history-refresh.md', '<path d="M39 29a11 11 0 1 0 0 13M39 23v7h-7M29 28v8l5 3"/>'],
  ['history-recovery', 'History recovery', 'history-refresh.md#recover-a-missing-shard-download', '<path d="M18 39v9h22v-9M29 22v18M23 34l6 6 6-6"/>'],
  ['ci-integration', 'CI integration', 'ci.md', '<path d="M19 35h10V25h9M29 35v10h9"/><circle cx="17" cy="35" r="3"/><rect x="36" y="22" width="7" height="7"/><rect x="36" y="42" width="7" height="7"/>'],
  ['coverage', 'Manifests and coverage', 'shard-verification.md', '<path d="M19 22h15l6 6v21H19ZM34 22v7h6M23 39l5 5 8-10"/>'],
  ['test-selection', 'Test selection', 'test-selection.md', '<path d="M16 24h26L32 36v12l-6-4v-8Z"/>'],
  ['junit-containers', 'JUnit containers', 'testcontainers-example.md', '<ellipse cx="29" cy="26" rx="11" ry="4"/><path d="M18 26v16c0 6 22 6 22 0V26M23 36l5 5 8-9"/>'],
  ['spock-containers', 'Spock containers', 'spock-testcontainers-example.md', '<path d="M24 22h10M26 22v9l-8 14a3 3 0 0 0 3 4h16a3 3 0 0 0 3-4l-8-14v-9M22 39h14"/>'],
  ['testng-containers', 'TestNG containers', 'testng-testcontainers-example.md', '<rect x="17" y="23" width="5" height="5"/><rect x="17" y="33" width="5" height="5"/><rect x="17" y="43" width="5" height="5"/><path d="M29 25h12M29 35h12M29 44l4 4 9-10"/>'],
  ['compatibility', 'Testing and compatibility', 'testing.md', '<g fill="#CDFFD8" stroke="none"><rect x="17" y="23" width="6" height="6"/><rect x="26" y="23" width="6" height="6"/><rect x="35" y="23" width="6" height="6"/><rect x="17" y="32" width="6" height="6"/><rect x="26" y="32" width="6" height="6"/><rect x="35" y="32" width="6" height="6"/><rect x="17" y="41" width="6" height="6"/><rect x="26" y="41" width="6" height="6"/><rect x="35" y="41" width="6" height="6"/></g>'],
  ['execution-equivalence', 'Execution equivalence', 'execution-equivalence.md', '<path stroke-width="4" d="M18 30h22M18 41h22"/><path d="m16 24-3 11 3 11M42 24l3 11-3 11"/>'],
  ['security', 'Security boundaries', 'security.md', '<path d="m29 21 12 5v9c0 7-12 14-12 14S17 42 17 35v-9ZM23 34l4 4 8-8"/>'],
  ['dependency-security', 'Dependency scanning', 'dependency-security.md', '<circle cx="26" cy="32" r="9"/><path d="m33 39 9 9M26 27v6"/><circle cx="26" cy="37" r="1" fill="#CDFFD8" stroke="none"/>'],
  ['dependency-remediation', 'Dependency remediation', 'dependency-remediation-2026-11-13.md', '<path d="M40 23a9 9 0 0 0-12 11L17 45l4 4 11-11a9 9 0 0 0 11-12l-6 6-5-5Z"/>'],
  ['dependency-integrity', 'Dependency integrity', 'dependency-verification.md', '<rect x="19" y="32" width="20" height="16" rx="2"/><path d="M23 32v-5a6 6 0 0 1 12 0v5M29 38v5"/>'],
  ['security-review', 'Security review', 'security-review-2026-11-12.md', '<path d="M24 25h-5v24h21V25h-6"/><rect x="24" y="21" width="10" height="7" rx="1"/><path d="m24 38 4 4 8-9"/>'],
  ['artifact-audit', 'Published artifact audit', 'published-artifact-audit.md', '<path d="m17 27 11-6 11 6-11 6ZM17 27v14l11 6 11-6V27M28 33v14"/><circle cx="39" cy="43" r="7" fill="#063D26"/><path d="m35 43 3 3 5-6"/>'],
  ['troubleshooting', 'Troubleshooting', 'troubleshooting.md', '<circle cx="29" cy="35" r="12"/><circle cx="29" cy="35" r="6"/><path d="m20 26 5 5M33 39l5 5M20 44l5-5M33 31l5-5"/>'],
  ['releases', 'Releases and upgrades', 'releases.md', '<path d="M29 44V23M20 32l9-9 9 9M18 43v6h22v-6"/>'],
  ['maven-central', 'Maven Central setup', 'maven-central.md', '<path d="M20 40a7 7 0 0 1-2-13 10 10 0 0 1 19-1 7 7 0 0 1 3 14M29 48V33M24 38l5-5 5 5"/>'],
  ['publication-descriptions', 'Publication descriptions', 'publication-descriptions.md', '<path d="M20 22h19v27H20ZM25 30h9M25 36h9M25 42h5"/>'],
  ['diagrams', 'PlantUML diagrams', 'diagrams/README.md', '<rect x="25" y="22" width="8" height="7"/><path d="M29 29v6H17v6M29 35h12v6"/><rect x="13" y="41" width="8" height="7"/><rect x="37" y="41" width="8" height="7"/>'],
];

const escape = (value) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const directory = new URL('./', import.meta.url);
const manifest = [];
const preview = ['<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="790" viewBox="0 0 1200 790">',
  '<rect width="1200" height="790" fill="#F2F7F2"/>',
  '<text x="24" y="42" font-family="Arial,sans-serif" font-weight="700" font-size="30" fill="#063D26">ShardPilot guide icons / 64 + 32 px</text>'];

for (const [index, [slug, title, href, glyph]] of icons.entries()) {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64" role="img" aria-labelledby="${slug}-title">\n` +
    `  <title id="${slug}-title">${escape(title)}</title>\n` +
    '  <path fill="#A0EF3B" d="M8 14 14 8H56L50 14Z"/>\n' +
    '  <path fill="#388E24" d="m50 14 6-6v42l-6 6Z"/>\n' +
    '  <path fill="#063D26" d="M8 14H50V56H8Z"/>\n' +
    `  <g fill="none" stroke="#CDFFD8" stroke-width="2.8" stroke-linecap="round" stroke-linejoin="round">${glyph}</g>\n` +
    '</svg>\n';
  await writeFile(new URL(`${slug}.svg`, directory), svg);
  manifest.push({ file: `${slug}.svg`, title, href });
  const image = `data:image/svg+xml;base64,${Buffer.from(svg).toString('base64')}`;
  const x = (index % 5) * 240;
  const y = 70 + Math.floor(index / 5) * 138;
  // Each standalone fragment declares SVG so editor validation uses its image attributes.
  preview.push(`<image xmlns="http://www.w3.org/2000/svg" x="${x + 24}" y="${y}" width="64" height="64" href="${image}"/>`,
    `<image xmlns="http://www.w3.org/2000/svg" x="${x + 128}" y="${y + 16}" width="32" height="32" href="${image}"/>`,
    `<text x="${x + 16}" y="${y + 94}" font-family="Arial,sans-serif" font-size="15" fill="#063D26">${escape(title)}</text>`);
}
preview.push('</svg>');
await writeFile(new URL('icons.json', directory), `${JSON.stringify(manifest, null, 2)}\n`);
await writeFile(new URL('preview.svg', directory), `${preview.join('\n')}\n`);
console.log(`Generated ${icons.length} guide icons in ${fileURLToPath(directory)}`);
