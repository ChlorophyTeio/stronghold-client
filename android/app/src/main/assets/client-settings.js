(() => {
  // The Android client uses the full display. Explicit native sliders are the
  // only margins; do not let the website add another automatic safe area.
  for (const side of ['t', 'r', 'b', 'l']) {
    document.documentElement.style.setProperty(`--sa-${side}`, '0px', 'important');
  }
  if (window.__spClientSettingsObserver) return;
  const addEntry = () => {
    for (const list of document.querySelectorAll('.set-list')) {
      if (list.querySelector('[data-sp-client-settings]')) continue;
      const row = document.createElement('div');
      row.className = 'set-row';
      row.dataset.spClientSettings = '';
      const label = document.createElement('span');
      label.className = 'set-row__label';
      label.textContent = 'Android 客户端';
      const button = document.createElement('a');
      button.className = 'btn btn--secondary';
      button.textContent = '客户端设置';
      button.href = new URL('/__sp_client_settings__', location.origin).href;
      button.setAttribute('role', 'button');
      button.style.cssText = 'padding:8px 14px;min-height:36px;display:inline-flex;align-items:center;text-decoration:none;white-space:nowrap';
      row.append(label, button);
      list.prepend(row);
    }
  };
  window.__spClientSettingsObserver = new MutationObserver(addEntry);
  window.__spClientSettingsObserver.observe(document.documentElement, {childList:true, subtree:true});
  addEntry();
})();
