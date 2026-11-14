// Stage-4: DrawContext-shaped render() overrides -> 1.18.2 MatrixStack signature.
const fs = require('fs');
const path = require('path');

const root = process.argv[2];

function walk(dir, out) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

const HEAD = /public void render\(\s*final DrawContext context\s*,\s*final int mouseX\s*,\s*final int mouseY\s*,\s*(?:final float delta|final float tickDelta)\s*\)\s*\{/;

let touched = 0;
for (const file of walk(root, [])) {
  if (file.includes('clientgui')) continue;
  let txt = fs.readFileSync(file, 'utf8');
  if (!txt.includes('DrawContext')) continue;
  const eol = txt.includes('\r\n') ? '\r\n' : '\n';
  const before = txt;

  txt = txt.replace(HEAD, (m) => {
    return 'public void render(final MatrixStack matrices, final int mouseX, final int mouseY,' + eol +
           '                        final float delta) {' + eol +
           '        // 1.18.2 的 Screen#render 收 MatrixStack（没有 1.20.1 的 DrawContext）；' + eol +
           '        // 替身包一层，屏幕内部的绘制代码一行不用改。' + eol +
           '        final DrawContext context = new DrawContext(matrices);';
  });

  // super.render(context, ...) / super.renderBackground(context)
  txt = txt.replace(/super\.render\(context,\s*/g, 'super.render(matrices, ');
  txt = txt.replace(/super\.renderBackground\(context\)/g, 'super.renderBackground(matrices)');

  if (txt !== before) {
    // make sure MatrixStack is imported
    if (!/^import net\.minecraft\.client\.util\.math\.MatrixStack;$/m.test(txt)) {
      // insert after the last net.minecraft.client import, else after package
      const lines = txt.split(/\r?\n/);
      let idx = -1;
      for (let i = 0; i < lines.length; i++) {
        if (/^import net\.minecraft\.client\./.test(lines[i])) idx = i;
      }
      if (idx < 0) {
        for (let i = 0; i < lines.length; i++) if (/^import /.test(lines[i])) idx = i;
      }
      if (idx < 0) {
        for (let i = 0; i < lines.length; i++) if (/^package /.test(lines[i])) idx = i;
      }
      lines.splice(idx + 1, 0, 'import net.minecraft.client.util.math.MatrixStack;');
      txt = lines.join(eol);
    }
    fs.writeFileSync(file, txt);
    touched++;
    console.log('rewrote ' + path.relative(root, file));
  }
}
console.log('files: ' + touched);
