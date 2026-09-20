/* ============================================================
 * manage.js —— 智能体管理 / 模型配置 / 提示词配置 三个管理页
 * 对应 /agent(CRUD)、/aimodel/configs(list|save|activate)、
 * /prompt/templates(list|save|activate|effective)
 * ============================================================ */

/* ================= 智能体管理 ================= */
async function mountAgents(view) {
  await loadAgents();
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>智能体管理</h1>
        <p>分析主体:每个智能体独立绑定数据表与知识资源,「数据问答 / 智能体知识库」都作用于左侧选中的智能体</p>
      </div>
      <div class="page-actions"><button class="btn" id="ag-add">＋ 新建智能体</button></div>
    </div>
    <div class="agent-grid" id="ag-grid"></div>`;

  $('#ag-add').onclick = () => showAgentModal(null);
  renderAgentGrid();

  if (window.__wantNewAgent) {
    window.__wantNewAgent = false;
    showAgentModal(null);
  }
  window.__pageCleanup = null;
}

function renderAgentGrid() {
  const grid = $('#ag-grid');
  if (!grid) return;
  grid.innerHTML =
    store.agents
      .map(
        (a) => `<div class="agent-card ${a.id === store.activeAgentId ? 'active' : ''}">
      <div class="name">${esc(a.name)}${a.id === store.activeAgentId ? ' <span class="chip blue">当前</span>' : ''}</div>
      <div class="desc">${esc(a.description || '暂无描述')}</div>
      <div class="meta">#${a.id} · 更新于 ${fmtTime(a.updateTime)}</div>
      <div class="acts">
        <button class="btn mini" data-open="${a.id}">知识库</button>
        <button class="btn mini secondary" data-edit="${a.id}">编辑</button>
        <button class="btn mini danger" data-del="${a.id}" data-name="${esc(a.name)}">删除</button>
      </div>
    </div>`
      )
      .join('') || '<div class="empty">暂无智能体,点「新建智能体」</div>';

  $$('#ag-grid [data-open]').forEach((b) => {
    b.onclick = () => {
      setActiveAgent(Number(b.dataset.open));
      location.hash = '#/knowledge';
    };
  });
  $$('#ag-grid [data-edit]').forEach((b) => {
    const row = store.agents.find((a) => String(a.id) === b.dataset.edit);
    b.onclick = () => showAgentModal(row);
  });
  $$('#ag-grid [data-del]').forEach((b) => {
    b.onclick = () =>
      confirmBox({
        title: '删除智能体',
        message: `删除「${b.dataset.name}」及其全部数据(知识源 / 表绑定 / 向量 / 会话)?此操作不可恢复。`,
        confirmText: '删除',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', '/agent/' + b.dataset.del);
            await loadAgents();
            refreshAgentUI();
            renderAgentGrid();
            toast('已删除(级联清理完成)');
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
  });
}

function showAgentModal(row) {
  const isEdit = !!row;
  openModal({
    title: isEdit ? '编辑智能体' : '新建智能体',
    body: `
      <div class="field" style="margin-bottom:12px"><label>名称 <span class="req">*</span></label>
        <input id="ag-name" value="${esc(row ? row.name : '')}" placeholder="如:经营分析助手"></div>
      <div class="field"><label>描述</label>
        <textarea id="ag-desc" rows="3" placeholder="用途 / 面向的数据与业务域(可空)">${esc(row ? row.description || '' : '')}</textarea></div>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>${isEdit ? '保存修改' : '创建'}</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const payload = {
          name: $('#ag-name', bodyEl).value.trim(),
          description: $('#ag-desc', bodyEl).value.trim(),
        };
        if (!payload.name) return toast('名称不能为空', true);
        try {
          if (isEdit) {
            await api('POST', '/agent/' + row.id, payload);
            toast('已保存修改');
          } else {
            const created = await api('POST', '/agent', payload);
            toast('已创建');
            // 新建后自动选为当前智能体
            await loadAgents();
            if (created && created.id) setActiveAgent(created.id);
          }
          await loadAgents();
          refreshAgentUI();
          close();
          renderAgentGrid();
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

/* ================= 模型配置 ================= */
async function mountModels(view) {
  await loadModels();
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>模型配置</h1>
        <p>OpenAI 兼容协议:对话模型(CHAT)与向量模型(EMBEDDING)各激活一个,跑图与知识向量化即可用</p>
      </div>
      <div class="page-actions"><button class="btn" id="md-add">＋ 添加配置</button></div>
    </div>
    <div id="md-list"></div>`;

  $('#md-add').onclick = () => showModelModal();
  renderModelList();
  window.__pageCleanup = null;
}

function renderModelList() {
  const box = $('#md-list');
  if (!box) return;
  const list = store.models;
  const paramSummary = (m) => {
    const parts = [];
    if (m.temperature != null) parts.push('temp=' + m.temperature);
    if (m.maxTokens != null) parts.push('maxTokens=' + m.maxTokens);
    if (m.topP != null) parts.push('topP=' + m.topP);
    if (m.seed != null) parts.push('seed=' + m.seed);
    return parts.join(' · ') || '按服务默认';
  };
  box.innerHTML = `
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>类型</th><th>模型名</th><th>服务地址</th><th>参数</th><th>状态</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          ${
            list
              .map(
                (m) => `<tr>
            <td><span class="chip ${m.modelType === 'CHAT' ? 'blue' : ''}">${esc(m.modelType)}</span></td>
            <td class="cell-main">${esc(m.modelName)}</td>
            <td class="dim mono" style="max-width:280px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${esc(m.baseUrl)}">${esc(m.baseUrl)}</td>
            <td class="dim small">${esc(paramSummary(m))}</td>
            <td>${m.isActive ? '<span class="chip green"><span class="dot"></span>已激活</span>' : '<span class="chip">未激活</span>'}</td>
            <td><div class="actions">
              ${m.isActive ? '' : `<button class="btn link" data-act="${m.id}">激活(即时生效)</button>`}
              <button class="btn link" data-medit="${m.id}">编辑</button>
              <button class="btn link danger" data-mdel="${m.id}" data-name="${esc(m.modelName)}" data-active="${m.isActive ? '1' : ''}">删除</button>
            </div></td>
          </tr>`
              )
              .join('') || '<tr><td colspan="6" class="empty">暂无模型配置,点「添加配置」</td></tr>'
          }
        </tbody>
      </table>
    </div>
    <p class="dim small" style="margin-top:10px">提示:接口未配置时,聊天与向量化相关功能会提示「未配置可用的模型」;激活会热构建实例,失败时旧实例继续生效。</p>`;

  $$('[data-act]').forEach((b) => {
    b.onclick = async () => {
      try {
        await api('POST', `/aimodel/configs/${b.dataset.act}/activate`);
        await loadModels();
        renderModelList();
        refreshAgentUI();
        toast('已激活,立即生效(同类型原激活已自动取消)');
      } catch (e) {
        toast(e.message, true);
      }
    };
  });
  $$('[data-medit]').forEach((b) => {
    const row = store.models.find((x) => String(x.id) === b.dataset.medit);
    b.onclick = () => showModelModal(row);
  });
  $$('[data-mdel]').forEach((b) => {
    b.onclick = () => {
      const isActive = !!b.dataset.active;
      confirmBox({
        title: '删除模型配置',
        message: isActive
          ? `删除「${b.dataset.name}」?该配置当前已激活,删除将同时停用(该类型变为未配置)。`
          : `删除「${b.dataset.name}」?`,
        confirmText: '删除',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', '/aimodel/configs/' + b.dataset.mdel);
            await loadModels();
            renderModelList();
            refreshAgentUI();
            toast(isActive ? '已删除并停用' : '已删除');
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
    };
  });
}

function showModelModal(row) {
  const isEdit = !!row;
  const v = (x) => (x != null ? x : '');
  openModal({
    title: isEdit ? '编辑模型配置' : '添加模型配置',
    body: `
      <div class="form-grid">
        <div class="field"><label>类型${isEdit ? '(创建后不可修改)' : ' *'}</label>
          <select id="mm-type" ${isEdit ? 'disabled' : ''}>
            <option value="CHAT" ${row && row.modelType === 'CHAT' ? 'selected' : ''}>CHAT(对话)</option>
            <option value="EMBEDDING" ${row && row.modelType === 'EMBEDDING' ? 'selected' : ''}>EMBEDDING(向量)</option>
          </select></div>
        <div class="field"><label>模型名 <span class="req">*</span></label>
          <input id="mm-name" value="${esc(row ? row.modelName : '')}" placeholder="如:deepseek-chat / text-embedding-v3"></div>
        <div class="field" style="grid-column:1/-1"><label>服务地址(baseUrl) <span class="req">*</span></label>
          <input id="mm-url" value="${esc(row ? row.baseUrl : '')}" placeholder="如:https://api.deepseek.com">
          <span class="dim small">不要以 /v1 结尾:系统会自动拼接 /v1/chat/completions 与 /v1/embeddings(带了会拼出 /v1/v1 报 404)</span></div>
        <div class="field" style="grid-column:1/-1"><label>API Key${isEdit ? '(留空 = 不修改)' : '(本地无鉴权可空)'}</label>
          <input id="mm-key" placeholder="${isEdit ? '留空保持原密钥' : 'sk-…'}"></div>
      </div>
      <div class="dim small" style="margin:14px 0 6px">高级参数(可空,按服务默认)</div>
      <div class="form-grid">
        <div class="field"><label>temperature</label><input id="mm-temp" type="number" step="0.1" min="0" max="2" value="${v(row && row.temperature)}"></div>
        <div class="field"><label>maxTokens</label><input id="mm-maxtok" type="number" min="1" value="${v(row && row.maxTokens)}"></div>
        <div class="field"><label>topP</label><input id="mm-topp" type="number" step="0.1" min="0" max="1" value="${v(row && row.topP)}"></div>
        <div class="field"><label>frequencyPenalty</label><input id="mm-fp" type="number" step="0.1" value="${v(row && row.frequencyPenalty)}"></div>
        <div class="field"><label>presencePenalty</label><input id="mm-pp" type="number" step="0.1" value="${v(row && row.presencePenalty)}"></div>
        <div class="field"><label>seed</label><input id="mm-seed" type="number" value="${v(row && row.seed)}"></div>
      </div>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>${isEdit ? '保存修改(激活行即时生效)' : '保存(默认未激活)'}</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const num = (id) => {
          const val = $('#' + id, bodyEl).value.trim();
          return val === '' ? undefined : Number(val);
        };
        const payload = {
          modelType: isEdit ? row.modelType : $('#mm-type', bodyEl).value,
          modelName: $('#mm-name', bodyEl).value.trim(),
          baseUrl: $('#mm-url', bodyEl).value.trim(),
          apiKey: $('#mm-key', bodyEl).value.trim(),
          temperature: num('mm-temp'),
          maxTokens: num('mm-maxtok'),
          topP: num('mm-topp'),
          frequencyPenalty: num('mm-fp'),
          presencePenalty: num('mm-pp'),
          seed: num('mm-seed'),
        };
        if (!payload.modelName || !payload.baseUrl) return toast('模型名与服务地址必填', true);
        try {
          if (isEdit) {
            await api('POST', '/aimodel/configs/' + row.id, payload);
            toast('已保存修改(如为激活行已即时生效)');
          } else {
            await api('POST', '/aimodel/configs', payload);
            toast('已保存(未激活;去列表点「激活」)');
          }
          close();
          await loadModels();
          renderModelList();
          refreshAgentUI();
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

/* ================= 提示词配置 ================= */
let promptContentMap = {};

async function mountPrompts(view) {
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>提示词配置</h1>
        <p>节点提示词模板:修改 = 另存新版本后激活;节点每次执行读取生效版本,激活即时生效、无需重启</p>
      </div>
      <div class="page-actions"><button class="btn" id="pr-new">＋ 新建模板</button></div>
    </div>
    <div id="pr-list"><div class="empty">加载中…</div></div>`;
  $('#pr-new').onclick = () => showPromptEditor({ name: '', content: '', isNew: true });
  await renderPromptList();
  window.__pageCleanup = null;
}

async function renderPromptList() {
  const box = $('#pr-list');
  if (!box) return;
  let list;
  try {
    list = await api('GET', '/prompt/templates');
  } catch (e) {
    box.innerHTML = `<div class="empty">加载失败:${esc(e.message)}</div>`;
    return;
  }
  promptContentMap = {};
  list.forEach((p) => (promptContentMap[p.id] = p.content));
  const names = [...new Set(list.map((p) => p.name))];

  box.innerHTML =
    names
      .map((n) => {
        const versions = list.filter((p) => p.name === n);
        return `<div class="prompt-group">
        <div class="pg-head">
          <span class="pg-name">${esc(n)}</span>
          <span class="chip">${versions.length} 个版本</span>
          <span class="spacer"></span>
          <button class="btn mini secondary" data-effective="${esc(n)}">查看生效版本</button>
          <button class="btn mini" data-newver="${esc(n)}">＋ 新版本</button>
        </div>
        ${versions
          .map(
            (p) => `<div class="prompt-row">
          <span class="chip ${p.enabled ? 'green' : ''}">v${p.version}${p.enabled ? ' · 生效中' : ''}</span>
          <span class="prompt-preview" title="${esc(p.content || '')}">${esc((p.content || '').replace(/\s+/g, ' ').slice(0, 120))}</span>
          ${p.enabled ? '' : `<button class="btn mini" data-activate="${p.id}">激活此版本</button>`}
          <button class="btn mini secondary" data-view="${p.id}">查看 / 编辑</button>
        </div>`
          )
          .join('')}
      </div>`;
      })
      .join('') || '<div class="empty">暂无模板;点「新建模板」或让系统自动写入种子</div>';

  $$('[data-activate]').forEach((b) => {
    b.onclick = async () => {
      try {
        await api('POST', `/prompt/templates/${b.dataset.activate}/activate`);
        toast('已激活,节点下次执行生效');
        renderPromptList();
      } catch (e) {
        toast(e.message, true);
      }
    };
  });
  $$('[data-view]').forEach((b) => {
    b.onclick = () => {
      const id = Number(b.dataset.view);
      const p = list.find((x) => x.id === id);
      showPromptEditor({ name: p.name, content: promptContentMap[id] || '' });
    };
  });
  $$('[data-newver]').forEach((b) => {
    b.onclick = () => {
      const name = b.dataset.newver;
      const latest = list.filter((p) => p.name === name).sort((x, y) => y.version - x.version)[0];
      showPromptEditor({ name, content: promptContentMap[latest.id] || '', prefill: true });
    };
  });
  $$('[data-effective]').forEach((b) => {
    b.onclick = async () => {
      try {
        const p = await api('GET', `/prompt/templates/${encodeURIComponent(b.dataset.effective)}/effective`);
        openModal({
          title: `生效版本 · ${p.name}`,
          large: true,
          body: `<div class="row" style="margin-bottom:8px"><span class="chip green">v${p.version}</span><span class="dim small">节点当前实际读取这一版</span></div>
            <pre class="code" style="max-height:60vh;overflow:auto;white-space:pre-wrap">${esc(p.content || '')}</pre>`,
        });
      } catch (e) {
        toast(e.message, true);
      }
    };
  });
}

function showPromptEditor({ name, content, isNew, prefill }) {
  openModal({
    title: isNew ? '新建提示词模板' : `提示词模板 · ${name}`,
    large: true,
    body: `
      ${isNew ? `<div class="field" style="margin-bottom:10px"><label>模板名(节点 ID,如 planner / sql-generate) <span class="req">*</span></label>
        <input id="pe-name" placeholder="kebab-case,和节点注册名一致"></div>` : ''}
      <div class="field"><label>内容${prefill ? '(已预填最新生效内容,可微调)' : ''}</label>
        <textarea id="pe-content" rows="16" class="mono" style="font-size:12px">${esc(content || '')}</textarea></div>
      <p class="dim small" style="margin:8px 0 0">保存 = 新增一个版本(未激活);在列表里点「激活此版本」后,节点下次执行即用新版。</p>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>另存为新版本</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const n = isNew ? $('#pe-name', bodyEl).value.trim() : name;
        const c = $('#pe-content', bodyEl).value;
        if (!n) return toast('模板名不能为空', true);
        if (!c.trim()) return toast('内容不能为空', true);
        try {
          const saved = await api('POST', '/prompt/templates', { name: n, content: c });
          toast(`已保存 v${saved && saved.version ? saved.version : ''}(未激活)`);
          close();
          renderPromptList();
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}
