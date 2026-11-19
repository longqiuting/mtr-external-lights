#!/usr/bin/env node
// 厦门地铁 PSD-LCD 挂板的离线预览：把渲染器用的那张「排版指令表」画成一张 PNG。
//
// 为什么还要调 Java：排版（src/main/java/.../xiamen/pids/PsdLcdLayout.java）是游戏和预览
// 共用的唯一一份代码，而它是 Java。这个脚本只做三件事：
//   1. 找 JDK（JAVA_HOME → 本机常见安装位置）；
//   2. 用 javac 把 PsdLcdLayout.java + tools/psd-lcd/PsdLcdPreview.java 编到 temp/ 下面；
//   3. 跑 PsdLcdPreview，输出 temp/psd-lcd-preview.png（尺寸 = 520×段数 × 160，四段正好 13:1）。
//
// 用法：
//   node tools/preview-psd-lcd.js                 # 4 段（13:1），写 temp/psd-lcd-preview.png
//   node tools/preview-psd-lcd.js --segments=6    # 其它段数
//   node tools/preview-psd-lcd.js --out=temp/x.png
'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..');
const layoutSource = path.join(
    repoRoot, 'v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen/pids/PsdLcdLayout.java');
const previewSource = path.join(repoRoot, 'tools/psd-lcd/PsdLcdPreview.java');

function arg(name, fallback) {
    const hit = process.argv.find((value) => value === `--${name}` || value.startsWith(`--${name}=`));
    if (!hit) {
        return fallback;
    }
    const eq = hit.indexOf('=');
    return eq < 0 ? true : hit.slice(eq + 1);
}

const segments = Number(arg('segments', 4));
const out = path.resolve(repoRoot, String(arg('out', 'temp/psd-lcd-preview.png')));
const classesDir = path.join(repoRoot, 'temp/psd-lcd-preview-classes');

function findJavaHome() {
    const candidates = [
        process.env.JAVA_HOME,
        'C:/Program Files/Java/jdk-21.0.10',
        'C:/Program Files/Java/jdk-17.0.9',
        'C:/Program Files/Java/jdk-17',
        '/usr/lib/jvm/default-java',
        '/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home',
    ].filter(Boolean);
    for (const home of candidates) {
        const javac = path.join(home, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
        if (fs.existsSync(javac)) {
            return home;
        }
    }
    // 再退一步：PATH 上的 javac
    const probe = spawnSync('javac', ['-version'], { encoding: 'utf8' });
    if (probe.status === 0) {
        return null;
    }
    throw new Error('找不到 JDK：请设置 JAVA_HOME，或装一个 JDK 17+');
}

function run(command, args) {
    const result = spawnSync(command, args, { encoding: 'utf8' });
    if (result.status !== 0) {
        process.stderr.write(result.stdout || '');
        process.stderr.write(result.stderr || '');
        throw new Error(`${command} 失败（exit ${result.status}）`);
    }
    return result.stdout;
}

function main() {
    if (!Number.isInteger(segments) || segments < 1 || segments > 32) {
        throw new Error(`--segments 必须是 1..32 的整数（收到 ${segments}）`);
    }
    for (const file of [layoutSource, previewSource]) {
        if (!fs.existsSync(file)) {
            throw new Error(`缺少源文件：${file}`);
        }
    }
    fs.mkdirSync(classesDir, { recursive: true });

    const javaHome = findJavaHome();
    const bin = javaHome ? path.join(javaHome, 'bin') : null;
    const javac = bin ? path.join(bin, process.platform === 'win32' ? 'javac.exe' : 'javac') : 'javac';
    const java = bin ? path.join(bin, process.platform === 'win32' ? 'java.exe' : 'java') : 'java';

    // 只编这两个文件：PsdLcdLayout 不 import 任何 Minecraft 类，所以不需要整份模组源码。
    run(javac, ['-encoding', 'UTF-8', '-d', classesDir, layoutSource, previewSource]);
    const stdout = run(java, ['-Djava.awt.headless=true', '-cp', classesDir,
        'dev.js.mtrheadlight.xiamen.pids.PsdLcdPreview', out, String(segments)]);
    process.stdout.write(stdout);
    console.log(`preview: ${out}`);
}

main();
