/* ============================================================
 * app.js —— 应用入口:hash 路由、侧栏导航、全局智能体选择器、初始化
 * ============================================================ */

const ROUTES = {
  chat: { title: '数据问答', mount: mountChat },
  prompts: { title: '提示词配置', mount: mountPrompts },
  knowledge: { title: '智能体知识库', mount: mountKnowledge },
  agents: { title: '智能体管理', mount: mountAgents },
  datasources: { title: '数据连接', mount: mountDatasources },
  models: { title: '模型配置', mount: mountModels },
};

let currentRoute = 'chat';

function parseRoute() {
  const h = location.hash.replace(/^#\/?/, '').split('?')[0];
  return ROUTES[h] ? h : 'chat';
}

async function route() {
  const name = parseRoute();
  currentRoute = name;
  $$('#side-nav button').forEach((b) => b.classList.toggle('active', b.dataset.route === name));
  $('#topbar-title').textContent = ROUTES[name].title;

  const view = $('#view');
  if (window.__pageCleanup) {
    try {
      window.__pageCleanup();
    } catch {}
    window.__pageCleanup = null;
  }
  view.innerHTML = '';
  view.scrollTop = 0;
  try {
    await ROUTES[name].mount(view);
  } catch (e) {
    view.innerHTML = `<div class="empty">页面加载失败:${esc(e.message)}</div>`;
  }
}

/* ===== 全局智能体选择器 ===== */
function renderAgentSwitch() {
  const nameEl = $('#agent-switch-name');
  const menu = $('#agent-menu');
  if (!nameEl || !menu) return;
  const a = activeAgent();
  nameEl.textContent = a ? a.name : '(未选择智能体)';
  menu.innerHTML =
    store.agents
      .map(
        (x) => `<div class="agent-menu-item ${x.id === store.activeAgentId ? 'active' : ''}" data-id="${x.id}">
      <div class="name">${esc(x.name)}</div>
      <div class="desc">${esc(x.description || '暂无描述')}</div>
    </div>`
      )
      .join('') || '<div class="agent-menu-empty">暂无智能体,去「智能体管理」新建</div>';
  $$('.agent-menu-item', menu).forEach((item) => {
    item.onclick = () => {
      setActiveAgent(Number(item.dataset.id));
      menu.hidden = true;
    };
  });
}

function setActiveAgent(id) {
  store.activeAgentId = id;
  localStorage.setItem(AGENT_KEY, String(id));
  renderAgentSwitch();
  if (currentRoute === 'knowledge' || currentRoute === 'chat') route();
}

/* 供管理页在数据变更后刷新外壳(侧栏下拉 + 顶栏模型) */
function refreshAgentUI() {
  renderAgentSwitch();
  if (typeof renderTopbarModel === 'function') renderTopbarModel();
}

/* ===== 初始化 ===== */
async function initApp() {
  $$('#side-nav button').forEach((btn) => {
    btn.onclick = () => (location.hash = '#/' + btn.dataset.route);
  });

  const switchEl = $('#agent-switch');
  const menu = $('#agent-menu');
  switchEl.onclick = (e) => {
    if (menu.contains(e.target)) return;
    menu.hidden = !menu.hidden;
  };
  document.addEventListener('click', (e) => {
    if (!switchEl.contains(e.target)) menu.hidden = true;
  });

  $('#side-new-agent').onclick = () => {
    window.__wantNewAgent = true;
    if (parseRoute() === 'agents') route();
    else location.hash = '#/agents';
  };
  $('#topbar-model').onclick = () => (location.hash = '#/models');

  try {
    await loadAgents();
  } catch (e) {
    toast('智能体列表加载失败:' + e.message, true);
  }
  renderAgentSwitch();
  try {
    await loadModels();
  } catch {}
  if (typeof renderTopbarModel === 'function') renderTopbarModel();

  window.addEventListener('hashchange', route);
  await route();
}

initApp();
