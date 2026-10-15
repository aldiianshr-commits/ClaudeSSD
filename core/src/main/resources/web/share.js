'use strict';
/* Halaman publik tautan berbagi CloudSSD. */
(() => {
  const app = document.getElementById('app');
  const token = decodeURIComponent(location.pathname.split('/')[2] || '');
  const base = '/api/public/share/' + encodeURIComponent(token);

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [k, v] of Object.entries(attrs || {})) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v; else if (k.startsWith('on')) el.addEventListener(k.slice(2), v); else el.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat()) if (c != null && c !== false) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    return el;
  }
  function icon(name) {
    const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    s.setAttribute('class', 'ic');
    const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    u.setAttribute('href', '#i-' + name); s.append(u); return s;
  }
  function fmtBytes(n) {
    if (n < 1000) return n + ' B';
    const u = ['kB', 'MB', 'GB', 'TB']; let i = -1;
    do { n /= 1000; i++; } while (n >= 1000 && i < u.length - 1);
    return n.toLocaleString('id-ID', { maximumFractionDigits: 1 }) + ' ' + u[i];
  }
  const card = (...c) => { app.replaceChildren(h('div', { class: 'auth-card' }, h('div', { class: 'brand' }, icon('logo'), 'CloudSSD'), ...c)); };

  async function load() {
    let res;
    try { res = await fetch(base, { credentials: 'same-origin' }); }
    catch (_) { return card(h('h1', null, 'Tidak dapat terhubung'), h('p', { class: 'lead' }, 'Server CloudSSD tidak dapat dijangkau dari jaringan ini.')); }
    const d = await res.json().catch(() => ({}));
    if (!res.ok) return card(h('h1', null, 'Tautan tidak tersedia'), h('p', { class: 'lead' }, d.error || 'Tautan tidak ditemukan.'));
    if (d.needsPassword) return askPassword();
    show(d);
  }

  function askPassword(msg) {
    const pw = h('input', { class: 'input', type: 'password', autocomplete: 'off', required: true });
    const err = msg ? h('div', { class: 'form-error' }, msg) : null;
    card(h('h1', null, 'Tautan dilindungi'), h('p', { class: 'lead' }, 'Masukkan kata sandi dari pengirim.'), err,
      h('form', { onsubmit: async (e) => {
        e.preventDefault();
        const r = await fetch(base + '/unlock', { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: pw.value }) });
        if (r.ok) return load();
        const d = await r.json().catch(() => ({}));
        askPassword(d.error || 'Gagal');
      } }, h('div', { class: 'field' }, h('label', null, 'Kata sandi'), pw), h('button', { class: 'btn primary', type: 'submit' }, icon('lock'), 'Buka')));
    pw.focus();
  }

  function show(d) {
    const url = base + '/content';
    let pv = null;
    if (d.previewable) {
      if (d.kind === 'image') pv = h('img', { src: url, alt: d.name });
      else if (d.kind === 'video') pv = h('video', { src: url, controls: true, playsinline: true, preload: 'metadata' });
      else if (d.kind === 'audio') pv = h('audio', { src: url, controls: true });
    }
    const box = h('div', { class: 'auth-card' }, h('div', { class: 'brand' }, icon('logo'), 'CloudSSD'),
      h('h1', null, d.name), h('p', { class: 'lead' }, fmtBytes(d.size) + (d.expiresAt ? ' · berlaku sampai ' + new Date(d.expiresAt).toLocaleString('id-ID') : '')),
      pv && h('div', { class: 'preview' }, pv),
      h('div', { class: 'row', style: null },
        d.previewable && !pv && h('a', { class: 'btn', href: url, target: '_blank', rel: 'noopener' }, 'Buka'),
        d.allowDownload ? h('a', { class: 'btn primary', href: url + '?download=1' }, icon('download'), 'Unduh') : h('span', { class: 'muted' }, 'Pemilik hanya mengizinkan melihat file ini.')));
    box.style.maxWidth = pv ? '900px' : '440px';
    box.lastChild.style.marginTop = '16px';
    app.replaceChildren(box);
  }

  load();
})();
