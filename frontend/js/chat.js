/* ============================================================
 * chat.js —— 数据问答页:会话管理(客户端编排)+ SSE 流式渲染
 * 时间线(步骤/SQL/结果/计划)+ 报告卡(markdown)+ 计划确认(挂起恢复)
 * 消息持久化:assistant 存一条 messageType='timeline',
 * content = {"blocks":[…], "report": "markdown"}
 * ============================================================ */

const chatState = {
  sessionId: null,
  sessions: [],
  messages: [],
  busy: false,
  es: null,
  runId: null,
  blocks: [],
  planText: '',
  finalText: '',
  pendingRunId: null,
  streamErr: null,
  sawFrame: false,
  humanReview: false,
  showSqlResults: true,
};

const WELCOME_TIPS = [
  '各渠道的订单总额和客单价对比',
  '最近 3 个月每月的已支付订单数趋势',
  '复购率最高的城市是哪一个?',
];

/* ================= 挂载 ================= */
async function mountChat(view) {
  closeChatStream();
  Object.assign(chatState, {
    sessionId: null,
    sessions: [],
    messages: [],
    busy: false,
    runId: null,
    blocks: [],
    planText: '',
    finalText: '',
    pendingRunId: null,
    streamErr: null,
  });

  view.innerHTML = `
    <div class="chat-page">
      <aside class="sess-side">
        <div class="sess-side-head">
          <b>历史会话</b>
          <button class="btn mini secondary" id="sess-refresh">刷新</button>
        </div>
        <ul class="sess-list" id="sess-list"></ul>
        <button class="btn" id="sess-new">＋ 新建分析会话</button>
      </aside>
      <section class="chat-main">
        <div class="msgs" id="msgs"></div>
        <div class="composer" id="composer"></div>
      </section>
    </div>`;

  $('#sess-new').onclick = createChatSession;
  $('#sess-refresh').onclick = () => loadChatSessions().catch((e) => toast(e.message, true));

  renderComposer();
  await loadChatSessions().catch((e) => toast(e.message, true));
  renderMessages();

  window.__pageCleanup = closeChatStream;
}

/* ================= 会话 ================= */
async function loadChatSessions() {
  const agent = activeAgent();
  if (!agent) {
    chatState.sessions = [];
    renderSessions();
    return;
  }
  chatState.sessions = await api('GET', '/session?agentId=' + agent.id);
  renderSessions();
}

function renderSessions() {
  const ul = $('#sess-list');
  if (!ul) return;
  ul.innerHTML =
    chatState.sessions
      .map(
        (s) => `<li class="sess-item ${s.id === chatState.sessionId ? 'active' : ''}" data-id="${esc(s.id)}">
        <span class="sess-title" title="${esc(s.title || '')}">${esc(s.title || '新会话')}</span>
        <span class="sess-time">${fmtTime(s.updateTime || s.createTime).slice(5)}</span>
        <button class="sess-del" data-del="${esc(s.id)}" title="删除会话">✕</button>
      </li>`
      )
      .join('') || '<li class="sess-empty">暂无会话,点下方新建</li>';
  $$('#sess-list .sess-item').forEach((li) => {
    li.onclick = (e) => {
      if (e.target.dataset.del) return;
      openChatSession(li.dataset.id);
    };
  });
  $$('#sess-list [data-del]').forEach((b) => {
    b.onclick = (e) => {
      e.stopPropagation();
      removeChatSession(b.dataset.del);
    };
  });
}

async function createChatSession() {
  const agent = activeAgent();
  if (!agent) return toast('先在左侧选择智能体', true);
  try {
    const s = await api('POST', '/session?agentId=' + agent.id);
    chatState.sessionId = s.id;
    chatState.messages = [];
    chatState.pendingRunId = null;
    chatState.planText = '';
    renderSessions();
    renderMessages();
    renderFeedbackPanel();
    $('#chat-input') && $('#chat-input').focus();
  } catch (e) {
    toast(e.message, true);
  }
}

