/* ============================================================
 * datasources.js —— 数据连接:业务库配置 CRUD、表结构浏览、逻辑外键(表关系)
 * 对应 /bizdatabase/configs|tables|columns|relations 全部端点
 * ============================================================ */

const REL_TYPES = [
  ['ONE_TO_ONE', '1:1'],
  ['ONE_TO_MANY', '1:N'],
  ['MANY_TO_ONE', 'N:1'],
  ['MANY_TO_MANY', 'N:M'],
];

const relTypeLabel = (t) => (REL_TYPES.find(([v]) => v === t) || [t, t])[1];

async function mountDatasources(view) {
  view.innerHTML = `
    <div class="page-head">
      <div>
        <h1>数据连接</h1>
        <p>管理业务数据库连接与表结构;「表关系(逻辑外键)」用于跨表 join 推断,是 NL2SQL 准确率的关键输入</p>
      </div>
      <div class="page-actions">
        <button class="btn secondary" id="ds-refresh">刷新</button>
        <button class="btn" id="ds-add">＋ 添加数据源</button>
      </div>
    </div>
    <div id="ds-list"><div class="empty">加载中…</div></div>`;

  $('#ds-add').onclick = () => showDbModal(null);
  $('#ds-refresh').onclick = () => loadDsList();
  await loadDsList();
}

async function loadDsList() {
  const box = $('#ds-list');
  if (!box) return;
  let list = [];
  try {
    list = await api('GET', '/bizdatabase/configs');
  } catch (e) {
    box.innerHTML = `<div class="empty">加载失败:${esc(e.message)}</div>`;
    return;
  }
  store.dbs = list;
  box.innerHTML = `
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>名称</th><th>类型</th><th>连接串</th><th>描述</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          ${
            list
              .map(
                (d) => `<tr>
            <td><div class="cell-main">${esc(d.name)}</div><div class="cell-sub">#${d.id}</div></td>
            <td><span class="chip">${esc(d.dbType)}</span></td>
            <td class="dim mono" style="max-width:300px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap" title="${esc(d.connectionUrl)}">${esc(d.connectionUrl)}</td>
            <td class="dim">${esc(d.description || '—')}</td>
            <td><div class="actions">
              <button class="btn link" data-tables="${d.id}" data-name="${esc(d.name)}">表结构</button>
              <button class="btn link" data-rels="${d.id}" data-name="${esc(d.name)}">表关系</button>
              <button class="btn link" data-edit="${d.id}">编辑</button>
              <button class="btn link danger" data-del="${d.id}" data-name="${esc(d.name)}">删除</button>
            </div></td>
          </tr>`
              )
              .join('') || '<tr><td colspan="5" class="empty">暂无数据源,点右上角「添加数据源」</td></tr>'
          }
        </tbody>
      </table>
    </div>`;

  $$('[data-tables]').forEach((b) => (b.onclick = () => showTablesModal(Number(b.dataset.tables), b.dataset.name)));
  $$('[data-rels]').forEach((b) => (b.onclick = () => showRelationsModal(Number(b.dataset.rels), b.dataset.name)));
  $$('[data-edit]').forEach((b) => {
    const row = list.find((d) => String(d.id) === b.dataset.edit);
    b.onclick = () => showDbModal(row);
  });
  $$('[data-del]').forEach((b) => {
    b.onclick = () =>
      confirmBox({
        title: '删除数据源',
        message: `删除「${b.dataset.name}」?该库全部表关系与连接池将一并清理。`,
        confirmText: '删除',
        danger: true,
        onConfirm: async () => {
          try {
            await api('DELETE', `/bizdatabase/configs/${b.dataset.del}`);
            toast('已删除');
            loadDsList();
          } catch (e) {
            toast(e.message, true);
          }
        },
      });
  });
}

