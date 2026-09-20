/* ============================================================
 * markdown.js —— 零依赖 mini markdown 渲染器(报告正文用)
 * 先整体转义(防 XSS)再转换:标题 / 代码块 / 行内码 / 粗斜体 /
 * 链接 / 列表 / 表格 / 引用 / 分割线 / 段落
 * ============================================================ */

function mdInline(s) {
  return s
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\*([^*]+)\*/g, '<em>$1</em>')
    .replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
}

function mdIsBlockStart(line) {
  return (
    /^```/.test(line) ||
    /^(#{1,4})\s/.test(line) ||
    /^\s*[-*+]\s/.test(line) ||
    /^\s*\d+[.、]\s/.test(line) ||
    /^\s*\|.*\|\s*$/.test(line) ||
    /^&gt;/.test(line) ||
    /^\s*(-{3,}|\*{3,})\s*$/.test(line)
  );
}

function mdSplitRow(line) {
  return line
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim());
}

function renderMarkdown(src) {
  if (!src) return '';
  const lines = esc(String(src).replace(/\r\n/g, '\n')).split('\n');
  const out = [];
  let i = 0;
  let listType = null;
  let inCode = false;
  let codeBuf = [];

  const closeList = () => {
    if (listType) {
      out.push(`</${listType}>`);
      listType = null;
    }
  };

  while (i < lines.length) {
    const line = lines[i];

    /* 代码块 */
    const fence = line.match(/^```(\w*)\s*$/);
    if (fence) {
      if (!inCode) {
        inCode = true;
        codeBuf = [];
      } else {
        inCode = false;
        out.push(`<pre><code>${codeBuf.join('\n')}</code></pre>`);
      }
      i++;
      continue;
    }
    if (inCode) {
      codeBuf.push(line);
      i++;
      continue;
    }

    /* 表格:| a | b | + | --- | 分隔行 */
    if (/^\s*\|.*\|\s*$/.test(line) && i + 1 < lines.length && /^\s*\|[\s:|-]+\|\s*$/.test(lines[i + 1])) {
      closeList();
      const heads = mdSplitRow(line);
      i += 2;
      const rows = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i])) {
        rows.push(mdSplitRow(lines[i]));
        i++;
      }
      out.push(
        `<table><thead><tr>${heads.map((h) => `<th>${mdInline(h)}</th>`).join('')}</tr></thead><tbody>${rows
          .map((r) => `<tr>${r.map((c) => `<td>${mdInline(c)}</td>`).join('')}</tr>`)
          .join('')}</tbody></table>`
      );
      continue;
    }

    /* 标题 */
    const h = line.match(/^(#{1,4})\s+(.*)$/);
    if (h) {
      closeList();
      const lv = h[1].length;
      out.push(`<h${lv}>${mdInline(h[2])}</h${lv}>`);
      i++;
      continue;
    }

    /* 分割线 */
    if (/^\s*(-{3,}|\*{3,})\s*$/.test(line)) {
      closeList();
      out.push('<hr>');
      i++;
      continue;
    }

    /* 引用 */
    const q = line.match(/^&gt;\s?(.*)$/);
    if (q) {
      closeList();
      out.push(`<blockquote>${mdInline(q[1])}</blockquote>`);
      i++;
      continue;
    }

    /* 无序列表 */
    const ul = line.match(/^\s*[-*+]\s+(.*)$/);
    if (ul) {
      if (listType !== 'ul') {
        closeList();
        out.push('<ul>');
        listType = 'ul';
      }
      out.push(`<li>${mdInline(ul[1])}</li>`);
      i++;
      continue;
    }

    /* 有序列表 */
    const ol = line.match(/^\s*\d+[.、]\s+(.*)$/);
    if (ol) {
      if (listType !== 'ol') {
        closeList();
        out.push('<ol>');
        listType = 'ol';
      }
      out.push(`<li>${mdInline(ol[1])}</li>`);
      i++;
      continue;
    }

    /* 空行 */
    if (!line.trim()) {
      closeList();
      i++;
      continue;
    }

    /* 段落(合并连续普通行) */
    closeList();
    const buf = [line];
    i++;
    while (i < lines.length && lines[i].trim() && !mdIsBlockStart(lines[i])) {
      buf.push(lines[i]);
      i++;
    }
    out.push(`<p>${mdInline(buf.join('<br>'))}</p>`);
  }

  if (inCode && codeBuf.length) {
    out.push(`<pre><code>${codeBuf.join('\n')}</code></pre>`);
  }
  closeList();
  return out.join('\n');
}