async function openChatSession(id) {
  if (chatState.busy) return toast('任务进行中,先停止再切换会话', true);
  chatState.sessionId = id;
  chatState.pendingRunId = null;
  chatState.planText = '';
  renderSessions();
  renderFeedbackPanel();
  try {
    chatState.messages = await api('GET', `/session/${id}/messages`);
  } catch (e) {
    chatState.messages = [];
    toast(e.message, true);
  }
  renderMessages();
}

function removeChatSession(id) {
  confirmBox({
    title: '删除会话',
    message: '删除该会话记录(含图侧记忆)?此操作不可恢复。',
    confirmText: '删除',
    danger: true,
    onConfirm: async () => {
      try {
        await api('POST', '/graph/stop?sessionId=' + encodeURIComponent(id)).catch(() => {});
        await api('DELETE', '/graph/memory?sessionId=' + encodeURIComponent(id)).catch(() => {});
        await api('DELETE', '/session/' + id);
        if (chatState.sessionId === id) {
          chatState.sessionId = null;
          chatState.messages = [];
          renderMessages();
        }
        await loadChatSessions();
        toast('已删除');
      } catch (e) {
        toast(e.message, true);
      }
    },
  });
}

/* ================= 消息渲染 ================= */
function renderMessages() {
  const box = $('#msgs');
  if (!box) return;
  if (!activeAgent()) {
    box.innerHTML = `<div class="empty"><div class="big">🤖</div>请先在左侧「当前选择智能体」里选择一个智能体</div>`;
    return;
  }
  if (!chatState.sessionId) {
    box.innerHTML = `
      <div class="welcome">
        <span class="logo"></span>
        <h2>你好,我是 DataBuddy</h2>
        <p>面向业务数据的分析智能体:理解问题 → 规划 → 取数 → 分析 → 成文</p>
        <div class="tips">${WELCOME_TIPS.map((t) => `<span class="tip">${esc(t)}</span>`).join('')}</div>
      </div>`;
    $$('#msgs .tip').forEach(
      (el) =>
        (el.onclick = () => {
          const input = $('#chat-input');
          if (input) {
            input.value = el.textContent;
            input.focus();
          }
        })
    );
    return;
  }
  if (!chatState.messages.length) {
    box.innerHTML = '<div class="empty">开始提问吧 —— 结果会以「执行时间线 + 分析报告」呈现</div>';
    return;
  }
  box.innerHTML = chatState.messages.map(renderHistoryMsg).join('');
  scrollChatBottom();
}

function renderHistoryMsg(m) {
  if (m.role === 'USER') {
    return `<div class="msg-row user"><span class="msg-avatar me">我</span><div class="msg-bubble">${esc(m.content)}</div></div>`;
  }
  let inner;
  if (m.messageType === 'timeline') {
    let data = null;
    try {
      data = JSON.parse(m.content);
    } catch {
      data = { blocks: [], report: m.content };
    }
    inner = `${renderBlocks(data.blocks || [], true)}${data.report ? reportBody(data.report) : ''}`;
    if (!inner) inner = '<div class="md">(空消息)</div>';
  } else if (m.messageType === 'warning') {
    inner = `<div class="status-banner warn">⚠ ${esc(m.content)}</div>`;
  } else if (m.messageType === 'error') {
    inner = `<div class="status-banner err">✕ ${esc(m.content)}</div>`;
  } else {
    inner = reportBody(m.content);
  }
  return `<div class="msg-row"><span class="msg-avatar ai">AI</span><div class="msg-block">${inner}</div></div>`;
}

function reportBody(md) {
  return `<div class="report-body md">${renderMarkdown(md)}</div>`;
}