/* ===== 添加 / 编辑数据源 ===== */
function showDbModal(row) {
  const isEdit = !!row;
  openModal({
    title: isEdit ? '编辑数据源' : '添加数据源',
    body: `
      <div class="form-grid">
        <div class="field"><label>名称 <span class="req">*</span></label><input id="db-name" value="${esc(row ? row.name : '')}" placeholder="如:演示业务库"></div>
        <div class="field"><label>类型 <span class="req">*</span></label>
          <select id="db-type"><option value="MYSQL">MYSQL</option></select></div>
        <div class="field" style="grid-column:1/-1"><label>JDBC 连接串 <span class="req">*</span></label>
          <input id="db-url" value="${esc(row ? row.connectionUrl : '')}" placeholder="jdbc:mysql://mysql:3306/biz_demo"></div>
        <div class="field"><label>用户名 <span class="req">*</span></label><input id="db-user" value="${esc(row ? row.username : '')}"></div>
        <div class="field"><label>密码${isEdit ? '(留空 = 不修改)' : ' <span class="req">*</span>'}</label>
          <input type="password" id="db-pass" placeholder="${isEdit ? '留空保持原密码' : ''}"></div>
        <div class="field" style="grid-column:1/-1"><label>描述</label><input id="db-desc" value="${esc(row ? row.description || '' : '')}"></div>
      </div>
      <p class="dim small" style="margin:10px 0 0">保存时会先直连探测连通性(失败即拒绝保存)。</p>`,
    footer: `<button class="btn secondary" type="button" data-cancel>取消</button><button class="btn" type="button" data-ok>${isEdit ? '保存修改' : '保存'}</button>`,
    onMount: (bodyEl, footEl, close) => {
      $('[data-cancel]', footEl).onclick = close;
      $('[data-ok]', footEl).onclick = async () => {
        const payload = {
          name: $('#db-name', bodyEl).value.trim(),
          dbType: $('#db-type', bodyEl).value,
          connectionUrl: $('#db-url', bodyEl).value.trim(),
          username: $('#db-user', bodyEl).value.trim(),
          password: $('#db-pass', bodyEl).value,
          description: $('#db-desc', bodyEl).value.trim(),
        };
        if (!payload.name || !payload.connectionUrl || !payload.username) return toast('名称 / 连接串 / 用户名必填', true);
        try {
          if (isEdit) {
            await api('POST', `/bizdatabase/configs/${row.id}`, payload);
            toast('已保存修改(连通性已探测)');
          } else {
            await api('POST', '/bizdatabase/configs', payload);
            toast('已保存(连通性已探测)');
          }
          close();
          loadDsList();
        } catch (e) {
          toast(e.message, true);
        }
      };
    },
  });
}

/* ===== 表结构浏览(左表清单 / 右列清单)===== */
function showTablesModal(dbId, dbName) {
  openModal({
    title: `表结构 · ${dbName}`,
    large: true,
    body: `<div class="split">
      <div class="left" id="tbl-left"><div class="empty">加载中…</div></div>
      <div class="right" id="tbl-right"><div class="empty">从左侧选择一张表查看字段</div></div>
    </div>`,
    onMount: async (bodyEl) => {
      const left = $('#tbl-left', bodyEl);
      const right = $('#tbl-right', bodyEl);
      try {
        const tables = await api('GET', `/bizdatabase/configs/${dbId}/tables`);
        left.innerHTML =
          tables
            .map(
              (t) => `<div class="titem" data-t="${esc(t.name)}" title="${esc(t.comment || '')}">${esc(t.name)}
            ${t.comment ? `<span class="cmt">${esc(t.comment)}</span>` : ''}</div>`
            )
            .join('') || '<div class="empty">该库暂无表</div>';
        const loadCols = async (name, el) => {
          $$('.titem', left).forEach((i) => i.classList.remove('active'));
          el.classList.add('active');
          right.innerHTML = '<div class="empty">加载中…</div>';
          try {
            const cols = await api(
              'GET',
              `/bizdatabase/configs/${dbId}/tables/${encodeURIComponent(name)}/columns`
            );
            right.innerHTML = `<table class="tbl">
              <thead><tr><th>列名</th><th>类型</th><th>可空</th><th>注释</th></tr></thead>
              <tbody>${cols
                .map(
                  (c) => `<tr><td class="cell-main mono">${esc(c.name)}</td><td class="dim mono">${esc(c.type)}</td>
                <td class="dim">${c.nullable ? '是' : '否'}</td><td class="dim">${esc(c.comment || '')}</td></tr>`
                )
                .join('')}</tbody></table>`;
          } catch (e) {
            right.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
          }
        };
        $$('.titem', left).forEach((el) => (el.onclick = () => loadCols(el.dataset.t, el)));
        const first = $('.titem', left);
        if (first) loadCols(first.dataset.t, first);
      } catch (e) {
        left.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
      }
    },
  });
}

