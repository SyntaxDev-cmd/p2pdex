// Copiar ao clicar
document.querySelectorAll('[data-copy]').forEach(el => {
  el.title = 'Clique para copiar';
  el.addEventListener('click', () => {
    navigator.clipboard && navigator.clipboard.writeText(el.textContent.trim());
    const old = el.style.outline;
    el.style.outline = '2px solid #22c55e';
    setTimeout(() => el.style.outline = old, 600);
  });
});

// Sincroniza <input type=color> com campo de texto hex
document.querySelectorAll('.color').forEach(box => {
  const picker = box.querySelector('input[type=color]');
  const text = box.querySelector('input[type=text]');
  if (!picker || !text) return;
  picker.addEventListener('input', () => { text.value = picker.value.toUpperCase(); text.dispatchEvent(new Event('input', {bubbles: true})); });
  text.addEventListener('input', () => { if (/^#[0-9a-f]{6}$/i.test(text.value)) picker.value = text.value; });
});

// Confirmações
document.querySelectorAll('[data-confirm]').forEach(el => {
  el.addEventListener('click', ev => { if (!confirm(el.dataset.confirm)) ev.preventDefault(); });
});

// Pré-visualização ao vivo do app
(function () {
  const tv = document.getElementById('tvPreview');
  if (!tv) return;
  const form = tv.closest('form') || document;
  const val = n => { const el = form.querySelector('[name="' + n + '"]'); return el ? el.value : ''; };
  const hexA = (hex, pct) => {
    const n = parseInt(hex.slice(1), 16);
    return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${(pct / 100).toFixed(2)})`;
  };
  function render() {
    const s = tv.style;
    s.setProperty('--p', val('color_primary'));
    s.setProperty('--a', val('color_accent'));
    s.setProperty('--b1', val('color_bg1'));
    s.setProperty('--b2', val('color_bg2'));
    s.setProperty('--t', val('color_text'));
    s.setProperty('--t2', val('color_text2'));
    s.setProperty('--ft', val('color_focus_text'));
    s.setProperty('--k', val('color_kids'));
    s.setProperty('--rad', (parseInt(val('corner_radius')) || 0) + 'px');
    if (/^#[0-9a-f]{6}$/i.test(val('color_surface'))) s.setProperty('--s', hexA(val('color_surface'), parseInt(val('surface_opacity')) || 0));
    const mode = val('background_mode');
    const img = tv.dataset.bg;
    if (mode === 'image' && img) {
      s.background = `linear-gradient(${hexA(val('color_bg2') || '#000000', 45)},${hexA(val('color_bg2') || '#000000', 45)}), url("${img}") center/cover`;
    } else if (mode === 'solid') {
      s.background = val('color_bg1');
    } else {
      s.background = `linear-gradient(135deg, ${val('color_bg1')}, ${val('color_bg2')})`;
    }
    const name = tv.querySelector('[data-name]');
    if (name) name.textContent = val('app_name') || 'App';
    const op = form.querySelector('[data-opacity-label]');
    if (op) op.textContent = val('surface_opacity') + '%';
  }
  form.addEventListener('input', render);
  form.addEventListener('change', render);
  render();

  // Presets
  document.querySelectorAll('[data-preset]').forEach(btn => {
    btn.addEventListener('click', () => {
      const p = JSON.parse(btn.dataset.preset);
      Object.keys(p).forEach(k => {
        const el = form.querySelector('[name="' + k + '"]');
        if (!el) return;
        el.value = p[k];
        const box = el.closest('.color');
        if (box) { const pk = box.querySelector('input[type=color]'); if (pk) pk.value = p[k]; }
      });
      render();
    });
  });
})();

// Tabelas viram cartões no celular: copia o título da coluna para cada célula
document.querySelectorAll('.tbl table').forEach(t => {
  const head = t.querySelector('tr');
  if (!head) return;
  const labels = Array.from(head.children).map(th => th.textContent.trim());
  t.querySelectorAll('tr').forEach((tr, i) => {
    if (i === 0) return;
    Array.from(tr.children).forEach((td, j) => {
      if (!td.hasAttribute('data-label') && labels[j] && td.colSpan === 1) td.setAttribute('data-label', labels[j]);
    });
  });
});

// Fecha o menu lateral com ESC
document.addEventListener('keydown', e => { if (e.key === 'Escape') document.getElementById('side')?.classList.remove('open'); });
