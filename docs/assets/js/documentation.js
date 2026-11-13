// Build section links from the rendered headings, preserving Jekyll's actual IDs.
const contents = document.getElementById('page-contents');
const headings = document.querySelectorAll('#main-content h2[id], #main-content h3[id]');
if (contents && headings.length) {
  for (const heading of headings) {
    const item = document.createElement('li');
    if (heading.tagName === 'H3') item.className = 'subheading';
    const link = document.createElement('a');
    link.href = '#' + encodeURIComponent(heading.id);
    link.textContent = heading.textContent;
    item.append(link);
    contents.append(item);
  }
  contents.closest('section').hidden = false;
}

// Native details remains usable without JavaScript; only its initial mobile state changes.
const menu = document.querySelector('.documentation-menu');
const mobile = window.matchMedia('(max-width: 900px)');
function updateMenu() {
  if (menu) menu.open = !mobile.matches;
}
updateMenu();
mobile.addEventListener('change', updateMenu);
contents?.addEventListener('click', (event) => {
  if (mobile.matches && event.target.closest('a')) menu.open = false;
});
