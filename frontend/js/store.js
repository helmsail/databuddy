/* ============================================================
 * store.js —— 全局共享状态:智能体清单 / 当前智能体 / 模型 / 业务库
 * 页面每次挂载时自行拉取所需数据,这里只放跨页共享的部分
 * ============================================================ */

const store = {
  agents: [],
  activeAgentId: null,
  models: [],
  dbs: [],
};

const AGENT_KEY = 'dd-active-agent';

async function loadAgents() {
  store.agents = await api('GET', '/agent');
  const saved = Number(localStorage.getItem(AGENT_KEY));
  const savedOk = store.agents.some((a) => a.id === saved);
  if (!store.agents.some((a) => a.id === store.activeAgentId)) {
    store.activeAgentId = savedOk ? saved : (store.agents[0] ? store.agents[0].id : null);
  }
  if (store.activeAgentId) localStorage.setItem(AGENT_KEY, String(store.activeAgentId));
}

function activeAgent() {
  return store.agents.find((a) => a.id === store.activeAgentId) || null;
}

async function loadModels() {
  store.models = await api('GET', '/aimodel/configs');
}

async function loadDbs() {
  store.dbs = await api('GET', '/bizdatabase/configs');
}

function activeChatModel() {
  return store.models.find((m) => m.modelType === 'CHAT' && m.isActive) || null;
}

function activeEmbeddingModel() {
  return store.models.find((m) => m.modelType === 'EMBEDDING' && m.isActive) || null;
}