/* ===== 时间线块渲染(流式与历史共用)===== */
function renderBlocks(blocks, done) {
  if (!blocks || !blocks.length) return '';
  const rows = blocks
    .map((b, idx) => {
      const isActive = !done && idx === blocks.length - 1;
      const dotCls = isActive ? 'active' : 'done';
      const dot = isActive ? '' : '✓';
      let label = '';
      let body = '';
      if (b.type === 'step') {
        label = `<div class="wtl-label">${esc(b.text)}${isActive ? '<span class="chip blue wtl-badge">进行中</span>' : ''}</div>`;
      } else if (b.type === 'sql') {
        label = `<div class="wtl-label">SQL 生成${isActive ? '<span class="chip blue wtl-badge">进行中</span>' : ''}</div>`;
        body = `<div class="wtl-body"><pre class="code"><span class="lang">SQL</span>${esc(b.text)}</pre></div>`;
      } else if (b.type === 'result') {
        label = `<div class="wtl-label">SQL 执行结果${isActive ? '<span class="chip blue wtl-badge">进行中</span>' : ''}</div>`;
        body = chatState.showSqlResults ? `<div class="wtl-body">${renderResultSet(b.text)}</div>` : '';
      } else if (b.type === 'plan') {
        label = '<div class="wtl-label">执行计划</div>';
        body = `<div class="wtl-body">${renderPlanCard(b.text, isActive && !!chatState.pendingRunId)}</div>`;
      }
      return `<div class="wtl-item ${dotCls}"><span class="wtl-dot ${dotCls}">${dot}</span>${label}${body}</div>`;
    })
    .join('');
  return `<div class="wtl"><div class="wtl-head"><b>任务执行</b><span class="dim small">共 ${blocks.length} 个节点</span></div>${rows}</div>`;
}

/* SQL 结果集(客户端分页) */
function renderResultSet(text) {
  let r;
  try {
    r = JSON.parse(text);
  } catch {
    return `<pre class="code">${esc(text)}</pre>`;
  }
  const cols = r.columns || [];
  const rowsAll = r.rows || [];
  if (!cols.length) return '<div class="dim small">无结果</div>';
  const pageSize = 10;
  const pages = Math.max(1, Math.ceil(rowsAll.length / pageSize));
  const id = 'rs-' + Math.random().toString(36).slice(2, 8);
  const rows = rowsAll.slice(0, pageSize);
  return `<div class="rset" data-pages="${pages}" data-rows="${esc(JSON.stringify(rowsAll))}" data-cols="${esc(JSON.stringify(cols))}" id="${id}">
    <div class="rset-head">
      <span>共 ${rowsAll.length} 行${r.truncated ? '(已截断)' : ''}</span>
      <span class="row" style="gap:6px">
        <button class="page-btn" data-pg="prev" disabled>‹</button>
        <span class="pg-info">1 / ${pages}</span>
        <button class="page-btn" data-pg="next" ${pages <= 1 ? 'disabled' : ''}>›</button>
      </span>
    </div>
    <div class="rset-scroll">${rsetTable(cols, rows)}</div>
  </div>`;
}

