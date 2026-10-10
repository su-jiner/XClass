const fs = require('fs');
const path = require('path');

const root = 'D:/Work/Classxiao/miniprogram/pages';
const pages = fs.readdirSync(root);
let problems = [];
for (const page of pages) {
  const dir = path.join(root, page);
  const wxmlPath = path.join(dir, page + '.wxml');
  const jsPath = path.join(dir, page + '.js');
  if (!fs.existsSync(wxmlPath) || !fs.existsSync(jsPath)) continue;
  const wxml = fs.readFileSync(wxmlPath, 'utf8');
  const js = fs.readFileSync(jsPath, 'utf8');
  const re = /(?:bind|catch)[a-z]+="([A-Za-z_$][\w$]*)"/g;
  let m;
  const used = new Set();
  while ((m = re.exec(wxml)) !== null) used.add(m[1]);
  for (const name of used) {
    const defined = new RegExp('(?:^|\\n)\\s*' + name + '\\s*[(:]', 'm').test(js)
      || new RegExp(name + '\\s*:\\s*function', 'm').test(js);
    if (!defined) problems.push(page + '.wxml -> ' + name + ' not defined in ' + page + '.js');
  }
}
// custom-tab-bar component
const tabWxml = root + '/../custom-tab-bar/index.wxml';
const tabJs = root + '/../custom-tab-bar/index.js';
if (fs.existsSync(tabWxml)) {
  const wxml = fs.readFileSync(tabWxml, 'utf8');
  const js = fs.readFileSync(tabJs, 'utf8');
  const re = /(?:bind|catch)[a-z]+="([A-Za-z_$][\w$]*)"/g;
  let m;
  const used = new Set();
  while ((m = re.exec(wxml)) !== null) used.add(m[1]);
  for (const name of used) {
    if (!new RegExp(name + '\\s*[(:]', 'm').test(js)) problems.push('custom-tab-bar -> ' + name);
  }
}
console.log(problems.length ? problems.join('\n') : 'ALL EVENT HANDLERS DEFINED');
