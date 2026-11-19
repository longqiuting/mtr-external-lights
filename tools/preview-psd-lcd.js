#!/usr/bin/env node
// 厦门地铁 PSD-LCD 挂板的离线预览：用**渲染器同一份** Java2D 绘制代码出 PNG。
//
//   v1.20.1/.../pids/PsdLcdPainter.java    ← 排版 + 绘制（不 import 任何 MC 类）
//   v1.20.1/.../pids/PsdLcdTypeface.java   ← 字体解析（同一套兜底）
//   tools/psd-lcd/PsdLcdPreview.java       ← 示例数据 + 出图
//
// 渲染器（PsdLcdRenderer）把同一份 paint() 的结果贴到面板上，所以预览图长什么样，
// 游戏里就是什么样。
//
// 用法：
//   node tools/preview-psd-lcd.js                  # 两张：6.9:1 与 13:1（游戏里 4 段）
//   node tools/preview-psd-lcd.js --ratio=6.91     # 只出一张
//   node tools/preview-psd-lcd.js --height=400     # 换输出像素高
//   node tools/preview-psd-lcd.js --out=temp/x.png
//   node tools/preview-psd-lcd.js --fonts=<目录>    # 指定字体目录（默认自动找资源包里的）
'use strict';

const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const repoRoot = path.resolve(__dirname, '..');
const pkg = path.join(repoRoot, 'v1.20.1/src/main/java/dev/js/mtrheadlight/xiamen');
const sources = [
    path.join(pkg, 'pids/PsdLcdPainter.java'),
    path.join(pkg, 'pids/PsdLcdTypeface.java'),
    // 字体解析委托给它（PsdLcdTypeface → XiamenStripPainter#font()）：同一套系统黑体
    path.join(pkg, 'XiamenStripPainter.java'),
    path.join(repoRoot, 'tools/psd-lcd/PsdLcdPreview.java'),
];
const classesDir = path.join(repoRoot, 'temp/psd-lcd-preview-classes');

function arg(name, fallback) {
    const hit = process.argv.find((v) => v === `--${name}` || v.startsWith(`--${name}=`));
    if (!hit) {
        return fallback;
    }
    const eq = hit.indexOf('=');
    return eq < 0 ? true : hit.slice(eq + 1);
}

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
    for (const file of sources) {
        if (!fs.existsSync(file)) {
            throw new Error(`缺少源文件：${file}`);
        }
    }
    fs.mkdirSync(classesDir, { recursive: true });

    const javaHome = findJavaHome();
    const bin = javaHome ? path.join(javaHome, 'bin') : null;
    const javac = bin ? path.join(bin, process.platform === 'win32' ? 'javac.exe' : 'javac') : 'javac';
    const java = bin ? path.join(bin, process.platform === 'win32' ? 'java.exe' : 'java') : 'java';

    run(javac, ['-encoding', 'UTF-8', '-d', classesDir, ...sources]);

    const height = Number(arg('height', 320));
    const fonts = arg('fonts', null);
    const out = arg('out', null);
    const ratio = arg('ratio', null);
    const cars = arg('cars', null);

    // 默认出三张：
    //   · 6.9:1 + 4 节   —— 对实拍用的正片（正常卡片宽度）
    //   · 6.9:1 + 8 节   —— 验证长编组的逐节压缩（卡片缩窄、整行不溢出、不盖广告位）
    //   · 13:1 + 4 节    —— 游戏里 4 段挂板的真实比例与分辨率
    const jobs = ratio
        ? [{ ratio: Number(ratio), out: out || 'temp/psd-lcd-preview.png', cars: cars || 4 }]
        : [
            { ratio: 6.91, out: out || 'temp/psd-lcd-preview.png', cars: 4 },
            { ratio: 6.91, out: 'temp/psd-lcd-preview-8cars.png', cars: 8 },
            { ratio: 13.0, out: 'temp/psd-lcd-preview-13x1.png', cars: 4 },
        ];

    for (const job of jobs) {
        const target = path.resolve(repoRoot, String(job.out));
        fs.mkdirSync(path.dirname(target), { recursive: true });
        const args = ['-Djava.awt.headless=true', '-Dfile.encoding=UTF-8', '-cp', classesDir,
            'dev.js.mtrheadlight.xiamen.pids.PsdLcdPreview', target, String(job.ratio),
            String(height), String(fonts || ''), String(job.cars)];
        process.stdout.write(run(java, args));
    }
}

main();
