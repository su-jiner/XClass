const fs = require('fs');
const path = require('path');

function walk(d, out = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (p.endsWith('.wxml')) out.push(p);
  }
  return out;
}

const files = walk('D:/Work/Classxiao/miniprogram');
const VOID = ['input', 'image', 'textarea'];
const bad = [];
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8').replace(/<!--[\s\S]*?-->/g, '');
  const stack = [];
  const re = /<\/?([a-zA-Z][\w-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)\/?>/g;
  let m;
  while ((m = re.exec(src)) !== null) {
    const full = m[0];
    const tag = m[1];
    if (full.startsWith('</')) {
      const open = stack.pop();
      if (open !== tag) bad.push(f + ': </' + tag + '> closes but top is <' + open + '>');
    } else if (!full.endsWith('/>') && !VOID.includes(tag)) {
      stack.push(tag);
    }
  }
  if (stack.length) bad.push(f + ': unclosed <' + stack.join(',<') + '>');
}
console.log(bad.length ? bad.join('\n') : 'ALL WXML BALANCED (' + files.length + ' files)');