function rsetTable(cols, rows) {
  return `<table><thead><tr>${cols.map((c) => `<th>${esc(c)}</th>`).join('')}</tr></thead>
    <tbody>${rows.map((row) => `<tr>${cols.map((c) => `<td title="${esc(row[c])}">${esc(row[c])}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
}

/* 结果集分页事件(事件委托,全局只绑一次) */
document.addEventListener('click', (e) => {
  const btn = e.target.closest('.rset .page-btn');
  if (!btn || btn.disabled) return;
  const rset = btn.closest('.rset');
  const cols = JSON.parse(rset.dataset.cols || '[]');
  const rowsAll = JSON.parse(rset.dataset.rows || '[]');
  const pages = Number(rset.dataset.pages || 1);
  const info = $('.pg-info', rset);
  let pg = Number(info.textContent.split('/')[0].trim());
  pg += btn.dataset.pg === 'next' ? 1 : -1;
  pg = Math.max(1, Math.min(pages, pg));
  info.textContent = `${pg} / ${pages}`;
  $('[data-pg="prev"]', rset).disabled = pg <= 1;
  $('[data-pg="next"]', rset).disabled = pg >= pages;
  const start = (pg - 1) * 10;
  $('.rset-scroll', rset).innerHTML = rsetTable(cols, rowsAll.slice(start, start + 10));
});

/* 计划卡(历史只读;挂起中带按钮走 composer 面板,不在此处) */
function renderPlanCard(planText, withBadge) {
  let p = null;
  try {
    p = JSON.parse(planText);
  } catch {
    return `<pre class="code">${esc(planText)}</pre>`;
  }
  const steps = Array.isArray(p.execution_plan) ? p.execution_plan : [];
  return `<div class="plan-card">
    <div class="plan-title">${withBadge ? '待确认的执行计划' : '执行计划'}</div>
    ${p.thought_process ? `<div class="dim small" style="margin-bottom:4px">思路:${esc(p.thought_process)}</div>` : ''}
    <ol>${steps.map((s) => `<li>${esc(s.instruction)} <span class="dim small">(${esc(s.tool_to_use)})</span></li>`).join('')}</ol>
  </div>`;
}

/* ================= 流式渲染 ================= */
function closeChatStream() {
  if (chatState.es) {
    chatState.es.close();
    chatState.es = null;
  }
}

function scrollChatBottom() {
  const box = $('#msgs');
  if (box) box.scrollTop = box.scrollHeight;
}

function renderStreaming() {
  const box = $('#msgs');
  if (!box) return;
  $$('.streaming-mark', box).forEach((el) => el.remove());
  if (!chatState.busy) return;
  const timeline = renderBlocks(chatState.blocks, false);
  const report = chatState.finalText
    ? `<div class="report-body md">${renderMarkdown(chatState.finalText)}</div>`
    : '';
  const content = timeline || report ? `${timeline}${report}` : thinkingHTML();
  box.insertAdjacentHTML(
    'beforeend',
    `<div class="msg-row streaming-mark"><span class="msg-avatar ai">AI</span><div class="msg-block">${content}</div></div>`
  );
  scrollChatBottom();
}

function thinkingHTML() {
  return '<div class="thinking"><span class="dot"></span><span class="dot"></span><span class="dot"></span></div>';
}

function startChatStream(url) {
  closeChatStream();
  chatState.busy = true;
  chatState.sawFrame = false;
  chatState.runId = null;
  chatState.blocks = [];
  chatState.finalText = '';
  chatState.streamErr = null;
  toggleComposerBusy(true);
  renderStreaming();

  const es = new EventSource(API + url);
  chatState.es = es;
  const on = (name, fn) =>
    es.addEventListener(name, (e) => {
      chatState.sawFrame = true;
      let c;
      try {
        c = JSON.parse(e.data);
      } catch {
        c = { text: e.data };
      }
      chatState.runId = c.runId || chatState.runId;
      fn(c);
    });

  on('step', (c) => {
    chatState.blocks.push({ type: 'step', text: c.text });
    renderStreaming();
  });
  on('sql', (c) => {
    chatState.blocks.push({ type: 'sql', text: c.text });
    renderStreaming();
  });
  on('result', (c) => {
    chatState.blocks.push({ type: 'result', text: c.text });
    renderStreaming();
  });
  on('plan', (c) => {
    chatState.planText = c.text || '';
    chatState.pendingRunId = chatState.runId;
    chatState.blocks.push({ type: 'plan', text: chatState.planText });
    renderFeedbackPanel();
    renderStreaming();
  });
  on('text', (c) => {
    chatState.finalText += c.text || '';
    renderStreaming();
  });
  on('done', () => finishChatStream());

  es.onerror = (e) => {
    if (e && e.data) {
      let t = e.data;
      try {
        t = JSON.parse(e.data).text || e.data;
      } catch {}
      chatState.streamErr = t;
    } else if (chatState.busy && !chatState.sawFrame) {
      chatState.streamErr = '请求被拒绝或连接失败(检查模型配置与参数)';
    }
    finishChatStream();
  };
}

function finishChatStream() {
  closeChatStream();
  const wasBusy = chatState.busy;
  chatState.busy = false;
  toggleComposerBusy(false);
  if (!wasBusy) return;

  const hasData =
    chatState.blocks.length || chatState.finalText || chatState.streamErr;
  if (hasData && chatState.sessionId) {
    let content;
    let messageType = 'timeline';
    if (chatState.streamErr && !chatState.blocks.length) {
      messageType = 'error';
      content = chatState.streamErr;
    } else {
      content = JSON.stringify({
        blocks: chatState.blocks,
        report: chatState.finalText || (chatState.streamErr ? `执行中断:${chatState.streamErr}` : null),
      });
    }
    api('POST', `/session/${chatState.sessionId}/messages`, {
      role: 'ASSISTANT',
      content,
      messageType,
    }).catch(() => {});
    chatState.messages.push({ role: 'ASSISTANT', content, messageType });
  }
  chatState.blocks = [];
  chatState.finalText = '';
  chatState.streamErr = null;
  renderMessages();
  renderFeedbackPanel();
}

/* ================= 发问与确认 ================= */
function renderComposer() {
  const composer = $('#composer');
  if (!composer) return;
  composer.innerHTML = `
    <div class="composer-status">
      <span class="status-chip" id="chip-db" title="当前智能体绑定的业务库">数据库:—</span>
      <span class="status-chip clickable" id="chip-model" title="点击切换激活的对话模型">模型:—</span>
    </div>
    <div class="feedback-panel" id="fb-panel" hidden>
      <div class="head">任务已挂起:请确认执行计划</div>
      <textarea id="fb-input" rows="2" placeholder="修改意见(接受可留空;拒绝重规划时必填)"></textarea>
      <div class="acts">
        <button class="btn reject" id="fb-reject" type="button">✕ 拒绝并重新规划</button>
        <button class="btn accept" id="fb-accept" type="button">✓ 接受计划并继续</button>
      </div>
    </div>
    <textarea id="chat-input" rows="3" placeholder="在这里提问,例如:各渠道的订单总额和客单价对比(Enter 发送,Shift+Enter 换行)"></textarea>
    <div class="composer-foot">
      <div class="opt-chips">
        <label class="opt-chip ${chatState.humanReview ? 'active' : ''}" id="opt-review">
          <input type="checkbox" ${chatState.humanReview ? 'checked' : ''}> 人工确认计划
        </label>
        <label class="opt-chip ${chatState.showSqlResults ? 'active' : ''}" id="opt-sqlresults">
          <input type="checkbox" ${chatState.showSqlResults ? 'checked' : ''}> 显示 SQL 结果
        </label>
      </div>
      <div class="row">
        <button class="btn send-btn" id="btn-send" type="button">发送</button>
        <button class="btn stop-btn" id="btn-stop" type="button" hidden>停止</button>
      </div>
    </div>`;

  $('#btn-send').onclick = sendChat;
  $('#btn-stop').onclick = stopChat;
  $('#chat-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      sendChat();
    }
  });
  $('#opt-review').onclick = () => {
    chatState.humanReview = !chatState.humanReview;
    $('#opt-review').classList.toggle('active', chatState.humanReview);
    $('#opt-review input').checked = chatState.humanReview;
    if (chatState.humanReview) toast('已开启人工确认:计划生成后将挂起等待你确认');
  };
  $('#opt-sqlresults').onclick = () => {
    chatState.showSqlResults = !chatState.showSqlResults;
    $('#opt-sqlresults').classList.toggle('active', chatState.showSqlResults);
    $('#opt-sqlresults input').checked = chatState.showSqlResults;
    if (!chatState.busy) renderMessages();
  };
  $('#fb-accept').onclick = () => resumeChatPlan(true);
  $('#fb-reject').onclick = () => resumeChatPlan(false);
  $('#chip-model').onclick = showModelSwitcher;
  $('#chip-db').onclick = () => (location.hash = '#/datasources');

  renderComposerStatus();
}

async function renderComposerStatus() {
  const agent = activeAgent();
  const dbChip = $('#chip-db');
  if (dbChip) {
    if (!agent) {
      dbChip.textContent = '数据库:—';
    } else {
      try {
        if (!store.dbs.length) await loadDbs();
        const binds = await api('GET', `/agent/${agent.id}/biz-tables`);
        const names = [...new Set(binds.map((b) => b.databaseConfigId))]
          .map((id) => (store.dbs.find((d) => d.id === id) || {}).name)
          .filter(Boolean);
        dbChip.textContent = '数据库:' + (names.join('、') || '未绑定');
      } catch {
        dbChip.textContent = '数据库:—';
      }
    }
  }
  renderTopbarModel();
}

function renderTopbarModel() {
  const el = $('#topbar-model');
  if (!el) return;
  const m = activeChatModel();
  const e = activeEmbeddingModel();
  el.textContent = `对话模型:${m ? m.modelName : '未配置'} · 向量模型:${e ? e.modelName : '未配置'}`;
}

function showModelSwitcher() {
  const chats = store.models.filter((m) => m.modelType === 'CHAT');
  openModal({
    title: '切换对话模型(激活即刻生效)',
    body:
      chats
        .map(
          (m) => `<div class="hit" data-id="${m.id}" style="cursor:pointer">
        <span class="chip ${m.isActive ? 'green' : 'blue'}">${m.isActive ? '已激活' : '激活'}</span>
        <div class="hit-body"><div class="cell-main">${esc(m.modelName)}</div><div class="hit-meta">${esc(m.baseUrl)}</div></div>
      </div>`
        )
        .join('') || '<div class="empty">暂无 CHAT 模型配置,先去「模型配置」添加</div>',
    onMount: (body, foot, close) => {
      $$('.hit', body).forEach((row) => {
        row.onclick = async () => {
          try {
            await api('POST', `/aimodel/configs/${row.dataset.id}/activate`);
            await loadModels();
            renderTopbarModel();
            close();
            toast('模型已切换,即时生效');
          } catch (e) {
            toast(e.message, true);
          }
        };
      });
    },
  });
}

function toggleComposerBusy(busy) {
  const send = $('#btn-send');
  const stop = $('#btn-stop');
  const input = $('#chat-input');
  if (!send) return;
  send.hidden = busy;
  stop.hidden = !busy;
  if (input) input.disabled = busy;
}

function renderFeedbackPanel() {
  const panel = $('#fb-panel');
  if (!panel) return;
  const show = !!chatState.pendingRunId && !chatState.busy;
  panel.hidden = !show;
}

async function sendChat() {
  const input = $('#chat-input');
  const text = input.value.trim();
  if (!text || chatState.busy) return;
  const agent = activeAgent();
  if (!agent) return toast('先在左侧选择智能体', true);
  if (chatState.pendingRunId) return toast('有计划待确认,请先接受或拒绝', true);
  if (!chatState.sessionId) {
    await createChatSession();
    if (!chatState.sessionId) return;
  }
  try {
    await api('POST', `/session/${chatState.sessionId}/messages`, {
      role: 'USER',
      content: text,
      messageType: 'text',
    });
  } catch (e) {
    toast(e.message, true);
  }
  chatState.messages.push({ role: 'USER', content: text, messageType: 'text' });
  input.value = '';
  renderMessages();
  startChatStream(
    `/graph/run?agentId=${agent.id}&input=${encodeURIComponent(text)}&sessionId=${encodeURIComponent(
      chatState.sessionId
    )}&humanReview=${chatState.humanReview}`
  );
}

function resumeChatPlan(approved) {
  const fb = ($('#fb-input') || {}).value || '';
  const feedback = fb.trim();
  if (!approved && !feedback) return toast('拒绝并重规划时请填写修改意见', true);
  const runId = chatState.pendingRunId;
  if (!runId) return toast('缺少运行号(runId)', true);
  chatState.pendingRunId = null;
  chatState.planText = '';
  renderFeedbackPanel();
  startChatStream(
    `/graph/resume?runId=${encodeURIComponent(runId)}&approved=${approved}&feedback=${encodeURIComponent(feedback)}`
  );
}

function stopChat() {
  const rid = chatState.runId;
  closeChatStream();
  finishChatStream();
  if (rid) api('POST', '/graph/stop?runId=' + encodeURIComponent(rid)).catch(() => {});
  toast('已停止');
}
