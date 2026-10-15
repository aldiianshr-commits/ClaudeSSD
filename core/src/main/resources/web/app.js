'use strict';
/* CloudSSD — antarmuka web. Tanpa framework/dependensi; semua data pengguna dirender lewat textContent (aman XSS). */
(() => {
  const $app = document.getElementById('app');
  const $modalRoot = document.getElementById('modal-root');
  const $toasts = document.getElementById('toasts');

  const state = {
    user: null, csrf: null, multiUser: false,
    view: 'dashboard', path: '/', items: [], sort: { key: 'name', dir: 1 },
    selected: new Set(), searchResults: null, searchQuery: '', loading: false,
  };
  let dashTimer = null;

  // ------------------------------------------------------------------ util DOM
  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    if (attrs) for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
      else if (k === 'value') el.value = v;
      else if (k === 'checked') el.checked = !!v;
      else el.setAttribute(k, v === true ? '' : v);
    }
    for (const c of children.flat(Infinity)) {
      if (c == null || c === false) continue;
      el.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return el;
  }
  function icon(name, cls) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'ic' + (cls ? ' ' + cls : ''));
    svg.setAttribute('aria-hidden', 'true');
    const use = document.createElementNS('http://www.w3.org/2000/svg', 'use');
    use.setAttribute('href', '#i-' + name);
    svg.append(use);
    return svg;
  }
  const clear = (el) => { while (el.firstChild) el.firstChild.remove(); return el; };

  function fmtBytes(n) {
    if (n == null) return '—';
    if (n < 1000) return n + ' B';
    const u = ['kB', 'MB', 'GB', 'TB', 'PB'];
    let i = -1;
    do { n /= 1000; i++; } while (n >= 1000 && i < u.length - 1);
    return n.toLocaleString('id-ID', { maximumFractionDigits: n < 10 ? 2 : 1 }) + ' ' + u[i];
  }
  function fmtDate(ms) {
    if (!ms) return '—';
    return new Date(ms).toLocaleString('id-ID', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
  function fmtAgo(ms) {
    if (!ms) return '—';
    const s = Math.round((Date.now() - ms) / 1000);
    if (s < 60) return 'baru saja';
    if (s < 3600) return Math.floor(s / 60) + ' menit lalu';
    if (s < 86400) return Math.floor(s / 3600) + ' jam lalu';
    return fmtDate(ms);
  }
  function fmtDuration(ms) {
    const s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400), hh = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60);
    return (d ? d + ' hr ' : '') + (hh ? hh + ' j ' : '') + m + ' mnt';
  }
  function joinPath(dir, name) { return (dir === '/' ? '' : dir) + '/' + name; }
  function parentPath(p) { const i = p.lastIndexOf('/'); return i <= 0 ? '/' : p.slice(0, i); }

  function toast(msg, kind) {
    const t = h('div', { class: 'toast ' + (kind || '') }, icon(kind === 'err' ? 'alert' : 'check', 'sm'), msg);
    $toasts.append(t);
    setTimeout(() => t.remove(), kind === 'err' ? 6000 : 3200);
  }

  // ------------------------------------------------------------------ API
  class ApiErr extends Error {
    constructor(status, data) { super((data && data.error) || ('HTTP ' + status)); this.status = status; this.code = data && data.code; this.data = data || {}; }
  }
  async function api(method, url, body) {
    const opts = { method, credentials: 'same-origin', headers: {} };
    if (method !== 'GET' && method !== 'HEAD' && state.csrf) opts.headers['X-CSRF-Token'] = state.csrf;
    if (body !== undefined) { opts.headers['Content-Type'] = 'application/json'; opts.body = JSON.stringify(body); }
    let res;
    try { res = await fetch(url, opts); } catch (e) { throw new ApiErr(0, { error: 'Tidak dapat terhubung ke server CloudSSD. Periksa Wi-Fi dan pastikan server di tablet masih berjalan.', code: 'network' }); }
    let data = null;
    const ct = res.headers.get('content-type') || '';
    if (ct.includes('application/json')) { try { data = await res.json(); } catch (_) { } }
    else if (!res.ok) { data = { error: (await res.text()).slice(0, 300) || ('HTTP ' + res.status) }; }
    if (!res.ok) {
      const err = new ApiErr(res.status, data);
      if (res.status === 401 && err.code === 'unauthenticated' && state.user) { state.user = null; renderLogin('Sesi berakhir. Silakan login kembali.'); }
      throw err;
    }
    return data;
  }
  const q = (o) => Object.entries(o).map(([k, v]) => encodeURIComponent(k) + '=' + encodeURIComponent(v)).join('&');
  const fileUrl = (path, inline) => '/api/files/download?' + q({ path, inline: inline ? 1 : 0 });

  // ------------------------------------------------------------------ modal
  function modal({ title, text, body, actions, wide, onClose }) {
    const close = () => { ov.remove(); document.removeEventListener('keydown', esc); onClose && onClose(); };
    const esc = (e) => { if (e.key === 'Escape') close(); };
    const acts = h('div', { class: 'actions' });
    const box = h('div', { class: 'modal' + (wide ? ' wide' : ''), role: 'dialog', 'aria-modal': 'true' },
      title && h('h2', null, title), text && h('p', null, text), body, acts);
    for (const a of actions || []) {
      acts.append(h('button', { class: 'btn ' + (a.kind || ''), type: a.submit ? 'submit' : 'button', onclick: async (e) => {
        if (a.onClick) {
          const btn = e.currentTarget; btn.disabled = true;
          try { const r = await a.onClick(); if (r !== false) close(); } catch (err) { toast(err.message, 'err'); } finally { btn.disabled = false; }
        } else close();
      } }, a.label));
    }
    const ov = h('div', { class: 'overlay', onmousedown: (e) => { if (e.target === ov) close(); } }, box);
    document.addEventListener('keydown', esc);
    $modalRoot.append(ov);
    const first = box.querySelector('input, select');
    if (first) setTimeout(() => first.focus(), 30);
    return close;
  }
  function confirmDialog(title, text, okLabel, danger) {
    return new Promise((resolve) => {
      let done = false;
      modal({ title, text, onClose: () => { if (!done) resolve(false); },
        actions: [{ label: 'Batal' }, { label: okLabel || 'Ya', kind: danger ? 'solid-danger' : 'primary', onClick: () => { done = true; resolve(true); } }] });
    });
  }
  function promptDialog(title, label, value, okLabel) {
    return new Promise((resolve) => {
      let done = false;
      const input = h('input', { class: 'input', value: value || '', maxlength: 255 });
      const body = h('div', { class: 'field' }, h('label', null, label), input);
      const close = modal({ title, body, onClose: () => { if (!done) resolve(null); },
        actions: [{ label: 'Batal' }, { label: okLabel || 'Simpan', kind: 'primary', onClick: () => { done = true; resolve(input.value.trim()); } }] });
      input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { done = true; resolve(input.value.trim()); close(); } });
      if (value) setTimeout(() => { const dot = value.lastIndexOf('.'); input.setSelectionRange(0, dot > 0 ? dot : value.length); }, 40);
    });
  }
  function popupMenu(anchor, items) {
    document.querySelectorAll('.menu').forEach((m) => m.remove());
    const menu = h('div', { class: 'menu', role: 'menu' });
    for (const it of items) {
      if (!it) continue;
      menu.append(h('button', { class: it.danger ? 'danger' : '', role: 'menuitem', onclick: () => { menu.remove(); it.onClick(); } }, icon(it.icon, 'sm'), it.label));
    }
    document.body.append(menu);
    const r = anchor.getBoundingClientRect();
    const mh = menu.offsetHeight, mw = menu.offsetWidth;
    menu.style.top = Math.max(8, (r.bottom + mh + 8 > innerHeight ? r.top - mh - 4 : r.bottom + 4)) + 'px';
    menu.style.left = Math.max(8, Math.min(innerWidth - mw - 8, r.right - mw)) + 'px';
    setTimeout(() => document.addEventListener('mousedown', function off(e) { if (!menu.contains(e.target)) { menu.remove(); document.removeEventListener('mousedown', off); } }), 0);
  }

  // ------------------------------------------------------------------ boot & auth
  async function boot() {
    try {
      const st = await api('GET', '/api/status');
      if (st.setupRequired) return renderSetup();
      try {
        const me = await api('GET', '/api/me');
        setMe(me);
        route();
      } catch (e) {
        if (e.status === 401 || e.status === 403) renderLogin(e.status === 403 ? e.message : null);
        else throw e;
      }
    } catch (e) {
      clear($app).append(h('div', { class: 'auth' }, h('div', { class: 'auth-card' }, brand(), h('h1', null, 'Tidak dapat terhubung'),
        h('p', { class: 'lead' }, e.message), h('button', { class: 'btn primary', onclick: boot }, 'Coba lagi'))));
    }
  }
  function setMe(me) { state.user = me.user; state.csrf = me.csrf; state.multiUser = me.multiUser; }
  const brand = () => h('div', { class: 'brand' }, icon('logo'), 'CloudSSD');

  function renderLogin(message) {
    stopDash();
    const err = h('div', { class: 'form-error hidden' });
    const user = h('input', { class: 'input', name: 'username', autocomplete: 'username', autocapitalize: 'none', spellcheck: 'false', required: true });
    const pass = h('input', { class: 'input', name: 'password', type: 'password', autocomplete: 'current-password', required: true });
    const btn = h('button', { class: 'btn primary', type: 'submit' }, 'Masuk');
    const form = h('form', { onsubmit: async (e) => {
      e.preventDefault(); btn.disabled = true; err.classList.add('hidden');
      try {
        const r = await api('POST', '/api/login', { username: user.value, password: pass.value });
        setMe({ user: r.user, csrf: r.csrf, multiUser: state.multiUser });
        const me = await api('GET', '/api/me'); setMe(me);
        route();
      } catch (x) { err.textContent = x.message; err.classList.remove('hidden'); pass.value = ''; pass.focus(); }
      finally { btn.disabled = false; }
    } },
      message && h('div', { class: 'form-note' }, message), err,
      h('div', { class: 'field' }, h('label', null, 'Nama pengguna'), user),
      h('div', { class: 'field' }, h('label', null, 'Kata sandi'), pass), btn);
    clear($app).append(h('div', { class: 'auth' }, h('div', { class: 'auth-card' }, brand(),
      h('h1', null, 'Masuk'), h('p', { class: 'lead' }, 'Penyimpanan pribadi Anda di Xiaomi Pad 6.'), form)));
    setTimeout(() => user.focus(), 30);
  }

  function renderSetup() {
    const err = h('div', { class: 'form-error hidden' });
    const code = h('input', { class: 'input mono', inputmode: 'numeric', autocomplete: 'off', maxlength: 8, required: true });
    const user = h('input', { class: 'input', autocomplete: 'username', autocapitalize: 'none', required: true });
    const pass = h('input', { class: 'input', type: 'password', autocomplete: 'new-password', required: true, minlength: 10 });
    const pass2 = h('input', { class: 'input', type: 'password', autocomplete: 'new-password', required: true });
    const btn = h('button', { class: 'btn primary', type: 'submit' }, 'Buat administrator');
    const form = h('form', { onsubmit: async (e) => {
      e.preventDefault(); err.classList.add('hidden');
      if (pass.value !== pass2.value) { err.textContent = 'Konfirmasi kata sandi tidak sama.'; err.classList.remove('hidden'); return; }
      btn.disabled = true;
      try {
        await api('POST', '/api/setup', { code: code.value, username: user.value, password: pass.value });
        toast('Administrator dibuat. Silakan masuk.', 'ok');
        renderLogin();
      } catch (x) { err.textContent = x.message; err.classList.remove('hidden'); }
      finally { btn.disabled = false; }
    } }, err,
      h('div', { class: 'field' }, h('label', null, 'Kode pengaturan'), code, h('div', { class: 'hint' }, 'Kode 8 digit yang tampil di aplikasi CloudSSD pada tablet.')),
      h('div', { class: 'field' }, h('label', null, 'Nama pengguna administrator'), user, h('div', { class: 'hint' }, '3–32 karakter: huruf kecil, angka, titik, _ atau -')),
      h('div', { class: 'field' }, h('label', null, 'Kata sandi'), pass, h('div', { class: 'hint' }, 'Minimal 10 karakter. Tidak ada kata sandi bawaan.')),
      h('div', { class: 'field' }, h('label', null, 'Ulangi kata sandi'), pass2), btn);
    clear($app).append(h('div', { class: 'auth' }, h('div', { class: 'auth-card' }, brand(),
      h('h1', null, 'Pengaturan pertama'), h('p', { class: 'lead' }, 'Buat akun administrator untuk CloudSSD.'), form)));
  }

  async function logout() {
    try { await api('POST', '/api/logout'); } catch (_) { }
    state.user = null; state.csrf = null;
    renderLogin('Anda telah keluar.');
  }

  // ------------------------------------------------------------------ shell & routing
  const NAV = () => [
    { id: 'dashboard', label: 'Dashboard', icon: 'home' },
    { id: 'files', label: 'File', icon: 'folder' },
    { id: 'shares', label: 'Berbagi', icon: 'share' },
    state.user.role === 'admin' && { id: 'users', label: 'Pengguna', icon: 'users' },
    { id: 'settings', label: 'Pengaturan', icon: 'settings' },
  ].filter(Boolean);

  function go(view, params) {
    const hash = '#/' + view + (params ? '?' + q(params) : '');
    if (location.hash === hash) route(); else location.hash = hash;
  }
  window.addEventListener('hashchange', () => { if (state.user) route(); });

  function route() {
    if (!state.user) return renderLogin();
    const m = location.hash.match(/^#\/(\w+)(?:\?(.*))?$/);
    const view = m ? m[1] : 'dashboard';
    const params = new URLSearchParams(m && m[2] || '');
    state.view = NAV().some((n) => n.id === view) ? view : 'dashboard';
    stopDash();
    const main = shell();
    if (state.view === 'dashboard') viewDashboard(main);
    else if (state.view === 'files') { state.path = params.get('path') || '/'; viewFiles(main); }
    else if (state.view === 'shares') viewShares(main);
    else if (state.view === 'users') viewUsers(main);
    else if (state.view === 'settings') viewSettings(main);
  }

  function shell() {
    const nav = NAV();
    const main = h('main', { class: 'main' });
    const navBtn = (n, cls) => h('button', { class: cls + (state.view === n.id ? ' active' : ''), onclick: () => go(n.id) }, icon(n.icon), h('span', null, n.label));
    const bottom = h('nav', { class: 'bottom-nav' }, nav.map((n) => navBtn(n, '')));
    bottom.style.setProperty('--n', nav.length);
    clear($app).append(h('div', { class: 'shell' },
      h('aside', { class: 'sidebar' }, brand(), nav.map((n) => navBtn(n, 'nav-item')), h('div', { class: 'spacer' }),
        h('div', { class: 'user-chip' }, h('div', { class: 'avatar' }, state.user.username[0]),
          h('div', { class: 'grow' }, h('div', { class: 'ellipsis' }, state.user.username), h('div', { class: 'muted', style: null }, state.user.role === 'admin' ? 'Administrator' : 'Pengguna')),
          h('button', { class: 'btn ghost icon sm', title: 'Keluar', 'aria-label': 'Keluar', onclick: logout }, icon('logout', 'sm')))),
      h('div', { class: 'grow' },
        h('header', { class: 'topbar' }, brand(), h('div', { class: 'grow' }),
          h('button', { class: 'btn ghost icon', 'aria-label': 'Keluar', title: 'Keluar', onclick: logout }, icon('logout'))),
        main),
      bottom));
    return main;
  }

  // ------------------------------------------------------------------ dashboard
  function stopDash() { if (dashTimer) { clearInterval(dashTimer); dashTimer = null; } }

  function viewDashboard(main) {
    const body = h('div');
    main.append(h('div', { class: 'page-head' }, h('div', { class: 'grow' }, h('h1', null, 'Dashboard'), h('div', { class: 'sub' }, 'Halo, ' + state.user.username)),
      h('button', { class: 'btn', onclick: () => go('settings') }, icon('settings', 'sm'), 'Pengaturan')), body);
    const load = async () => {
      try { renderDash(body, await api('GET', '/api/dashboard')); }
      catch (e) { clear(body).append(h('div', { class: 'alert bad' }, icon('alert'), h('div', null, h('b', null, 'Server tidak merespons. '), e.message))); }
    };
    body.append(h('div', { class: 'muted' }, 'Memuat…'));
    load();
    dashTimer = setInterval(() => { if (!document.hidden && state.view === 'dashboard') load(); }, 10000);
  }

  function renderDash(body, d) {
    clear(body);
    const st = d.storage, srv = d.server;
    if (!st.configured) body.append(h('div', { class: 'alert bad' }, icon('alert'), h('div', null, h('b', null, 'Lokasi penyimpanan belum dipilih. '), 'Pilih folder SSD di aplikasi CloudSSD pada tablet.')));
    else if (!st.available) body.append(h('div', { class: 'alert bad' }, icon('alert'), h('div', null, h('b', null, 'SSD tidak terhubung. '), 'File tidak dapat dibaca atau disimpan sampai SSD disambungkan kembali.')));
    else if (st.lowSpace) body.append(h('div', { class: 'alert warn' }, icon('alert'), h('div', null, h('b', null, 'Ruang SSD hampir penuh. '), 'Tersisa ' + fmtBytes(st.freeBytes) + '.')));

    const pct = st.totalBytes ? Math.round(st.usedBytes / st.totalBytes * 1000) / 10 : 0;
    const meter = h('div', { class: 'meter' + (pct > 95 ? ' bad' : pct > 85 ? ' warn' : '') }, h('div'));
    meter.firstChild.style.width = pct + '%';
    const s = d.stats || {};
    const isAdmin = state.user.role === 'admin';
    const quota = d.quota && d.quota.quotaBytes > 0 ? d.quota : null;

    const cards = h('div', { class: 'grid cards stats' },
      h('div', { class: 'card' }, h('div', { class: 'card-ic' }, icon('server')), h('h3', null, 'Server'),
        h('div', { class: 'big status' }, h('span', { class: 'dot ok' }), 'Berjalan'),
        h('div', { class: 'muted' }, 'Aktif ' + fmtDuration(Date.now() - srv.startedAt) + ' · ' + srv.connections + ' koneksi')),
      h('div', { class: 'card' }, h('div', { class: 'card-ic' }, icon('drive')), h('h3', null, 'SSD'),
        h('div', { class: 'big status' }, h('span', { class: 'dot ' + (st.available ? (st.lowSpace ? 'warn' : 'ok') : 'bad') }), st.available ? 'Terhubung' : 'Terputus'),
        h('div', { class: 'muted ellipsis' }, st.description || (st.available ? 'Penyimpanan siap' : 'Tidak tersedia'))),
      h('div', { class: 'card' }, h('div', { class: 'card-ic' }, icon('file')), h('h3', null, isAdmin ? 'Jumlah file' : 'File Anda'),
        h('div', { class: 'big' }, s.files == null ? '…' : s.files.toLocaleString('id-ID')),
        h('div', { class: 'muted' }, s.files == null ? (d.scanning ? 'Sedang menghitung…' : 'Belum dihitung') : 'Dihitung ' + fmtAgo(s.computedAt))),
      h('div', { class: 'card' }, h('div', { class: 'card-ic' }, icon('folder')), h('h3', null, 'Jumlah folder'),
        h('div', { class: 'big' }, s.folders == null ? '…' : s.folders.toLocaleString('id-ID')),
        h('div', { class: 'muted' }, s.bytes == null ? '' : fmtBytes(s.bytes) + ' data')),
    );

    const capCard = h('div', { class: 'card span-2' }, h('h3', null, 'Kapasitas penyimpanan'),
      st.totalBytes ? [
        h('div', { class: 'row' }, h('div', { class: 'big grow' }, fmtBytes(st.usedBytes), h('span', { class: 'muted' }, ' / ' + fmtBytes(st.totalBytes))), h('span', { class: 'tag' }, pct + '%')),
        meter,
        h('div', { class: 'row muted' }, h('span', { class: 'grow' }, 'Terpakai ' + fmtBytes(st.usedBytes)), h('span', null, 'Tersisa ' + fmtBytes(st.freeBytes))),
        h('div', { class: 'muted', style: null }, h('small', null, 'Kapasitas aktual volume yang terhubung, dilaporkan sistem Android.')),
      ] : h('p', { class: 'muted' }, st.available ? 'Sistem tidak menyediakan informasi kapasitas untuk lokasi ini.' : 'Kapasitas tidak tersedia saat SSD terputus.'),
      quota && [h('div', { style: null, class: 'muted' }, h('br'), 'Kuota Anda: ' + fmtBytes(quota.usedBytes) + ' dari ' + fmtBytes(quota.quotaBytes))]);

    const net = h('div', { class: 'card span-2' }, h('h3', null, 'Koneksi & alamat akses'),
      h('dl', { class: 'kv' },
        h('dt', null, 'Perangkat ini'), h('dd', null, srv.clientIp + ' ', h('span', { class: 'tag ' + (srv.clientNetwork === 'vpn' ? 'blue' : '') }, netLabel(srv.clientNetwork))),
        h('dt', null, 'Akses LAN'), h('dd', null, srv.lanAccess ? 'Aktif (Wi-Fi yang sama)' : 'Nonaktif (hanya tablet)'),
        h('dt', null, 'Akses VPN'), h('dd', null, srv.vpnAccess ? 'Diizinkan (Tailscale)' : 'Nonaktif')),
      srv.addresses.length ? srv.addresses.map((a) => {
        const url = 'http://' + a.ip + ':' + srv.port;
        return h('div', { class: 'addr' }, icon(a.network === 'vpn' ? 'globe' : 'wifi', 'sm'), h('span', { class: 'mono grow ellipsis' }, url),
          h('span', { class: 'tag' + (a.network === 'vpn' ? ' blue' : '') }, a.network === 'vpn' ? 'VPN' : a.iface),
          h('button', { class: 'btn ghost icon sm', title: 'Salin', onclick: () => copyText(url) }, icon('copy', 'sm')));
      }) : h('p', { class: 'muted' }, 'Tidak ada alamat jaringan lokal terdeteksi.'));

    const r = d.remote;
    const remote = h('div', { class: 'card' }, h('div', { class: 'card-ic' }, icon('globe')), h('h3', null, 'Akses jarak jauh'),
      h('div', { class: 'status' }, h('span', { class: 'dot ' + (r.lastVerifiedAt ? 'ok' : r.vpnAccess ? 'warn' : '') }),
        r.lastVerifiedAt ? 'Terverifikasi' : r.vpnAccess ? 'Belum teruji' : 'Nonaktif'),
      h('p', { class: 'muted' }, r.lastVerifiedAt ? 'Akses terakhir via VPN dari ' + r.lastVerifiedIp + ', ' + fmtAgo(r.lastVerifiedAt) + '.'
        : r.vpnAccess ? (r.vpnInterfaceDetected ? 'Antarmuka Tailscale terdeteksi. Status menjadi “terverifikasi” setelah ada perangkat yang benar-benar membuka CloudSSD lewat VPN.' : 'VPN diizinkan, tetapi antarmuka Tailscale tidak terdeteksi di tablet.')
          : 'Server tidak dapat dibuka dari luar rumah. Lihat panduan Tailscale di dokumentasi.'));

    const act = h('div', { class: 'card span-2' }, h('h3', null, 'Aktivitas terbaru'),
      d.activity.length ? h('ul', { class: 'list' }, d.activity.map((a) => h('li', null, icon(actIcon(a.action), 'sm'),
        h('div', { class: 'grow' }, h('div', null, a.detail), h('div', { class: 'muted' }, (a.user ? a.user + ' · ' : '') + fmtAgo(a.time) + (a.ip ? ' · ' + a.ip : '')))))) :
        h('p', { class: 'muted' }, 'Belum ada aktivitas.'));

    const ctl = h('div', { class: 'card' }, h('div', { class: 'card-ic' }, icon('settings')), h('h3', null, 'Kontrol server'),
      h('p', { class: 'muted' }, 'Mulai/hentikan server, pemilihan SSD, akses jaringan dan HTTPS diatur dari aplikasi CloudSSD di tablet (butuh akses fisik).'),
      h('button', { class: 'btn sm', onclick: () => go('settings') }, 'Pengaturan akun'));

    body.append(cards, h('div', { class: 'grid cards', style: null }, capCard, net, remote, ctl, act));
    body.lastChild.style.marginTop = '16px';
  }
  const netLabel = (n) => ({ loopback: 'tablet', lan: 'LAN', vpn: 'VPN', public: 'publik' })[n] || n;
  const actIcon = (a) => ({ upload: 'upload', delete: 'trash', mkdir: 'plus-folder', rename: 'edit', move: 'move', login: 'lock', logout: 'logout',
    share: 'share', share_revoke: 'share', server: 'server', login_failed: 'alert', error: 'alert' })[a] || 'activity';

  async function copyText(t) {
    try { await navigator.clipboard.writeText(t); toast('Disalin', 'ok'); }
    catch (_) {
      // Clipboard API butuh HTTPS; fallback untuk HTTP lokal.
      const ta = h('textarea', null, t); document.body.append(ta); ta.select();
      try { document.execCommand('copy'); toast('Disalin', 'ok'); } catch (e) { toast('Salin manual: ' + t); }
      ta.remove();
    }
  }

  // ------------------------------------------------------------------ file manager
  let filesMain = null;
  function viewFiles(main) {
    filesMain = main;
    state.selected.clear(); state.searchResults = null; state.searchQuery = '';
    renderFilesFrame();
    loadDir();
  }

  async function loadDir() {
    state.loading = true; renderList();
    try {
      const r = await api('GET', '/api/files?' + q({ path: state.path }));
      state.items = r.items; state.path = r.path;
    } catch (e) {
      state.items = []; state.listError = e;
    }
    state.loading = false;
    renderFilesFrame();
  }

  function renderFilesFrame() {
    const main = filesMain;
    if (!main || !main.isConnected) return;
    clear(main);
    const canWrite = state.user.canWrite || state.user.role === 'admin';
    const fileInput = h('input', { type: 'file', multiple: true, class: 'hidden', onchange: (e) => { Up.add([...e.target.files], state.path); e.target.value = ''; } });
    const searchInput = h('input', { class: 'input', type: 'search', placeholder: 'Cari di folder ini…', value: state.searchQuery,
      onkeydown: (e) => { if (e.key === 'Enter') doSearch(searchInput.value); } });
    searchInput.addEventListener('search', () => { if (!searchInput.value) { state.searchResults = null; state.searchQuery = ''; renderFilesFrame(); } });
    main.append(
      h('div', { class: 'page-head' }, h('h1', { class: 'grow' }, 'File'),
        canWrite && h('button', { class: 'btn', onclick: newFolder }, icon('plus-folder', 'sm'), h('span', null, 'Folder baru')),
        canWrite && h('button', { class: 'btn primary', onclick: () => fileInput.click() }, icon('upload', 'sm'), h('span', null, 'Unggah')), fileInput),
      crumbs(),
      h('div', { class: 'toolbar' },
        h('div', { class: 'search' }, icon('search', 'sm'), searchInput),
        h('select', { class: 'input', style: null, 'aria-label': 'Urutkan', onchange: (e) => { const [k, d] = e.target.value.split(':'); state.sort = { key: k, dir: +d }; renderList(); } },
          [['name:1', 'Nama A–Z'], ['name:-1', 'Nama Z–A'], ['size:-1', 'Terbesar'], ['size:1', 'Terkecil'], ['modified:-1', 'Terbaru'], ['modified:1', 'Terlama']]
            .map(([v, l]) => h('option', { value: v, selected: v === state.sort.key + ':' + state.sort.dir }, l))),
        h('button', { class: 'btn icon', title: 'Muat ulang', 'aria-label': 'Muat ulang', onclick: loadDir }, icon('refresh', 'sm'))),
      h('div', { id: 'selbar-slot' }),
      h('div', { id: 'list-slot' }));
    main.querySelector('select').style.width = 'auto';
    renderList();
  }

  function crumbs() {
    const parts = state.path.split('/').filter(Boolean);
    const el = h('div', { class: 'crumbs' }, h('button', { onclick: () => go('files', { path: '/' }) }, 'Beranda'));
    let acc = '';
    for (const p of parts) {
      acc += '/' + p;
      const target = acc;
      el.append(icon('chevron'), h('button', { onclick: () => go('files', { path: target }) }, p));
    }
    if (state.searchResults) el.append(icon('chevron'), h('button', null, 'Hasil pencarian “' + state.searchQuery + '”'));
    return el;
  }

  function sorted(items) {
    const { key, dir } = state.sort;
    return [...items].sort((a, b) => {
      if (a.dir !== b.dir) return a.dir ? -1 : 1;
      let c = key === 'name' ? a.name.localeCompare(b.name, 'id', { numeric: true, sensitivity: 'base' }) : (a[key] - b[key]);
      if (c === 0) c = a.name.localeCompare(b.name);
      return c * dir;
    });
  }

  function renderList() {
    const slot = document.getElementById('list-slot');
    if (!slot) return;
    renderSelbar();
    clear(slot);
    if (state.loading) { slot.append(h('div', { class: 'files' }, h('div', { class: 'empty' }, 'Memuat…'))); return; }
    if (state.listError) {
      const e = state.listError; state.listError = null;
      slot.append(h('div', { class: 'alert bad' }, icon('alert'), h('div', null, h('b', null, e.code === 'storage_unavailable' ? 'SSD tidak tersedia. ' : 'Gagal memuat. '), e.message)));
      if (e.status === 404 && state.path !== '/') slot.append(h('button', { class: 'btn', onclick: () => go('files', { path: '/' }) }, 'Kembali ke beranda'));
      return;
    }
    const items = sorted(state.searchResults || state.items);
    const sortBtn = (key, label) => h('button', { class: state.sort.key === key ? 'on' : '', onclick: () => { state.sort = { key, dir: state.sort.key === key ? -state.sort.dir : (key === 'name' ? 1 : -1) }; renderFilesFrame(); } },
      label, state.sort.key === key ? (state.sort.dir > 0 ? ' ↑' : ' ↓') : '');
    const allSel = items.length > 0 && items.every((i) => state.selected.has(i.path));
    const head = h('div', { class: 'f-head' },
      h('input', { type: 'checkbox', 'aria-label': 'Pilih semua', checked: allSel, onchange: (e) => { items.forEach((i) => e.target.checked ? state.selected.add(i.path) : state.selected.delete(i.path)); renderList(); } }),
      sortBtn('name', 'Nama'), sortBtn('size', 'Ukuran'), sortBtn('modified', 'Diubah'), h('span'));
    const box = h('div', { class: 'files' }, head);
    if (!items.length) {
      box.append(h('div', { class: 'empty' }, icon(state.searchResults ? 'search' : 'folder'), h('div', null, state.searchResults ? 'Tidak ada hasil.' : 'Folder ini kosong.'),
        !state.searchResults && (state.user.canWrite || state.user.role === 'admin') && h('div', { class: 'muted' }, 'Seret file ke sini atau klik “Unggah”.')));
    }
    for (const it of items) box.append(fileRow(it));
    slot.append(box);
  }

  function fileRow(it) {
    const sel = state.selected.has(it.path);
    const open = () => it.dir ? go('files', { path: it.path }) : preview(it);
    const typeLabel = it.dir ? 'Folder' : (it.ext ? it.ext.toUpperCase() : 'File');
    const row = h('div', { class: 'f-row' + (sel ? ' selected' : ''), ondblclick: open },
      h('input', { type: 'checkbox', 'aria-label': 'Pilih ' + it.name, checked: sel, onchange: (e) => { e.target.checked ? state.selected.add(it.path) : state.selected.delete(it.path); renderList(); } }),
      h('div', { class: 'f-name' }, h('div', { class: 'fi ' + it.kind }, icon(kindIcon(it.kind))),
        h('div', { class: 'nm' }, h('div', { class: 't', title: it.name }, h('button', { onclick: open }, it.name)),
          h('div', { class: 's' }, (it.dir ? 'Folder' : fmtBytes(it.size) + ' · ' + typeLabel) + ' · ' + fmtDate(it.modified)),
          state.searchResults && h('div', { class: 's', style: null }, parentPath(it.path)))),
      h('div', { class: 'f-size' }, it.dir ? '—' : fmtBytes(it.size), h('div', { class: 'muted' }, h('small', null, typeLabel))),
      h('div', { class: 'f-date' }, fmtDate(it.modified)),
      h('button', { class: 'btn ghost icon sm', 'aria-label': 'Menu ' + it.name, onclick: (e) => rowMenu(e.currentTarget, it) }, icon('more', 'sm')));
    if (state.searchResults) row.querySelectorAll('.s')[1].style.display = 'block';
    return row;
  }
  const kindIcon = (k) => ({ folder: 'folder', image: 'image', video: 'video', audio: 'audio', pdf: 'pdf', archive: 'archive', text: 'text' })[k] || 'file';

  function rowMenu(anchor, it) {
    const w = state.user.canWrite || state.user.role === 'admin';
    popupMenu(anchor, [
      it.dir ? { icon: 'folder', label: 'Buka', onClick: () => go('files', { path: it.path }) } : { icon: 'image', label: 'Pratinjau', onClick: () => preview(it) },
      !it.dir && { icon: 'download', label: 'Unduh', onClick: () => downloadFile(it) },
      w && { icon: 'edit', label: 'Ganti nama', onClick: () => renameItem(it) },
      w && { icon: 'move', label: 'Pindahkan', onClick: () => moveItems([it.path]) },
      !it.dir && { icon: 'share', label: 'Bagikan tautan', onClick: () => shareDialog(it) },
      w && { icon: 'trash', label: 'Hapus', danger: true, onClick: () => deleteItems([it.path]) },
    ]);
  }

  function renderSelbar() {
    const slot = document.getElementById('selbar-slot');
    if (!slot) return;
    clear(slot);
    if (!state.selected.size) return;
    const w = state.user.canWrite || state.user.role === 'admin';
    const paths = [...state.selected];
    const files = (state.searchResults || state.items).filter((i) => state.selected.has(i.path) && !i.dir);
    slot.append(h('div', { class: 'selbar' }, h('b', { class: 'grow' }, paths.length + ' dipilih'),
      files.length > 0 && h('button', { class: 'btn sm', onclick: () => downloadMany(files) }, icon('download', 'sm'), 'Unduh'),
      w && h('button', { class: 'btn sm', onclick: () => moveItems(paths) }, icon('move', 'sm'), 'Pindahkan'),
      w && h('button', { class: 'btn sm danger', onclick: () => deleteItems(paths) }, icon('trash', 'sm'), 'Hapus'),
      h('button', { class: 'btn sm ghost', onclick: () => { state.selected.clear(); renderList(); } }, 'Batal')));
  }

  async function doSearch(qs) {
    qs = qs.trim();
    if (!qs) { state.searchResults = null; state.searchQuery = ''; return renderFilesFrame(); }
    state.searchQuery = qs; state.loading = true; renderList();
    try {
      const r = await api('GET', '/api/files/search?' + q({ path: state.path, q: qs }));
      state.searchResults = r.items;
      if (r.truncated) toast('Hasil dibatasi. Persempit kata kunci atau folder.');
    } catch (e) { toast(e.message, 'err'); }
    state.loading = false; state.selected.clear();
    renderFilesFrame();
  }

  async function newFolder() {
    const name = await promptDialog('Folder baru', 'Nama folder', '', 'Buat');
    if (!name) return;
    try { await api('POST', '/api/files/mkdir', { path: state.path, name }); toast('Folder dibuat', 'ok'); loadDir(); }
    catch (e) { toast(e.message, 'err'); }
  }

  async function renameItem(it) {
    const name = await promptDialog('Ganti nama', 'Nama baru', it.name, 'Ganti nama');
    if (!name || name === it.name) return;
    try { await api('POST', '/api/files/rename', { path: it.path, name }); toast('Nama diganti', 'ok'); state.selected.clear(); loadDir(); }
    catch (e) { toast(e.message, 'err'); }
  }

  async function deleteItems(paths) {
    const names = paths.map((p) => p.split('/').pop());
    const ok = await confirmDialog('Hapus ' + paths.length + ' item?',
      'Item berikut akan dihapus permanen dari SSD dan tidak dapat dikembalikan: ' + names.slice(0, 5).join(', ') + (names.length > 5 ? ', dan ' + (names.length - 5) + ' lainnya' : '') + '. Folder akan dihapus beserta seluruh isinya.',
      'Hapus permanen', true);
    if (!ok) return;
    try {
      const r = await api('POST', '/api/files/delete', { paths });
      if (r.failed.length) toast('Gagal menghapus ' + r.failed.length + ' item: ' + r.failed[0].error, 'err');
      if (r.deleted.length) toast(r.deleted.length + ' item dihapus', 'ok');
    } catch (e) { toast(e.message, 'err'); }
    state.selected.clear(); state.searchResults = null; loadDir();
  }

  function moveItems(paths) {
    let cur = state.path;
    const list = h('div', { class: 'folder-pick' });
    const label = h('div', { class: 'mono muted', style: null });
    const load = async () => {
      clear(list).append(h('div', { class: 'empty' }, 'Memuat…'));
      label.textContent = 'Tujuan: ' + cur;
      try {
        const r = await api('GET', '/api/files?' + q({ path: cur }));
        clear(list);
        if (cur !== '/') list.append(h('button', { onclick: () => { cur = parentPath(cur); load(); } }, icon('chevron'), '.. (ke atas)'));
        r.items.filter((i) => i.dir && !paths.includes(i.path)).sort((a, b) => a.name.localeCompare(b.name))
          .forEach((i) => list.append(h('button', { onclick: () => { cur = i.path; load(); } }, icon('folder', 'sm'), i.name)));
        if (list.children.length === 0) list.append(h('div', { class: 'empty' }, 'Tidak ada subfolder'));
      } catch (e) { clear(list).append(h('div', { class: 'empty' }, e.message)); }
    };
    load();
    modal({ title: 'Pindahkan ' + paths.length + ' item', body: h('div', null, label, h('br'), list), actions: [
      { label: 'Batal' },
      { label: 'Pindahkan ke sini', kind: 'primary', onClick: async () => {
        const r = await api('POST', '/api/files/move', { paths, dest: cur });
        if (r.failed.length) toast('Gagal: ' + r.failed[0].error, 'err'); else toast(r.moved.length + ' item dipindahkan', 'ok');
        state.selected.clear(); loadDir();
      } }] });
  }

  // ------------------------------------------------------------------ pratinjau & unduh
  async function preview(it) {
    const url = fileUrl(it.path, true);
    let content;
    if (it.kind === 'image') content = h('img', { src: url, alt: it.name });
    else if (it.kind === 'video') content = h('video', { src: url, controls: true, preload: 'metadata', playsinline: true });
    else if (it.kind === 'audio') content = h('audio', { src: url, controls: true, preload: 'metadata' });
    else if (it.kind === 'pdf') { window.open(url, '_blank', 'noopener'); return; }
    else if (it.kind === 'text' && it.mime === 'text/plain') {
      content = h('pre', null, 'Memuat…');
      fetch(url, { headers: { Range: 'bytes=0-524287' }, credentials: 'same-origin' }).then((r) => r.text())
        .then((t) => { content.textContent = t + (it.size > 524288 ? '\n\n… (hanya 512 kB pertama yang ditampilkan)' : ''); })
        .catch(() => { content.textContent = 'Gagal memuat.'; });
    } else content = h('div', { class: 'empty' }, icon(kindIcon(it.kind)), h('div', null, 'Pratinjau tidak tersedia untuk jenis file ini.'));
    if (content.tagName === 'VIDEO') content.addEventListener('error', () => toast('Browser ini tidak dapat memutar format video tersebut. Unduh untuk memutar di aplikasi lain.', 'err'));
    modal({ title: it.name, wide: true, body: h('div', null, h('div', { class: 'preview' }, content),
      h('p', { class: 'muted', style: null }, fmtBytes(it.size) + ' · ' + (it.ext ? it.ext.toUpperCase() : 'File') + ' · ' + fmtDate(it.modified))),
      onClose: () => { if (content.pause) content.pause(); content.removeAttribute('src'); },
      actions: [{ label: 'Tutup' }, { label: 'Unduh', kind: 'primary', onClick: () => { downloadFile(it); } }] });
  }

  function nativeDownload(it) {
    const a = h('a', { href: fileUrl(it.path, false), download: it.name });
    document.body.append(a); a.click(); a.remove();
  }

  /** Unduh dengan progres: memakai File System Access API (Chrome/Edge desktop) agar data di-stream ke disk, bukan ke RAM. */
  async function downloadFile(it) {
    if (!window.showSaveFilePicker || it.size < 20e6) { nativeDownload(it); toast('Unduhan dimulai — progres tampil di pengelola unduhan browser.'); return; }
    let handle;
    try { handle = await window.showSaveFilePicker({ suggestedName: it.name }); } catch (e) { if (e.name !== 'AbortError') nativeDownload(it); return; }
    const item = Up.addDownload(it);
    const writable = await handle.createWritable();
    let received = 0, attempts = 0;
    while (received < it.size) {
      try {
        const res = await fetch(fileUrl(it.path, false), { credentials: 'same-origin', headers: received ? { Range: 'bytes=' + received + '-' } : {} });
        if (!res.ok) throw new ApiErr(res.status, await res.json().catch(() => ({ error: 'HTTP ' + res.status })));
        if (received && res.status !== 206) throw new Error('Server tidak mendukung melanjutkan unduhan');
        const reader = res.body.getReader();
        for (; ;) {
          const { done, value } = await reader.read();
          if (done) break;
          await writable.write({ type: 'write', position: received, data: value });
          received += value.length; attempts = 0;
          Up.progress(item, received);
        }
        if (received < it.size) throw new Error('Koneksi terputus');
      } catch (e) {
        if (e instanceof ApiErr || ++attempts > 5) { await writable.abort(); Up.fail(item, e.message); return; }
        Up.note(item, 'Koneksi terputus, mencoba lagi… (' + attempts + '/5)');
        await sleep(2000 * attempts);
      }
    }
    await writable.close();
    Up.done(item, 'Tersimpan');
  }

  async function downloadMany(files) {
    for (const f of files) { nativeDownload(f); await sleep(600); }
    toast(files.length + ' unduhan dimulai. Jika browser bertanya, izinkan beberapa unduhan.');
  }
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  // ------------------------------------------------------------------ berbagi
  function shareDialog(it) {
    const exp = h('select', { class: 'input' }, [['24', '24 jam'], ['1', '1 jam'], ['168', '7 hari'], ['720', '30 hari'], ['', 'Tanpa batas waktu']].map(([v, l]) => h('option', { value: v }, l)));
    const pw = h('input', { class: 'input', type: 'password', autocomplete: 'new-password', placeholder: 'Opsional, minimal 6 karakter' });
    const dl = h('input', { type: 'checkbox', checked: true });
    modal({ title: 'Bagikan “' + it.name + '”',
      body: h('div', null,
        h('div', { class: 'alert info' }, icon('lock', 'sm'), h('div', null, 'Tautan hanya dapat dibuka dari jaringan yang diizinkan server (Wi-Fi rumah / VPN). Tautan tidak otomatis terbuka ke internet.')),
        h('div', { class: 'field' }, h('label', null, 'Masa berlaku'), exp),
        h('div', { class: 'field' }, h('label', null, 'Kata sandi tautan'), pw),
        h('label', { class: 'check' }, dl, 'Penerima boleh mengunduh (jika tidak dicentang: hanya lihat/pratinjau)')),
      actions: [{ label: 'Batal' }, { label: 'Buat tautan', kind: 'primary', onClick: async () => {
        const s = await api('POST', '/api/shares', { path: it.path, expiresHours: exp.value ? +exp.value : null, password: pw.value || null, allowDownload: dl.checked });
        showShareLink(s);
      } }] });
  }
  function showShareLink(s) {
    const url = location.origin + s.url;
    modal({ title: 'Tautan dibuat', body: h('div', null,
      h('div', { class: 'addr' }, h('span', { class: 'mono grow', style: null }, url)),
      h('p', { class: 'muted', style: null }, (s.expiresAt ? 'Berlaku sampai ' + fmtDate(s.expiresAt) : 'Tanpa batas waktu') + (s.hasPassword ? ' · dilindungi kata sandi' : '') + (s.allowDownload ? '' : ' · hanya lihat')),
      h('p', { class: 'muted' }, 'Catatan: mode “hanya lihat” menyembunyikan tombol unduh, tetapi siapa pun yang dapat melihat file tetap bisa menyimpannya (misalnya lewat tangkapan layar).')),
      actions: [{ label: 'Tutup' }, { label: 'Salin tautan', kind: 'primary', onClick: () => copyText(url) }] });
  }

  async function viewShares(main) {
    const body = h('div', null, h('div', { class: 'muted' }, 'Memuat…'));
    main.append(h('div', { class: 'page-head' }, h('div', { class: 'grow' }, h('h1', null, 'Tautan berbagi'), h('div', { class: 'sub' }, 'Buat tautan dari menu file (⋮ → Bagikan tautan).'))), body);
    try {
      const r = await api('GET', '/api/shares');
      clear(body);
      if (!r.shares.length) { body.append(h('div', { class: 'files' }, h('div', { class: 'empty' }, icon('share'), h('div', null, 'Belum ada tautan berbagi.')))); return; }
      const rows = r.shares.map((s) => h('tr', null,
        h('td', null, h('div', { class: 'ellipsis' }, s.name), h('div', { class: 'muted mono' }, h('small', null, s.path))),
        h('td', null, h('span', { class: 'tag ' + (s.active ? 'green' : 'red') }, s.revoked ? 'Dicabut' : s.active ? 'Aktif' : 'Kedaluwarsa')),
        h('td', { class: 'hide-sm' }, s.expiresAt ? fmtDate(s.expiresAt) : 'Tanpa batas'),
        h('td', { class: 'hide-sm' }, [s.hasPassword && 'Sandi', s.allowDownload ? 'Unduh' : 'Hanya lihat'].filter(Boolean).join(' · ')),
        h('td', { class: 'hide-sm' }, String(s.accessCount)),
        h('td', null, h('div', { class: 'row' },
          s.active && h('button', { class: 'btn sm icon', title: 'Salin tautan', onclick: () => copyText(location.origin + s.url) }, icon('copy', 'sm')),
          s.active && h('button', { class: 'btn sm danger', onclick: async () => {
            if (!await confirmDialog('Cabut tautan?', 'Tautan untuk “' + s.name + '” akan langsung berhenti berfungsi.', 'Cabut', true)) return;
            try { await api('DELETE', '/api/shares/' + encodeURIComponent(s.id)); toast('Tautan dicabut', 'ok'); route(); } catch (e) { toast(e.message, 'err'); }
          } }, 'Cabut')))));
      body.append(h('div', { class: 'table-wrap' }, h('table', { class: 'table' },
        h('thead', null, h('tr', null, h('th', null, 'File'), h('th', null, 'Status'), h('th', { class: 'hide-sm' }, 'Berlaku sampai'), h('th', { class: 'hide-sm' }, 'Izin'), h('th', { class: 'hide-sm' }, 'Dibuka'), h('th'))),
        h('tbody', null, rows))));
    } catch (e) { clear(body).append(h('div', { class: 'alert bad' }, icon('alert'), e.message)); }
  }

  // ------------------------------------------------------------------ pengguna (admin)
  async function viewUsers(main) {
    const body = h('div', null, h('div', { class: 'muted' }, 'Memuat…'));
    main.append(h('div', { class: 'page-head' }, h('div', { class: 'grow' }, h('h1', null, 'Pengguna'), h('div', { class: 'sub' }, 'Kelola akun, izin, folder, dan kuota.')),
      state.multiUser && h('button', { class: 'btn primary', onclick: () => userDialog(null) }, icon('users', 'sm'), 'Tambah pengguna')), body);
    try {
      const [r, s] = await Promise.all([api('GET', '/api/admin/users'), api('GET', '/api/admin/settings')]);
      state.multiUser = s.multiUser;
      clear(body);
      if (!s.multiUser) body.append(h('div', { class: 'alert info' }, icon('lock'), h('div', { class: 'grow' }, h('b', null, 'Mode pribadi aktif. '),
        'Hanya administrator yang dapat masuk. Aktifkan mode multi-pengguna untuk membuat akun lain.'),
        h('button', { class: 'btn sm primary', onclick: async () => { await api('PATCH', '/api/admin/settings', { multiUser: true }); state.multiUser = true; toast('Mode multi-pengguna aktif', 'ok'); route(); } }, 'Aktifkan')));
      const rows = r.users.map((u) => {
        const quota = u.quotaBytes > 0;
        const pct = quota && u.usedBytes != null ? Math.min(100, u.usedBytes / u.quotaBytes * 100) : 0;
        const meter = quota ? h('div', { class: 'meter' + (pct > 95 ? ' bad' : pct > 85 ? ' warn' : '') }, h('div')) : null;
        if (meter) meter.firstChild.style.width = pct + '%';
        return h('tr', null,
          h('td', null, h('div', { class: 'row' }, h('div', { class: 'avatar' }, u.username[0]), h('div', null, h('div', null, u.username), h('div', { class: 'muted' }, h('small', null, u.role === 'admin' ? 'Administrator' : 'Pengguna'))))),
          h('td', null, h('span', { class: 'tag ' + (u.disabled ? 'red' : 'green') }, u.disabled ? 'Nonaktif' : 'Aktif')),
          h('td', { class: 'hide-sm' }, [u.canRead && 'Baca', u.canWrite && 'Tulis'].filter(Boolean).join(' + ') || 'Tidak ada'),
          h('td', { class: 'hide-sm mono' }, u.homeDir),
          h('td', null, h('div', null, (u.usedBytes == null ? (u.scanning ? 'Menghitung…' : '—') : fmtBytes(u.usedBytes)) + (quota ? ' / ' + fmtBytes(u.quotaBytes) : '')), meter),
          h('td', null, h('div', { class: 'row' },
            h('button', { class: 'btn sm icon', title: 'Hitung ulang pemakaian', onclick: async (e) => { e.currentTarget.disabled = true; try { await api('POST', '/api/admin/users/' + u.id + '/usage'); route(); } catch (x) { toast(x.message, 'err'); } } }, icon('refresh', 'sm')),
            h('button', { class: 'btn sm', onclick: () => userDialog(u) }, 'Ubah'))));
      });
      body.append(h('div', { class: 'table-wrap' }, h('table', { class: 'table' },
        h('thead', null, h('tr', null, h('th', null, 'Pengguna'), h('th', null, 'Status'), h('th', { class: 'hide-sm' }, 'Izin'), h('th', { class: 'hide-sm' }, 'Folder'), h('th', null, 'Pemakaian'), h('th'))),
        h('tbody', null, rows))),
        h('p', { class: 'muted' }, h('small', null, 'Pemakaian dihitung dari data aktual di SSD. Kuota adalah batas logis; total kuota boleh melebihi kapasitas fisik SSD, tetapi unggahan tetap ditolak bila ruang SSD habis.')));
    } catch (e) { clear(body).append(h('div', { class: 'alert bad' }, icon('alert'), e.message)); }
  }

  function userDialog(u) {
    const isNew = !u, isAdm = u && u.role === 'admin';
    const name = h('input', { class: 'input', value: u ? u.username : '', disabled: !isNew, autocapitalize: 'none' });
    const pw = h('input', { class: 'input', type: 'password', autocomplete: 'new-password', placeholder: isNew ? 'Minimal 10 karakter' : 'Kosongkan jika tidak diganti' });
    const home = h('input', { class: 'input mono', value: u ? u.homeDir : '', placeholder: '/users/<nama>', disabled: isAdm });
    const quota = h('input', { class: 'input', type: 'number', min: 0, step: 'any', value: u && u.quotaBytes ? +(u.quotaBytes / 1e9).toFixed(2) : '', placeholder: 'Kosong = tanpa batas', disabled: isAdm });
    const rd = h('input', { type: 'checkbox', checked: u ? u.canRead : true, disabled: isAdm });
    const wr = h('input', { type: 'checkbox', checked: u ? u.canWrite : true, disabled: isAdm });
    const dis = h('input', { type: 'checkbox', checked: u ? u.disabled : false });
    modal({ title: isNew ? 'Tambah pengguna' : 'Ubah ' + u.username, body: h('div', null,
      h('div', { class: 'field' }, h('label', null, 'Nama pengguna'), name),
      h('div', { class: 'field' }, h('label', null, isNew ? 'Kata sandi' : 'Setel ulang kata sandi'), pw),
      h('div', { class: 'field' }, h('label', null, 'Folder pengguna'), home, h('div', { class: 'hint' }, isAdm ? 'Administrator selalu mengakses seluruh SSD.' : 'Pengguna hanya dapat mengakses folder ini. Dibuat otomatis bila belum ada.')),
      h('div', { class: 'field' }, h('label', null, 'Kuota (GB)'), quota),
      h('label', { class: 'check' }, rd, 'Izin baca'), h('label', { class: 'check' }, wr, 'Izin tulis (unggah, ubah, hapus)'),
      !isNew && h('label', { class: 'check' }, dis, 'Nonaktifkan akun (semua sesinya langsung dikeluarkan)')),
      actions: [{ label: 'Batal' }, { label: 'Simpan', kind: 'primary', onClick: async () => {
        const qb = quota.value === '' ? 0 : Math.round(parseFloat(quota.value) * 1e9);
        if (isNew) await api('POST', '/api/admin/users', { username: name.value, password: pw.value, homeDir: home.value || null, quotaBytes: qb, canRead: rd.checked, canWrite: wr.checked });
        else {
          const body = { disabled: dis.checked };
          if (!isAdm) Object.assign(body, { homeDir: home.value, quotaBytes: qb, canRead: rd.checked, canWrite: wr.checked });
          if (pw.value) body.password = pw.value;
          await api('PATCH', '/api/admin/users/' + u.id, body);
        }
        toast('Tersimpan', 'ok'); route();
      } }] });
  }

  // ------------------------------------------------------------------ pengaturan
  async function viewSettings(main) {
    main.append(h('div', { class: 'page-head' }, h('h1', { class: 'grow' }, 'Pengaturan')));
    const cur = h('input', { class: 'input', type: 'password', autocomplete: 'current-password' });
    const n1 = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
    const n2 = h('input', { class: 'input', type: 'password', autocomplete: 'new-password' });
    const pwCard = h('div', { class: 'card' }, h('h2', { class: 'section-title' }, 'Ganti kata sandi'),
      h('form', { onsubmit: async (e) => {
        e.preventDefault();
        if (n1.value !== n2.value) return toast('Konfirmasi kata sandi tidak sama', 'err');
        try { const r = await api('POST', '/api/me/password', { current: cur.value, newPassword: n1.value }); state.csrf = r.csrf; cur.value = n1.value = n2.value = ''; toast('Kata sandi diganti. Perangkat lain telah dikeluarkan.', 'ok'); }
        catch (x) { toast(x.message, 'err'); }
      } }, h('div', { class: 'field' }, h('label', null, 'Kata sandi saat ini'), cur),
        h('div', { class: 'field' }, h('label', null, 'Kata sandi baru'), n1, h('div', { class: 'hint' }, 'Minimal 10 karakter.')),
        h('div', { class: 'field' }, h('label', null, 'Ulangi kata sandi baru'), n2),
        h('button', { class: 'btn primary', type: 'submit' }, 'Simpan kata sandi')));
    const sessCard = h('div', { class: 'card' }, h('h2', { class: 'section-title' }, 'Sesi login'), h('div', { class: 'muted' }, 'Memuat…'));
    const grid = h('div', { class: 'grid', style: null }, pwCard, sessCard);
    grid.style.gridTemplateColumns = 'repeat(auto-fit, minmax(300px, 1fr))';
    main.append(grid);
    loadSessions(sessCard);

    if (state.user.role === 'admin') {
      const adminBox = h('div', { class: 'card' }, h('div', { class: 'muted' }, 'Memuat…'));
      const logBox = h('div', { class: 'card' }, h('div', { class: 'muted' }, 'Memuat…'));
      adminBox.style.marginTop = logBox.style.marginTop = '16px';
      main.append(adminBox, logBox);
      try {
        const s = await api('GET', '/api/admin/settings');
        const mu = h('input', { type: 'checkbox', checked: s.multiUser });
        const low = h('input', { class: 'input', type: 'number', min: 0, step: 1, value: Math.round(s.lowSpaceWarnBytes / 1e9) });
        const hosts = h('input', { class: 'input mono', value: s.extraHosts.join(', '), placeholder: 'mis. pad6.tail1234.ts.net' });
        low.style.maxWidth = '140px';
        clear(adminBox).append(h('h2', { class: 'section-title' }, 'Server (administrator)'),
          h('div', { class: 'setting' }, h('div', { class: 'grow' }, h('div', null, 'Mode multi-pengguna'), h('div', { class: 'd' }, 'Nonaktif = mode pribadi, hanya administrator yang dapat masuk.')), h('label', { class: 'switch' }, mu, h('span'))),
          h('div', { class: 'setting' }, h('div', { class: 'grow' }, h('div', null, 'Peringatan ruang hampir penuh (GB)'), h('div', { class: 'd' }, 'Dashboard memberi peringatan bila ruang kosong SSD di bawah nilai ini.')), low),
          h('div', { class: 'field', style: null }, h('label', null, 'Nama host tambahan yang diizinkan'), hosts, h('div', { class: 'hint' }, 'Untuk nama domain sertifikat HTTPS. Alamat IP, localhost, dan *.ts.net (bila VPN aktif) selalu diizinkan.')),
          h('dl', { class: 'kv' }, h('dt', null, 'Akses LAN'), h('dd', null, s.lanAccess ? 'Aktif' : 'Nonaktif'), h('dt', null, 'Akses VPN'), h('dd', null, s.vpnAccess ? 'Aktif' : 'Nonaktif'),
            h('dt', null, 'Port HTTP'), h('dd', null, String(s.port)), h('dt', null, 'HTTPS'), h('dd', null, s.httpsEnabled ? 'Aktif' : 'Nonaktif')),
          h('p', { class: 'muted' }, h('small', null, s.note)),
          h('button', { class: 'btn primary', onclick: async () => {
            try {
              const r = await api('PATCH', '/api/admin/settings', { multiUser: mu.checked, lowSpaceWarnBytes: Math.max(0, Math.round(+low.value * 1e9)), extraHosts: hosts.value.split(',').map((x) => x.trim()).filter(Boolean) });
              state.multiUser = r.multiUser; toast('Pengaturan disimpan', 'ok');
            } catch (x) { toast(x.message, 'err'); }
          } }, 'Simpan pengaturan'));
      } catch (e) { clear(adminBox).append(h('div', { class: 'alert bad' }, e.message)); }
      try {
        const l = await api('GET', '/api/admin/logs');
        const fmt = (e) => new Date(e.time).toLocaleString('id-ID') + '  ' + (e.user ? '[' + e.user + '] ' : '') + (e.ip ? '(' + e.ip + ') ' : '') + e.detail;
        clear(logBox).append(h('h2', { class: 'section-title' }, 'Log kesalahan'),
          h('div', { class: 'log' }, l.errors.length ? l.errors.map(fmt).join('\n\n') : 'Tidak ada kesalahan tercatat.'),
          h('h2', { class: 'section-title', style: null }, 'Log aktivitas'),
          h('div', { class: 'log' }, l.activity.map(fmt).join('\n') || 'Kosong.'));
        logBox.children[2].style.marginTop = '18px';
      } catch (e) { clear(logBox).append(h('div', { class: 'alert bad' }, e.message)); }
    }
  }

  async function loadSessions(card) {
    try {
      const r = await api('GET', '/api/me/sessions');
      clear(card).append(h('h2', { class: 'section-title' }, 'Sesi login'),
        h('ul', { class: 'list' }, r.sessions.map((s) => h('li', null, icon('lock', 'sm'), h('div', { class: 'grow' },
          h('div', { class: 'ellipsis' }, uaLabel(s.userAgent), s.current && h('span', { class: 'tag blue', style: null }, ' Perangkat ini')),
          h('div', { class: 'muted' }, s.ip + ' · aktif ' + fmtAgo(s.lastSeen))),
          !s.current && h('button', { class: 'btn sm', onclick: async () => { await api('POST', '/api/me/sessions/revoke', { id: s.id }); loadSessions(card); } }, 'Keluarkan')))),
        r.sessions.length > 1 && h('button', { class: 'btn sm danger', onclick: async () => { await api('POST', '/api/me/sessions/revoke', { others: true }); toast('Perangkat lain dikeluarkan', 'ok'); loadSessions(card); } }, 'Keluarkan semua perangkat lain'),
        h('button', { class: 'btn sm', style: null, onclick: logout }, icon('logout', 'sm'), 'Keluar dari perangkat ini'));
      card.lastChild.style.marginLeft = '8px';
    } catch (e) { clear(card).append(h('div', { class: 'alert bad' }, e.message)); }
  }
  function uaLabel(ua) {
    if (!ua) return 'Perangkat tidak dikenal';
    const os = /Android/.test(ua) ? 'Android' : /iPhone|iPad/.test(ua) ? 'iOS' : /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Linux/.test(ua) ? 'Linux' : 'Lainnya';
    const br = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
    return br + ' · ' + os;
  }

  // ------------------------------------------------------------------ unggahan & transfer
  const CHUNK = 8 * 1024 * 1024;
  const Up = {
    items: [], running: 0, max: 2, panel: null, conflictAll: null,

    add(files, dir) {
      if (!files.length) return;
      this.conflictAll = null;
      for (const f of files) this.items.push({ file: f, dir, name: f.name, size: f.size, sent: 0, status: 'queued', kind: 'up' });
      this.render(); this.pump();
    },
    addDownload(it) { const x = { name: it.name, size: it.size, sent: 0, status: 'uploading', kind: 'down' }; this.items.push(x); this.render(); return x; },
    progress(x, n) { x.sent = n; x.msg = null; this.renderSoon(); },
    note(x, m) { x.msg = m; this.render(); },
    fail(x, m) { x.status = 'error'; x.msg = m; this.render(); },
    done(x, m) { x.status = 'done'; x.msg = m; this.render(); },

    pump() {
      while (this.running < this.max) {
        const next = this.items.find((x) => x.kind === 'up' && x.status === 'queued');
        if (!next) break;
        this.running++;
        next.status = 'uploading';
        this.run(next).finally(() => { this.running--; this.pump(); });
      }
    },

    async init(x, conflict) {
      return api('POST', '/api/uploads', { path: x.dir, name: x.name, size: x.size, conflict });
    },

    async run(x) {
      try {
        if (!x.id) {
          let u;
          try { u = await this.init(x, this.conflictAll || 'ask'); }
          catch (e) {
            if (e.code !== 'conflict') throw e;
            const choice = await this.askConflict(x, e.data);
            if (choice === 'skip') { x.status = 'skipped'; x.msg = 'Dilewati (nama sudah ada)'; this.render(); return; }
            u = await this.init(x, choice);
          }
          x.id = u.id; x.sent = u.committed;
        }
        x.startedAt = Date.now(); x.startSent = x.sent;
        let retries = 0;
        while (x.sent < x.size) {
          if (x.status === 'paused' || x.status === 'cancelled') return;
          const end = Math.min(x.sent + CHUNK, x.size);
          try {
            const r = await this.putChunk(x, x.file.slice(x.sent, end), x.sent);
            x.sent = r.committed; retries = 0; x.msg = null;
          } catch (e) {
            if (x.status === 'paused' || x.status === 'cancelled') return;
            if (e.code === 'offset') { x.sent = e.data.expected; continue; }
            if (e.code === 'storage_unavailable') { x.status = 'paused'; x.msg = 'SSD terputus. Sambungkan kembali SSD lalu klik lanjutkan.'; this.render(); return; }
            if (e.code === 'no_space' || e.code === 'quota') throw e;
            if (e.status === 0 || e.status >= 500 || e.code === 'connection') {
              if (++retries > 6) { x.status = 'paused'; x.msg = 'Koneksi terputus. Klik lanjutkan untuk mencoba lagi.'; this.render(); return; }
              x.msg = 'Koneksi terputus, mencoba lagi (' + retries + '/6)…'; this.render();
              await sleep(1500 * retries);
              try { const st = await api('GET', '/api/uploads/' + x.id); x.sent = st.committed; } catch (_) { }
              continue;
            }
            throw e;
          }
          this.renderSoon();
        }
        x.msg = 'Menyelesaikan…'; this.render();
        await api('POST', '/api/uploads/' + x.id + '/complete');
        x.status = 'done'; x.msg = 'Tersimpan di SSD';
        this.render();
        if (state.view === 'files' && state.path === x.dir) loadDir();
      } catch (e) {
        x.status = 'error'; x.msg = e.message; this.render();
      }
    },

    putChunk(x, blob, offset) {
      return new Promise((resolve, reject) => {
        const xhr = new XMLHttpRequest();
        x.xhr = xhr;
        xhr.open('PUT', '/api/uploads/' + x.id + '?offset=' + offset);
        xhr.setRequestHeader('X-CSRF-Token', state.csrf);
        xhr.setRequestHeader('Content-Type', 'application/octet-stream');
        xhr.upload.onprogress = (ev) => { x.live = offset + ev.loaded; this.renderSoon(); };
        xhr.onload = () => {
          let data = null; try { data = JSON.parse(xhr.responseText); } catch (_) { }
          x.live = null;
          if (xhr.status >= 200 && xhr.status < 300) resolve(data); else reject(new ApiErr(xhr.status, data));
        };
        xhr.onerror = () => { x.live = null; reject(new ApiErr(0, { error: 'Koneksi terputus', code: 'connection' })); };
        xhr.onabort = () => { x.live = null; reject(new ApiErr(0, { error: 'Dibatalkan', code: 'aborted' })); };
        xhr.send(blob);
      });
    },

    askConflict(x, data) {
      return new Promise((resolve) => {
        const all = h('input', { type: 'checkbox' });
        let done = false;
        const pick = (v) => () => { done = true; if (all.checked) this.conflictAll = v; resolve(v); };
        modal({ title: 'File sudah ada', text: '“' + x.name + '” sudah ada di folder ini. Apa yang ingin dilakukan?',
          body: h('label', { class: 'check' }, all, 'Terapkan untuk semua file berikutnya'),
          onClose: () => { if (!done) resolve('skip'); },
          actions: [{ label: 'Lewati', onClick: pick('skip') }, { label: 'Simpan sebagai “' + data.suggested + '”', onClick: pick('rename') },
            { label: 'Ganti file lama', kind: 'solid-danger', onClick: pick('overwrite') }] });
      });
    },

    pause(x) { x.status = 'paused'; x.msg = 'Dijeda'; if (x.xhr) x.xhr.abort(); this.render(); },
    resume(x) {
      if (!x.file) return this.pickForResume(x);
      x.status = 'queued'; x.msg = null; this.render(); this.pump();
    },
    async cancel(x) {
      x.status = 'cancelled'; if (x.xhr) x.xhr.abort();
      if (x.id) { try { await api('DELETE', '/api/uploads/' + x.id); } catch (_) { } }
      this.items.splice(this.items.indexOf(x), 1); this.render();
    },
    pickForResume(x) {
      const input = h('input', { type: 'file' });
      input.onchange = () => {
        const f = input.files[0];
        if (!f) return;
        if (f.name !== x.name || f.size !== x.size) { toast('File berbeda. Pilih “' + x.name + '” (' + fmtBytes(x.size) + ').', 'err'); return; }
        x.file = f; this.resume(x);
      };
      input.click();
    },

    /** Unggahan yang belum selesai dari sesi sebelumnya (mis. browser ditutup). */
    async restore() {
      try {
        const r = await api('GET', '/api/uploads');
        for (const u of r.uploads) {
          if (this.items.some((x) => x.id === u.id)) continue;
          this.items.push({ id: u.id, dir: u.dir, name: u.name, size: u.size, sent: u.committed, status: 'paused', kind: 'up', msg: 'Belum selesai. Klik lanjutkan lalu pilih file yang sama.' });
        }
        this.render();
      } catch (_) { }
    },

    renderSoon() { if (!this.raf) this.raf = requestAnimationFrame(() => { this.raf = null; this.render(); }); },
    render() {
      if (!this.items.length) { if (this.panel) { this.panel.remove(); this.panel = null; } return; }
      if (!this.panel || !this.panel.isConnected) { this.panel = h('div', { class: 'uploads' }); document.body.append(this.panel); }
      const active = this.items.filter((x) => x.status === 'uploading' || x.status === 'queued').length;
      clear(this.panel).append(h('header', null, icon(active ? 'upload' : 'check', 'sm'), h('span', { class: 'grow' }, active ? 'Mentransfer ' + active + ' file' : 'Transfer selesai'),
        !active && h('button', { class: 'btn ghost icon sm', title: 'Tutup', onclick: () => { this.items = this.items.filter((x) => x.status === 'paused' || x.status === 'error'); this.render(); } }, icon('x', 'sm'))),
      h('div', { class: 'items' }, this.items.map((x) => {
        const sent = x.live != null ? x.live : x.sent;
        const pct = x.size ? Math.min(100, sent / x.size * 100) : (x.status === 'done' ? 100 : 0);
        const meter = h('div', { class: 'meter' + (x.status === 'error' ? ' bad' : x.status === 'paused' ? ' warn' : '') }, h('div'));
        meter.firstChild.style.width = pct + '%';
        let speed = '';
        if (x.status === 'uploading' && x.startedAt && x.kind === 'up') {
          const secs = (Date.now() - x.startedAt) / 1000;
          if (secs > 1) speed = ' · ' + fmtBytes((sent - x.startSent) / secs) + '/dtk';
        }
        const metaCls = 'meta' + (x.status === 'error' ? ' err' : x.status === 'done' ? ' ok' : '');
        return h('div', { class: 'up' }, h('div', { class: 'row' }, icon(x.kind === 'down' ? 'download' : 'upload', 'sm'), h('div', { class: 'grow ellipsis', title: x.name }, x.name),
          x.kind === 'up' && x.status === 'uploading' && h('button', { class: 'btn ghost icon sm', title: 'Jeda', onclick: () => this.pause(x) }, icon('pause', 'sm')),
          x.kind === 'up' && (x.status === 'paused' || x.status === 'error') && x.id && h('button', { class: 'btn ghost icon sm', title: 'Lanjutkan', onclick: () => this.resume(x) }, icon('play', 'sm')),
          x.kind === 'up' && x.status !== 'done' && x.status !== 'skipped' && h('button', { class: 'btn ghost icon sm', title: 'Batalkan', onclick: () => this.cancel(x) }, icon('x', 'sm'))),
        x.status !== 'done' && x.status !== 'skipped' && meter,
        h('div', { class: metaCls }, x.msg || (fmtBytes(sent) + ' / ' + fmtBytes(x.size) + ' · ' + Math.floor(pct) + '%' + speed)));
      })));
    },
  };

  // Seret & lepas untuk unggah
  let dragDepth = 0, dz = null;
  window.addEventListener('dragenter', (e) => {
    if (!state.user || state.view !== 'files' || !e.dataTransfer || ![...e.dataTransfer.types].includes('Files')) return;
    if (!(state.user.canWrite || state.user.role === 'admin')) return;
    dragDepth++;
    if (!dz) { dz = h('div', { class: 'dropzone' }, h('div', null, 'Lepaskan untuk mengunggah ke ' + state.path)); document.body.append(dz); }
  });
  window.addEventListener('dragleave', () => { if (--dragDepth <= 0 && dz) { dz.remove(); dz = null; dragDepth = 0; } });
  window.addEventListener('dragover', (e) => { if (dz) e.preventDefault(); });
  window.addEventListener('drop', (e) => {
    if (!dz) return;
    e.preventDefault(); dz.remove(); dz = null; dragDepth = 0;
    const files = [...e.dataTransfer.files].filter((f) => f.size > 0 || f.type);
    if (files.length) Up.add(files, state.path);
  });
  window.addEventListener('beforeunload', (e) => {
    if (Up.items.some((x) => x.status === 'uploading' || x.status === 'queued')) { e.preventDefault(); e.returnValue = ''; }
  });

  const _route = route;
  let restored = false;
  route = function () { _route(); if (!restored && state.user) { restored = true; Up.restore(); } };

  boot();
})();
