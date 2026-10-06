/* Compukters documentation navigation. SPDX-License-Identifier: Apache-2.0 */
(() => {
  const header = document.querySelector('.site-header');
  const sidebar = document.querySelector('[data-sidebar]');
  const compact = window.matchMedia('(max-width: 55rem)');
  const updateLayout = () => {
    if (header) document.documentElement.style.setProperty('--header-height', `${Math.ceil(header.getBoundingClientRect().height)}px`);
    document.querySelector('.menu-tabs')?.setAttribute('aria-orientation', compact.matches ? 'horizontal' : 'vertical');
  };
  const updateSidebar = () => { if (sidebar) sidebar.open = !compact.matches; };
  updateLayout();
  updateSidebar();
  compact.addEventListener('change', updateSidebar);
  window.addEventListener('resize', updateLayout);
  if (header && 'ResizeObserver' in window) new ResizeObserver(updateLayout).observe(header);

  const menu = document.querySelector('#wiki-menu');
  // Keep ordinary audience hub links on browsers without native dialog support or with JavaScript disabled.
  if (!menu || typeof menu.show !== 'function') return;
  const triggers = [...document.querySelectorAll('[data-nav-open]')];
  const tabs = [...menu.querySelectorAll('[data-menu-tab]')];
  const panels = [...menu.querySelectorAll('[data-menu-panel]')];
  const backdrop = document.querySelector('[data-nav-backdrop]');
  const title = menu.querySelector('#wiki-menu-title');
  const description = menu.querySelector('#wiki-menu-description');
  let returnFocus = null;
  let selected = null;
  let restoreFocus = true;

  const select = (id) => {
    const tab = tabs.find((item) => item.dataset.menuTab === id);
    if (!tab) return;
    selected = id;
    title.textContent = tab.dataset.title;
    description.textContent = tab.dataset.description;
    tabs.forEach((item) => {
      const active = item === tab;
      item.setAttribute('aria-selected', String(active));
      item.tabIndex = active ? 0 : -1;
    });
    panels.forEach((panel) => { panel.hidden = panel.dataset.menuPanel !== id; });
    triggers.forEach((trigger) => trigger.setAttribute('aria-expanded', String(menu.open && trigger.dataset.navOpen === id)));
  };

  const close = (focus = true) => {
    if (!menu.open) return;
    restoreFocus = focus;
    menu.close();
  };
  menu.addEventListener('close', () => {
    if (menu.open) return; // A rapid reopen must not be cleaned up by an earlier queued close event.
    document.body.classList.remove('menu-open');
    backdrop.hidden = true;
    triggers.forEach((trigger) => trigger.setAttribute('aria-expanded', 'false'));
    if (restoreFocus && returnFocus?.isConnected) returnFocus.focus();
  });
  menu.addEventListener('cancel', (event) => { event.preventDefault(); close(); });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && menu.open) { event.preventDefault(); close(); }
  });

  triggers.forEach((trigger) => {
    trigger.hidden = false;
    trigger.addEventListener('click', () => {
      if (menu.open && selected === trigger.dataset.navOpen) { close(); return; }
      returnFocus = trigger;
      restoreFocus = true;
      if (!menu.open) {
        menu.show(); // A navigation disclosure: keep the other header buttons available for direct switching.
        document.body.classList.add('menu-open');
        backdrop.hidden = false;
      }
      select(trigger.dataset.navOpen);
      menu.scrollTop = 0;
      tabs.find((tab) => tab.dataset.menuTab === selected)?.focus();
    });
  });
  document.querySelectorAll('[data-nav-fallback]').forEach((link) => { link.hidden = true; });
  tabs.forEach((tab, index) => {
    tab.addEventListener('click', () => select(tab.dataset.menuTab));
    tab.addEventListener('keydown', (event) => {
      let next;
      if (event.key === 'ArrowRight' || event.key === 'ArrowDown') next = (index + 1) % tabs.length;
      if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') next = (index + tabs.length - 1) % tabs.length;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = tabs.length - 1;
      if (next === undefined) return;
      event.preventDefault();
      select(tabs[next].dataset.menuTab);
      tabs[next].focus();
    });
  });
  menu.querySelector('[data-nav-close]').addEventListener('click', () => close());
  backdrop.addEventListener('click', () => close());
  menu.addEventListener('click', (event) => { if (event.target.closest('a')) close(false); });
  document.addEventListener('focusin', (event) => {
    if (menu.open && !menu.contains(event.target) && !header.contains(event.target)) close(false);
  });
})();
