/* ============================================================
 * knowledge.js —— 智能体知识库:数据表 / 文档 / 术语 / 问答 / 检索测试
 * 五栏与后端 /agent/{id}/biz-tables|documents|terms|qa|retrieve 一一对应
 * ============================================================ */

async function mountKnowledge(view) {
  const agent = activeAgent();
  if (!agent) {
    view.innerHTML = `<div class="empty"><div class="big">📚</div>请先在左侧「当前选择智能体」里选择一个智能体</div>`;
    return;
  }
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>智能体知识库</h1>
        <p>${esc(agent.name)} · 维护专属知识资源(数据表 / 文档 / 术语 / 问答),支持向量召回</p>
      </div>
      <div class="page-actions">
        <button class="btn secondary" id="kb-rebuild" title="内存向量库重启后会清空:点此把四类知识全部重新向量化">重建全部向量</button>
      </div>
    </div>
    <div class="tabbar" id="kb-tabs">
      <button data-tab="tables" class="active">数据表</button>
      <button data-tab="docs">文档</button>
      <button data-tab="terms">术语</button>
      <button data-tab="qa">问答</button>
      <button data-tab="retrieve">检索测试</button>
    </div>
    <div id="kb-body"><div class="empty">加载中…</div></div>`;

  $$('#kb-tabs button').forEach((btn) => {
    btn.onclick = () => {
      $$('#kb-tabs button').forEach((b) => b.classList.toggle('active', b === btn));
      renderKbTab(btn.dataset.tab);
    };
  });
  $('#kb-rebuild').onclick = () =>
    confirmBox({
      title: '重建全部向量',
      message: '把该智能体的表 / 术语 / 问答 / 文档全部重新向量化(内存向量库重启丢失后的恢复入口)。确认执行?',
      confirmText: '开始重建',
      onConfirm: async () => {
        try {
          toast('重建中,请稍候…');
          await api('POST', `/agent/${agent.id}/knowledge/rebuild`);
          toast('全部向量重建完成');
          const active = $('#kb-tabs button.active');
          renderKbTab(active ? active.dataset.tab : 'tables');
        } catch (e) {
          toast(e.message, true);
        }
      },
    });
  renderKbTab('tables');

  window.__pageCleanup = null;
}

async function renderKbTab(tab) {
  const body = $('#kb-body');
  if (!body) return;
  body.innerHTML = '<div class="empty">加载中…</div>';
  try {
    if (tab === 'tables') await renderKbTables(body);
    else if (tab === 'docs') await renderKbDocs(body);
    else if (tab === 'terms') await renderKbTerms(body);
    else if (tab === 'qa') await renderKbQa(body);
    else if (tab === 'retrieve') renderKbRetrieve(body);
  } catch (e) {
    body.innerHTML = `<div class="empty">加载失败:${esc(e.message)}</div>`;
  }
}

/* ================= 数据表 ================= */
async function renderKbTables(body) {
  const agent = activeAgent();
  if (!store.dbs.length) await loadDbs();
  const binds = await api('GET', `/agent/${agent.id}/biz-tables`);
  const dbName = (id) => (store.dbs.find((d) => d.id === id) || {}).name || '#' + id;

  body.innerHTML = `
    <div class="card">
      <h2>绑定数据表</h2>
      <div class="row">
        <select id="bind-db" class="grow" style="max-width:320px">
          <option value="">选择业务库</option>
          ${store.dbs.map((d) => `<option value="${d.id}">${esc(d.name)}(${esc(d.dbType)})</option>`).join('')}
        </select>
        <button class="btn secondary" id="bind-fetch">拉取表清单</button>
        <span class="dim small">未找到业务库?先去「数据连接」添加</span>
      </div>
      <div class="pick-panel" id="bind-panel" hidden>
        <div class="table-pick" id="bind-pick"></div>
        <div class="row" style="margin-top:8px">
          <button class="btn" id="bind-do">绑定所选</button>
          <span class="dim small" id="bind-count"></span>
        </div>
      </div>
    </div>
    <div class="tab-toolbar">
      <button class="btn secondary" id="tbl-sync">全量同步向量</button>
      <button class="btn secondary" id="tbl-retry">重试未完成</button>
      <span class="hint">共绑定 ${binds.length} 张表;绑定/同步后自动入向量库</span>
    </div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>表名</th><th>所属库</th><th>嵌入状态</th><th>更新时间</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          ${
            binds
              .map(
                (b) => `<tr>
            <td class="cell-main">${esc(b.tableName)}</td>
            <td class="dim">${esc(dbName(b.databaseConfigId))}</td>
            <td>${stChip(b.embeddingStatus, b.errorMsg)}</td>
            <td class="dim">${fmtTime(b.updateTime)}</td>
            <td><div class="actions">
              <button class="btn link" data-cols="${b.id}" data-db="${b.databaseConfigId}" data-table="${esc(b.tableName)}">查看结构</button>
              <button class="btn link danger" data-unbind="${b.id}">解绑</button>
            </div></td>
          </tr>`
              )
              .join('') || '<tr><td colspan="5" class="empty">尚未绑定任何表</td></tr>'
          }
        </tbody>
      </table>
    </div>`;

  $('#bind-fetch').onclick = async () => {
    const dbId = $('#bind-db').value;
    if (!dbId) return toast('先选择业务库', true);
    try {
      const tables = await api('GET', `/bizdatabase/configs/${dbId}/tables`);
      $('#bind-panel').hidden = false;
      $('#bind-pick').innerHTML =
        tables
          .map(
            (t) => `<label title="${esc(t.comment || '')}">
          <input type="checkbox" value="${esc(t.name)}" ${binds.some((b) => b.tableName === t.name && b.databaseConfigId === Number(dbId)) ? 'disabled checked' : ''}>
          <span>${esc(t.name)}</span><span class="cmt">${esc(t.comment || '')}</span>
        </label>`
          )
          .join('') || '<div class="dim">该库暂无表</div>';
      const updateCount = () => ($('#bind-count').textContent = `已选 ${$$('#bind-pick input:checked:not(:disabled)').length} 张`);
      $$('#bind-pick input').forEach((i) => (i.onchange = updateCount));
      updateCount();
    } catch (e) {
      toast(e.message, true);
    }
  };

  $('#bind-do').onclick = async () => {
    const names = $$('#bind-pick input:checked:not(:disabled)').map((i) => i.value);
    if (!names.length) return toast('先勾选要绑定的表', true);
    try {
      await api('POST', `/agent/${agent.id}/biz-tables`, { databaseConfigId: Number($('#bind-db').value), tableNames: names });
      toast(`已绑定 ${names.length} 张表,向量同步中`);
      renderKbTab('tables');
    } catch (e) {
      toast(e.message, true);
    }
  };

  $('#tbl-sync').onclick = async () => {
    try {
      await api('POST', `/agent/${agent.id}/biz-tables/sync`);
      toast('已触发全量重刷');
      renderKbTab('tables');
    } catch (e) {
      toast(e.message, true);
    }
  };
  $('#tbl-retry').onclick = async () => {
    try {
      await api('POST', `/agent/${agent.id}/biz-tables/retry`);
      toast('已重试未完成的表');
      renderKbTab('tables');
    } catch (e) {
      toast(e.message, true);
    }
  };

  $$('[data-unbind]').forEach((b) => {
    b.onclick = () =>
      confirmBox({
        title: '解绑确认',
        message: '解绑该表(删绑定行 + 删对应向量)?',
        confirmText: '解绑',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', `/agent/${agent.id}/biz-tables`, [Number(b.dataset.unbind)]);
            toast('已解绑');
            renderKbTab('tables');
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
  });

  $$('[data-cols]').forEach((b) => {
    b.onclick = () => showTableColumns(b.dataset.db, b.dataset.table);
  });
}

async function showTableColumns(dbId, table) {
  openModal({
    title: `表结构 · ${table}`,
    body: '<div class="empty">加载中…</div>',
    large: true,
    onMount: async (bodyEl) => {
      try {
        const cols = await api('GET', `/bizdatabase/configs/${dbId}/tables/${encodeURIComponent(table)}/columns`);
        bodyEl.innerHTML = `<div class="tbl-wrap"><table class="tbl">
          <thead><tr><th>列名</th><th>类型</th><th>可空</th><th>注释</th></tr></thead>
          <tbody>${cols
            .map(
              (c) => `<tr><td class="cell-main mono">${esc(c.name)}</td><td class="dim mono">${esc(c.type)}</td>
            <td class="dim">${c.nullable ? '是' : '否'}</td><td class="dim">${esc(c.comment || '')}</td></tr>`
            )
            .join('')}</tbody></table></div>`;
      } catch (e) {
        bodyEl.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
      }
    },
  });
}

/* ================= 文档 ================= */
async function renderKbDocs(body) {
  const agent = activeAgent();
  const docs = await api('GET', `/agent/${agent.id}/documents`);

  body.innerHTML = `
    <div class="tab-toolbar">
      <button class="btn" id="doc-upload">＋ 上传文档</button>
      <button class="btn secondary" id="doc-retry">重试未完成</button>
      <span class="hint">txt / md / pdf / word / excel / ppt 等;上传后异步切分向量化</span>
    </div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>文档名</th><th>切分策略</th><th>嵌入状态</th><th>创建时间</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          ${
            docs
              .map(
                (d) => `<tr>
            <td class="cell-main">${esc(d.name)}</td>
            <td>
              <select data-splitter="${d.id}" class="dim" style="font-size:12px">
                ${SPLITTERS.map(([v, label]) => `<option value="${v}" ${v === d.splitterType ? 'selected' : ''}>${v}</option>`).join('')}
              </select>
            </td>
            <td>${stChip(d.embeddingStatus, d.errorMsg)}</td>
            <td class="dim">${fmtTime(d.createTime)}</td>
            <td><div class="actions">
              <button class="btn link" data-down="${d.id}">下载</button>
              <button class="btn link" data-rename="${d.id}" data-name="${esc(d.name)}">改名</button>
              <button class="btn link danger" data-rmdoc="${d.id}" data-name="${esc(d.name)}">删除</button>
            </div></td>
          </tr>`
              )
              .join('') || '<tr><td colspan="5" class="empty">暂无文档,点「上传文档」添加</td></tr>'
          }
        </tbody>
      </table>
    </div>`;

  $('#doc-upload').onclick = () => showDocUploadModal();
  $('#doc-retry').onclick = async () => {
    try {
      await api('POST', `/agent/${agent.id}/documents/retry`);
      toast('已重试未完成的文档');
      renderKbTab('docs');
    } catch (e) {
      toast(e.message, true);
    }
  };

  $$('[data-splitter]').forEach((sel) => {
    sel.onchange = async () => {
      try {
        await api('POST', `/agent/${agent.id}/documents/${sel.dataset.splitter}`, { splitterType: sel.value });
        toast('切分策略已更新,重新向量化中');
        setTimeout(() => renderKbTab('docs'), 1500);
      } catch (e) {
        toast(e.message, true);
      }
    };
  });
  $$('[data-down]').forEach((b) => (b.onclick = () => downloadFile(`/agent/${agent.id}/documents/${b.dataset.down}/download`)));
  $$('[data-rename]').forEach((b) => (b.onclick = () => showDocRenameModal(b.dataset.rename, b.dataset.name)));
  $$('[data-rmdoc]').forEach((b) => {
    b.onclick = () =>
      confirmBox({
        title: '删除文档',
        message: `删除「${b.dataset.name}」(行 + 向量 + 物理文件)?`,
        confirmText: '删除',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', `/agent/${agent.id}/documents/${b.dataset.rmdoc}`);
            toast('已删除');
            renderKbTab('docs');
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
  });
}

function showDocUploadModal() {
  const agent = activeAgent();
  openModal({
    title: '上传文档',
    body: `
      <div class="field" style="margin-bottom:12px">
        <label>文件 <span class="req">*</span></label>
        <input type="file" id="up-file" accept=".txt,.md,.markdown,.csv,.sql,.json,.xml,.yml,.yaml,.log,.html,.htm,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx,.rtf,.odt,.ods,.odp">
      </div>
      <div class="form-grid">
        <div class="field"><label>文档名(缺省用文件名)</label><input id="up-name" placeholder="如:业务规则说明书.md"></div>
        <div class="field"><label>切分策略</label>${splitterSelect('up-splitter', 'PARAGRAPH')}</div>
      </div>
      <p class="dim small" style="margin:10px 0 0">文件落存储后立即返回,后台异步切分向量化;失败的可在列表里「重试未完成」。</p>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>上传</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const file = $('#up-file', bodyEl).files[0];
        if (!file) return toast('请选择文件', true);
        const name = $('#up-name', bodyEl).value.trim();
        const splitter = $('[name="up-splitter"]', bodyEl).value;
        const qs = new URLSearchParams();
        if (name) qs.set('name', name);
        if (splitter) qs.set('splitterType', splitter);
        const fd = new FormData();
        fd.append('file', file);
        try {
          await apiUpload(`/agent/${agent.id}/documents?${qs.toString()}`, fd);
          toast('已上传,后台向量化中');
          close();
          renderKbTab('docs');
          setTimeout(() => renderKbTab('docs'), 2000);
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

function showDocRenameModal(id, oldName) {
  const agent = activeAgent();
  openModal({
    title: '文档改名',
    body: `<div class="field"><label>新文档名</label><input id="rn-name" value="${esc(oldName)}"></div>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>保存</button>`,
    onMount: (bodyEl, footEl, close) => {
      const input = $('#rn-name', bodyEl);
      input.focus();
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const name = input.value.trim();
        if (!name || name === oldName) return close();
        try {
          await api('POST', `/agent/${agent.id}/documents/${id}`, { name });
          toast('已改名');
          close();
          renderKbTab('docs');
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

/* ================= 术语 ================= */
async function renderKbTerms(body) {
  const agent = activeAgent();
  const rows = await api('GET', `/agent/${agent.id}/terms`);
  body.innerHTML = `
    <div class="tab-toolbar">
      <button class="btn" id="term-add">＋ 添加术语</button>
      <button class="btn secondary" id="term-retry">重试未完成</button>
      <span class="hint">新增 / 修改即同步向量;失败行可重试</span>
    </div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>术语</th><th>同义词</th><th>释义</th><th>嵌入状态</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          ${
            rows
              .map(
                (t) => `<tr>
            <td class="cell-main">${esc(t.businessTerm)}</td>
            <td class="dim">${esc(t.synonyms || '—')}</td>
            <td class="dim">${esc(t.description || '—')}</td>
            <td>${stChip(t.embeddingStatus, t.errorMsg)}</td>
            <td><div class="actions">
              <button class="btn link" data-tedit="${t.id}">编辑</button>
              <button class="btn link danger" data-trm="${t.id}" data-name="${esc(t.businessTerm)}">删除</button>
            </div></td>
          </tr>`
              )
              .join('') || '<tr><td colspan="5" class="empty">暂无术语,点「添加术语」</td></tr>'
          }
        </tbody>
      </table>
    </div>`;

  $('#term-add').onclick = () => showTermModal(null);
  $('#term-retry').onclick = async () => {
    try {
      await api('POST', `/agent/${agent.id}/terms/retry`);
      toast('已重试未完成的术语');
      renderKbTab('terms');
    } catch (e) {
      toast(e.message, true);
    }
  };
  $$('[data-tedit]').forEach((b) => {
    const row = rows.find((t) => String(t.id) === b.dataset.tedit);
    b.onclick = () => showTermModal(row);
  });
  $$('[data-trm]').forEach((b) => {
    b.onclick = () =>
      confirmBox({
        title: '删除术语',
        message: `删除「${b.dataset.name}」(行 + 向量)?`,
        confirmText: '删除',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', `/agent/${agent.id}/terms/${b.dataset.trm}`);
            toast('已删除');
            renderKbTab('terms');
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
  });
}

function showTermModal(row) {
  const agent = activeAgent();
  const isEdit = !!row;
  openModal({
    title: isEdit ? '编辑术语' : '添加术语',
    body: `<div class="form-grid">
      <div class="field"><label>术语 <span class="req">*</span></label><input id="tm-term" value="${esc(row ? row.businessTerm : '')}" placeholder="如:GMV"></div>
      <div class="field"><label>同义词(逗号分隔,可空)</label><input id="tm-syn" value="${esc(row ? row.synonyms || '' : '')}" placeholder="如:成交总额,商品交易总额"></div>
    </div>
    <div class="field" style="margin-top:12px"><label>释义(可空)</label><textarea id="tm-desc" rows="3" placeholder="业务口径说明,检索命中后回源给模型">${esc(row ? row.description || '' : '')}</textarea></div>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>${isEdit ? '保存' : '添加并同步'}</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const payload = {
          businessTerm: $('#tm-term', bodyEl).value.trim(),
          synonyms: $('#tm-syn', bodyEl).value.trim(),
          description: $('#tm-desc', bodyEl).value.trim(),
        };
        if (!payload.businessTerm) return toast('术语不能为空', true);
        try {
          if (isEdit) await api('POST', `/agent/${agent.id}/terms/${row.id}`, payload);
          else await api('POST', `/agent/${agent.id}/terms`, payload);
          toast(isEdit ? '已保存,向量重同步中' : '已添加,向量同步中');
          close();
          renderKbTab('terms');
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

/* ================= 问答 ================= */
async function renderKbQa(body) {
  const agent = activeAgent();
  const rows = await api('GET', `/agent/${agent.id}/qa`);
  body.innerHTML = `
    <div class="tab-toolbar">
      <button class="btn" id="qa-add">＋ 添加问答</button>
      <button class="btn secondary" id="qa-retry">重试未完成</button>
      <span class="hint">问题入向量;答案随命中回源(不进入向量)</span>
    </div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th style="width:34%">问题</th><th>答案</th><th>嵌入状态</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          ${
            rows
              .map(
                (q) => `<tr>
            <td class="cell-main">${esc(q.question)}</td>
            <td class="dim">${esc((q.content || '答案待补充').slice(0, 80))}${(q.content || '').length > 80 ? '…' : ''}</td>
            <td>${stChip(q.embeddingStatus, q.errorMsg)}</td>
            <td><div class="actions">
              <button class="btn link" data-qedit="${q.id}">编辑</button>
              <button class="btn link danger" data-qrm="${q.id}" data-name="${esc(q.question)}">删除</button>
            </div></td>
          </tr>`
              )
              .join('') || '<tr><td colspan="4" class="empty">暂无问答,点「添加问答」</td></tr>'
          }
        </tbody>
      </table>
    </div>`;

  $('#qa-add').onclick = () => showQaModal(null);
  $('#qa-retry').onclick = async () => {
    try {
      await api('POST', `/agent/${agent.id}/qa/retry`);
      toast('已重试未完成的问答');
      renderKbTab('qa');
    } catch (e) {
      toast(e.message, true);
    }
  };
  $$('[data-qedit]').forEach((b) => {
    const row = rows.find((q) => String(q.id) === b.dataset.qedit);
    b.onclick = () => showQaModal(row);
  });
  $$('[data-qrm]').forEach((b) => {
    b.onclick = () =>
      confirmBox({
        title: '删除问答',
        message: `删除「${b.dataset.name}」(行 + 向量)?`,
        confirmText: '删除',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', `/agent/${agent.id}/qa/${b.dataset.qrm}`);
            toast('已删除');
            renderKbTab('qa');
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
  });
}

function showQaModal(row) {
  const agent = activeAgent();
  const isEdit = !!row;
  openModal({
    title: isEdit ? '编辑问答' : '添加问答',
    body: `
      <div class="field" style="margin-bottom:12px"><label>问题 <span class="req">*</span></label>
        <textarea id="qa-q" rows="2" placeholder="用户可能会问的问题(将参与向量召回)">${esc(row ? row.question : '')}</textarea></div>
      <div class="field"><label>答案(可后补)</label>
        <textarea id="qa-a" rows="5" placeholder="标准答案 / 分析步骤 / 口径说明">${esc(row ? row.content || '' : '')}</textarea></div>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>${isEdit ? '保存' : '添加并同步'}</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const payload = {
          question: $('#qa-q', bodyEl).value.trim(),
          content: $('#qa-a', bodyEl).value.trim(),
        };
        if (!payload.question) return toast('问题不能为空', true);
        try {
          if (isEdit) await api('POST', `/agent/${agent.id}/qa/${row.id}`, payload);
          else await api('POST', `/agent/${agent.id}/qa`, payload);
          toast(isEdit ? '已保存(仅问题变化才重入向量)' : '已添加,向量同步中');
          close();
          renderKbTab('qa');
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

/* ================= 检索测试 ================= */
function renderKbRetrieve(body) {
  body.innerHTML = `
    <div class="card">
      <h2>知识检索联调</h2>
      <div class="row">
        <input id="rt-q" class="grow" placeholder="检索问题,如:合同终止条件 / 订单口径 / 复购率">
        <input id="rt-k" type="number" min="1" max="20" value="5" title="返回条数 topK">
        <button class="btn" id="rt-run">检索</button>
      </div>
      <div class="dim small" style="margin-top:6px">验证文档 / 术语 / 问答 / 表是否可被向量召回(需已配置 EMBEDDING 模型)</div>
    </div>
    <div class="card" id="rt-result"><div class="empty">输入问题后点「检索」</div></div>`;

  const run = async () => {
    const q = $('#rt-q').value.trim();
    if (!q) return toast('先输入检索问题', true);
    const k = Number($('#rt-k').value) || 5;
    const box = $('#rt-result');
    box.innerHTML = '<div class="empty">检索中…</div>';
    try {
      const hits = await api('GET', `/agent/${activeAgent().id}/retrieve?query=${encodeURIComponent(q)}&topK=${k}`);
      box.innerHTML =
        hits
          .map((h) => {
            const text = (h.content || '').replace(/\s+/g, ' ');
            const extra = Object.entries(h.extra || {})
              .map(([key, v]) => `${key}: ${v}`)
              .join(' · ');
            return `<div class="hit">
          <span class="chip blue">${SOURCE_LABELS[h.sourceType] || h.sourceType} #${h.sourceId}</span>
          <div class="hit-body">
            <div class="hit-text">${esc(text.length > 260 ? text.slice(0, 260) + '…' : text)}</div>
            <div class="hit-meta">${esc(extra)}${extra ? ' · ' : ''}score ${Number(h.score).toFixed(4)}</div>
          </div>
        </div>`;
          })
          .join('') || '<div class="empty">无召回:检查向量化状态、嵌入模型配置与重试未完成行</div>';
    } catch (e) {
      box.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
    }
  };
  $('#rt-run').onclick = run;
  $('#rt-q').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') run();
  });
}

const SOURCE_LABELS = { BIZ_TABLE: '表', BIZ_TERM: '术语', DOCUMENT: '文档', QA: '问答' };
