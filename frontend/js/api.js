/* ============================================================
 * api.js —— 基础工具:DOM 助手、请求封装(解 ApiResponse 信封)、
 * toast / confirm / 通用弹窗,供全部页面模块使用
 * ============================================================ */

const $ = (sel, el = document) => el.querySelector(sel);
const $$ = (sel, el = document) => [...el.querySelectorAll(sel)];

const esc = (s) =>
  String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const fmtTime = (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '—');

const API = new URLSearchParams(location.search).get('api') || '';

/* ===== 统一请求:成功取 data,失败抛 Error(message) ===== */
async function api(method, path, body) {
  const res = await fetch(API + path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const j = await res.json().catch(() => null);
  if (!j || !j.success) throw new Error((j && j.message) || 'HTTP ' + res.status);
  return j.data;
}

/* 上传(不设 Content-Type,浏览器自带 boundary) */
async function apiUpload(path, formData) {
  const res = await fetch(API + path, { method: 'POST', body: formData });
  const j = await res.json().catch(() => null);
  if (!j || !j.success) throw new Error((j && j.message) || 'HTTP ' + res.status);
  return j.data;
}

/* 下载(经隐藏 <a>,服务端 Content-Disposition 决定文件名) */
function downloadFile(path) {
  const a = document.createElement('a');
  a.href = API + path;
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/* ===== toast ===== */
let _toastTimer;
function toast(msg, bad) {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'toast' + (bad ? ' bad' : '');
  t.hidden = false;
  clearTimeout(_toastTimer);
  _toastTimer = setTimeout(() => (t.hidden = true), 3200);
}

/* ===== 通用弹窗:openModal({title, body, footer, large, onMount}) =====
 * body / footer 为 HTML 字符串;onMount(bodyEl, footEl, close) 里绑事件。
 * 返回 close 函数;点遮罩 / ✕ / close() 均可关闭 */
function openModal({ title, body, footer, large, onMount }) {
  const mask = document.createElement('div');
  mask.className = 'modal-mask';
  mask.innerHTML = `
    <div class="modal ${large ? 'modal-lg' : ''}">
      <div class="modal-head">
        <div class="modal-title">${esc(title)}</div>
        <button class="modal-close" type="button">✕</button>
      </div>
      <div class="modal-body">${body || ''}</div>
      ${footer ? `<div class="modal-foot">${footer}</div>` : ''}
    </div>`;
  $('#modal-root').appendChild(mask);

  const close = () => mask.remove();
  mask.addEventListener('click', (e) => {
    if (e.target === mask) close();
  });
  $('.modal-close', mask).onclick = close;
  if (onMount) onMount($('.modal-body', mask), $('.modal-foot', mask), close);
  return close;
}

/* ===== 确认框:confirmBox({title, message, confirmText, danger, onConfirm}) ===== */
function confirmBox({ title, message, confirmText = '确认', danger, onConfirm }) {
  const mask = document.createElement('div');
  mask.className = 'modal-mask';
  mask.innerHTML = `
    <div class="modal confirm-box">
      <div class="modal-head">
        <div class="modal-title">${esc(title)}</div>
        <button class="modal-close" type="button">✕</button>
      </div>
      <div class="modal-body">
        <div class="confirm-icon ${danger ? 'danger' : ''}">${danger ? '!' : '?'}</div>
        <div>${esc(message)}</div>
      </div>
      <div class="modal-foot">
        <button class="btn secondary" type="button" data-act="cancel">取消</button>
        <button class="btn ${danger ? 'danger' : ''}" type="button" data-act="ok">${esc(confirmText)}</button>
      </div>
    </div>`;
  $('#confirm-root').appendChild(mask);

  const close = () => mask.remove();
  mask.addEventListener('click', (e) => {
    if (e.target === mask) close();
  });
  $('.modal-close', mask).onclick = close;
  $('[data-act="cancel"]', mask).onclick = close;
  $('[data-act="ok"]', mask).onclick = () => {
    close();
    onConfirm && onConfirm();
  };
}

/* ===== 向量化状态徽标 ===== */
function stChip(status, err) {
  const map = { SYNCED: ['green', '已同步'], PENDING: ['amber', '处理中'], FAILED: ['red', '失败'] };
  const [cls, label] = map[status] || ['', status || '未知'];
  const tip = err ? ` title="${esc(err)}"` : '';
  return `<span class="chip ${cls}"${tip}><span class="dot"></span>${esc(label)}${status === 'FAILED' ? ' ⓘ' : ''}</span>`;
}

/* ===== 通用小工具 ===== */
const SPLITTERS = [
  ['PARAGRAPH', '按段落(PARAGRAPH)'],
  ['MARKDOWN', '按标题结构(MARKDOWN)'],
  ['WHOLE', '整篇(WHOLE)'],
  ['TOKEN', '按长度(TOKEN)'],
];

function splitterSelect(name, value) {
  return `<select name="${name}">${SPLITTERS.map(
    ([v, label]) => `<option value="${v}" ${v === value ? 'selected' : ''}>${label}</option>`
  ).join('')}</select>`;
}