/* ===== 逻辑外键(表关系)===== */
function showRelationsModal(dbId, dbName) {
  openModal({
    title: `逻辑外键配置 · ${dbName}`,
    large: true,
    body: `
      <div id="rel-list"><div class="empty">加载中…</div></div>
      <div class="rel-add">
        <div class="rel-flow">
          <div class="field"><label>主表</label><select id="rel-st"><option value="">选择主表</option></select></div>
          <div class="arrow">→</div>
          <div class="field"><label>关联表</label><select id="rel-tt"><option value="">选择关联表</option></select></div>
        </div>
        <div class="rel-cols">
          <div class="field"><label>主表字段</label><select id="rel-sc" disabled><option value="">先选主表</option></select></div>
          <div class="field"><label>关联字段</label><select id="rel-tc" disabled><option value="">先选关联表</option></select></div>
        </div>
        <div class="row" style="margin-top:10px">
          <select id="rel-type">
            ${REL_TYPES.map(([v, l]) => `<option value="${v}" ${v === 'MANY_TO_ONE' ? 'selected' : ''}>${l}(${v})</option>`).join('')}
          </select>
          <span class="spacer"></span>
          <button class="btn" id="rel-add-btn" type="button">添加关系</button>
        </div>
      </div>`,
    onMount: async (bodyEl) => {
      const listBox = $('#rel-list', bodyEl);
      let tables = [];
      let relations = [];

      const loadColumns = async (table, sel, placeholder) => {
        sel.disabled = true;
        sel.innerHTML = `<option value="">加载中…</option>`;
        if (!table) {
          sel.innerHTML = `<option value="">${placeholder}</option>`;
          return;
        }
        try {
          const cols = await api('GET', `/bizdatabase/configs/${dbId}/tables/${encodeURIComponent(table)}/columns`);
          sel.innerHTML =
            `<option value="">选择字段</option>` +
            cols.map((c) => `<option value="${esc(c.name)}">${esc(c.name)}${c.comment ? ' — ' + esc(c.comment) : ''}</option>`).join('');
          sel.disabled = false;
        } catch (e) {
          sel.innerHTML = `<option value="">加载失败</option>`;
        }
      };

      const renderRels = () => {
        listBox.innerHTML = `
          <table class="tbl">
            <thead><tr><th>主表</th><th style="text-align:center">关系</th><th>关联表</th><th style="text-align:right">操作</th></tr></thead>
            <tbody>
              ${
                relations
                  .map(
                    (r) => `<tr>
                <td><span class="cell-main" style="color:var(--primary)">${esc(r.sourceTableName)}</span>
                  <span class="dim mono">.${esc(r.sourceColumnName)}</span></td>
                <td style="text-align:center"><span class="chip blue">${relTypeLabel(r.relationType)}</span></td>
                <td><span class="cell-main" style="color:#047857">${esc(r.targetTableName)}</span>
                  <span class="dim mono">.${esc(r.targetColumnName)}</span></td>
                <td><div class="actions"><button class="btn link danger" data-rrm="${r.id}">删除</button></div></td>
              </tr>`
                  )
                  .join('') || '<tr><td colspan="4" class="empty">暂无逻辑外键配置;下方添加后将在分析链路中生效</td></tr>'
              }
            </tbody>
          </table>`;
        $$('[data-rrm]', listBox).forEach((b) => {
          b.onclick = () =>
            confirmBox({
              title: '删除关系',
              message: '删除该逻辑外键?',
              confirmText: '删除',
              danger: true,
              onConfirm: async () => {
                try {
                  await api('DELETE', `/bizdatabase/relations/${b.dataset.rrm}`);
                  toast('已删除');
                  await refresh();
                } catch (e) {
                  toast(e.message, true);
                }
              },
            });
        });
      };

      const refresh = async () => {
        relations = await api('GET', `/bizdatabase/relations?databaseConfigId=${dbId}`);
        renderRels();
      };

      try {
        tables = await api('GET', `/bizdatabase/configs/${dbId}/tables`);
      } catch (e) {
        listBox.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
        return;
      }
      const opts = tables.map((t) => `<option value="${esc(t.name)}">${esc(t.name)}${t.comment ? ' — ' + esc(t.comment) : ''}</option>`).join('');
      $('#rel-st', bodyEl).innerHTML = `<option value="">选择主表</option>${opts}`;
      $('#rel-tt', bodyEl).innerHTML = `<option value="">选择关联表</option>${opts}`;

      $('#rel-st', bodyEl).onchange = (e) => loadColumns(e.target.value, $('#rel-sc', bodyEl), '先选主表');
      $('#rel-tt', bodyEl).onchange = (e) => loadColumns(e.target.value, $('#rel-tc', bodyEl), '先选关联表');
      $('#rel-add-btn', bodyEl).onclick = async () => {
        const payload = {
          databaseConfigId: dbId,
          sourceTableName: $('#rel-st', bodyEl).value,
          sourceColumnName: $('#rel-sc', bodyEl).value,
          targetTableName: $('#rel-tt', bodyEl).value,
          targetColumnName: $('#rel-tc', bodyEl).value,
          relationType: $('#rel-type', bodyEl).value,
          description: '',
        };
        if (!payload.sourceTableName || !payload.sourceColumnName || !payload.targetTableName || !payload.targetColumnName)
          return toast('主表 / 字段与关联表 / 字段都需选择', true);
        try {
          await api('POST', '/bizdatabase/relations', payload);
          toast('已添加关系');
          $('#rel-sc', bodyEl).innerHTML = '<option value="">先选主表</option>';
          $('#rel-tc', bodyEl).innerHTML = '<option value="">先选关联表</option>';
          await refresh();
        } catch (e) {
          toast(e.message, true);
        }
      };

      await refresh();
    },
  });
}
