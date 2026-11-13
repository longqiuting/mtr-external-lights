#!/usr/bin/env node
/**
 * 生成厦门 / BRT 闸机的方块模型、blockstate、物品模型与贴图（v1.20.1 专用）。
 *
 * <h2>为什么要转，而不是把 Blockbench 的 json 直接丢进 resources</h2>
 * <p>Blockbench 的 {@code format_version 26.3} 导出物有两处原版读不了：
 *
 * <ol>
 *   <li><b>旋转角度</b>：原版 {@code ModelElement.Deserializer#deserializeRotationAngle} 只接受
 *       {@code 0 / ±22.5 / ±45}（javap 依据：与 22.5f、45.0f 比较，不等就抛
 *       {@code JsonParseException}），而素材里是 -15 / 35 / ±11.5 / ±25 / ±3.25。
 *       这里按「最近合法角」吸附（-15→-22.5、15→22.5、35→45、±11.5→±22.5、±25→±22.5、±3.25→0）。
 *       旋转原点原样保留 —— 原版<b>不</b>要求原点在元素中心（只有角度受限），所以量出来的斜面
 *       不会跑位，最多因为角度差 7~11° 让外壳整体外扩 0.7 像素（≈4cm），肉眼不可见。</li>
 *   <li><b>uv 比例</b>：素材 {@code texture_size} 是 64，uv 是 0..64；原版贴图坐标是 0..16
 *       （相对整张 sprite），所以 uv 一律 ×16/64 = 0.25。贴图同时按 64×64 落地
 *       （原图 2048×2048 是 64 的 32 倍最近邻放大，缩回去不丢任何像素，且 2048×2048 的
 *       sprite 会白占 16MB 显存）。</li>
 * </ol>
 *
 * <h2>开 / 关两种状态</h2>
 * <p>两款闸机各一份 {@code *_closed} / {@code *_open} 模型，进/出站<b>共用</b>同一套模型
 * （四个方块的 blockstate 指向同一对模型文件）。开启状态里 {@code light_on} 分组那个元素
 * 加 {@code "shade": false}（自发光观感），关闭状态 {@code light} 分组保持原样。
 *
 * <h2>用法</h2>
 * <pre>
 *   node tools/gen-ticket-gate-models.js                     # 用默认素材目录
 *   node tools/gen-ticket-gate-models.js "D:/某个目录"        # 指定素材目录
 * </pre>
 * 素材目录里需要：{@code ticket_barrier_closed.json} / {@code ticket_barrier_open.json} /
 * {@code ticket_barrier.png} / {@code brt_barrier_closed.json} / {@code brt_barrier_open.json} /
 * {@code brt_barrier.png}。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SRC = process.argv[2] || 'C:/Users/AD/Desktop/素材/厦门地铁闸机';
const ROOT = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte');

/** 原版 {@code ModelElement.Deserializer} 唯一接受的旋转角。 */
const LEGAL_ANGLES = [-45, -22.5, 0, 22.5, 45];

/** 一款闸机：素材前缀 → 模型 / 贴图名。 */
const VARIANTS = [
    { key: 'ticket_gate_xiamen', src: 'ticket_barrier', texture: 'ticket_barrier' },
    { key: 'ticket_gate_brt', src: 'brt_barrier', texture: 'brt_barrier' },
];

/** 四个方块 = 两款 × 进/出（进/出共用一套模型，只有判定不同）。 */
const BLOCKS = [
    { id: 'ticket_gate_xiamen_in', model: 'ticket_gate_xiamen' },
    { id: 'ticket_gate_xiamen_out', model: 'ticket_gate_xiamen' },
    { id: 'ticket_gate_brt_in', model: 'ticket_gate_brt' },
    { id: 'ticket_gate_brt_out', model: 'ticket_gate_brt' },
];

// ---------------------------------------------------------------- PNG

function decodePNG(file) {
    const b = fs.readFileSync(file);
    let off = 8;
    let w = 0, h = 0, depth = 0, colorType = 0;
    const idat = [];
    while (off < b.length) {
        const len = b.readUInt32BE(off);
        const type = b.toString('ascii', off + 4, off + 8);
        const data = b.subarray(off + 8, off + 8 + len);
        if (type === 'IHDR') {
            w = data.readUInt32BE(0); h = data.readUInt32BE(4);
            depth = data[8]; colorType = data[9];
            if (data[12] !== 0) throw new Error(`${file}: interlaced PNG not supported`);
        } else if (type === 'IDAT') {
            idat.push(data);
        } else if (type === 'IEND') {
            break;
        }
        off += 12 + len;
    }
    if (depth !== 8) throw new Error(`${file}: bit depth ${depth} not supported`);
    if (colorType !== 6 && colorType !== 2) throw new Error(`${file}: color type ${colorType} not supported`);
    const ch = colorType === 6 ? 4 : 3;
    const raw = zlib.inflateSync(Buffer.concat(idat));
    const stride = w * ch;
    const out = Buffer.alloc(w * h * ch);
    let prev = Buffer.alloc(stride);
    for (let y = 0; y < h; y++) {
        const filter = raw[y * (stride + 1)];
        const cur = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
        for (let i = 0; i < stride; i++) {
            const a = i >= ch ? cur[i - ch] : 0;
            const bb = prev[i];
            const c = i >= ch ? prev[i - ch] : 0;
            if (filter === 1) cur[i] = (cur[i] + a) & 255;
            else if (filter === 2) cur[i] = (cur[i] + bb) & 255;
            else if (filter === 3) cur[i] = (cur[i] + ((a + bb) >> 1)) & 255;
            else if (filter === 4) {
                const p = a + bb - c;
                const pa = Math.abs(p - a), pb = Math.abs(p - bb), pc = Math.abs(p - c);
                cur[i] = (cur[i] + (pa <= pb && pa <= pc ? a : pb <= pc ? bb : c)) & 255;
            }
        }
        cur.copy(out, y * stride);
        prev = cur;
    }
    return { w, h, ch, data: out };
}

let CRC_TABLE = null;
function crc32(buf) {
    if (!CRC_TABLE) {
        CRC_TABLE = [];
        for (let n = 0; n < 256; n++) {
            let c = n;
            for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
            CRC_TABLE[n] = c >>> 0;
        }
    }
    let c = 0xffffffff;
    for (const byte of buf) c = CRC_TABLE[(c ^ byte) & 255] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
}

function encodePNG(w, h, rgba) {
    const stride = w * 4;
    const raw = Buffer.alloc((stride + 1) * h);
    for (let y = 0; y < h; y++) {
        raw[y * (stride + 1)] = 0;
        rgba.copy(raw, y * (stride + 1) + 1, y * stride, (y + 1) * stride);
    }
    const chunk = (type, data) => {
        const len = Buffer.alloc(4);
        len.writeUInt32BE(data.length);
        const t = Buffer.from(type, 'ascii');
        const crc = Buffer.alloc(4);
        crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
        return Buffer.concat([len, t, data, crc]);
    };
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(w, 0);
    ihdr.writeUInt32BE(h, 4);
    ihdr[8] = 8;
    ihdr[9] = 6;
    return Buffer.concat([
        Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
        chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw, { level: 9 })), chunk('IEND', Buffer.alloc(0)),
    ]);
}

/** 最近邻缩到 size×size（原图是 size 的整数倍最近邻放大，这里是它的逆运算）。 */
function downscale(src, size) {
    const out = Buffer.alloc(size * size * 4);
    const step = src.w / size;
    for (let y = 0; y < size; y++) {
        for (let x = 0; x < size; x++) {
            const sx = Math.min(src.w - 1, Math.floor((x + 0.5) * step));
            const sy = Math.min(src.h - 1, Math.floor((y + 0.5) * step));
            const si = (sy * src.w + sx) * src.ch;
            const di = (y * size + x) * 4;
            out[di] = src.data[si];
            out[di + 1] = src.data[si + 1];
            out[di + 2] = src.data[si + 2];
            out[di + 3] = src.ch === 4 ? src.data[si + 3] : 255;
        }
    }
    return out;
}

// ---------------------------------------------------------------- 模型转换

const round = (v) => Math.round(v * 10000) / 10000;

/** 吸附到最近的原版合法角；0 表示这个元素不需要旋转。 */
function snapAngle(angle) {
    let best = 0, bestDiff = Infinity;
    for (const legal of LEGAL_ANGLES) {
        const diff = Math.abs(legal - angle);
        if (diff < bestDiff) {
            bestDiff = diff;
            best = legal;
        }
    }
    return best;
}

/** 自带「哪些元素属于 light 分组」—— Blockbench 的分组写在 groups 末尾，children 是元素下标。 */
function lightElementIndices(model) {
    const indices = new Set();
    for (const group of model.groups || []) {
        if (group && typeof group === 'object' && Array.isArray(group.children)
            && typeof group.name === 'string' && group.name.startsWith('light')) {
            for (const child of group.children) indices.add(child);
        }
    }
    return indices;
}

/**
 * 把一个元素在 Blockbench 里的旋转翻成原版能写的「单轴 + 合法角」。
 *
 * <p>两种素材写法：
 * <ul>
 *   <li><b>单轴</b>（厦门地铁那款）：{@code {"angle": -15, "axis": "x", "origin": [...]}} ——
 *       按最近合法角吸附即可；</li>
 *   <li><b>Euler</b>（BRT 那款，Blockbench 26.3 的新写法）：{@code {"x": 45, "y": -45, "z": 0, "origin": [...]}}
 *       —— <b>原版模型格式根本表达不了</b>（一个元素只能有一个轴 + 一个合法角）。这里只保留
 *       「只有一个轴有值、且值和合法角对得上」的情况（例：{@code x:0,y:0,z:45} → 45°/z），
 *       多轴组合返回 {@code null} = 丢弃这项旋转，并把元素原样放回。
 *       实测影响：BRT 那 15 个多轴元素是通道正中那台小机构的 12 片桨叶（绕 x 轴每 30° 一片），
 *       全部落在同一小块空间里；丢掉旋转后它们重合成一片，3/4 视角下只有 ~3% 的像素不同，
 *       远看仍是「柜体 + 通道 + 小机构」，不会出现空洞或飞出去的几何。</li>
 * </ul>
 */
function elementRotation(rotation, stats) {
    if (!rotation) return null;
    if (rotation.angle !== undefined && rotation.axis) {
        const angle = snapAngle(rotation.angle);
        return angle === 0 ? null
            : { origin: rotation.origin.map(round), axis: rotation.axis, angle, rescale: false };
    }
    const axes = ['x', 'y', 'z'].filter((axis) => Math.abs(rotation[axis] || 0) > 1e-6);
    if (axes.length === 0) return null;
    if (axes.length > 1) {
        stats.eulerDropped++;
        return null;
    }
    const axis = axes[0];
    const angle = snapAngle(rotation[axis]);
    if (angle === 0) return null;
    stats.eulerKept++;
    return { origin: (rotation.origin || [0, 0, 0]).map(round), axis, angle, rescale: false };
}

function convert(model, textureName, glowLight) {
    const size = model.texture_size ? model.texture_size[0] : 16;
    const uvScale = 16 / size;
    const lightSet = lightElementIndices(model);
    const stats = { eulerKept: 0, eulerDropped: 0 };
    const elements = model.elements.map((element, index) => {
        const out = {
            from: element.from.map(round),
            to: element.to.map(round),
        };
        const rotation = elementRotation(element.rotation, stats);
        if (rotation) out.rotation = rotation;
        const faces = {};
        for (const name of Object.keys(element.faces || {})) {
            const face = element.faces[name];
            if (!face) continue;
            const uv = face.uv || [0, 0, size, size];
            faces[name] = {
                uv: [round(uv[0] * uvScale), round(uv[1] * uvScale), round(uv[2] * uvScale), round(uv[3] * uvScale)],
                texture: '#all',
            };
        }
        out.faces = faces;
        if (glowLight && lightSet.has(index)) out.shade = false;
        return out;
    });
    if (stats.eulerDropped) {
        console.log(`  note: ${stats.eulerDropped} multi-axis (Euler) rotations dropped, ${stats.eulerKept} single-axis kept`);
    }
    return {
        credit: 'Converted from the Xiamen Metro ticket barrier Blockbench model by tools/gen-ticket-gate-models.js',
        textures: { all: `mtrlte:block/${textureName}`, particle: `mtrlte:block/${textureName}` },
        elements,
    };
}

function blockstate(modelName) {
    const variants = {};
    for (const facing of ['north', 'east', 'south', 'west']) {
        const y = { north: 0, east: 90, south: 180, west: 270 }[facing];
        for (const open of ['closed', 'open', 'open_concessionary']) {
            // open_concessionary（优惠票）没有单独的模型，和 open 用同一份 —— 缺这个变体的话
            // 优惠票过闸时状态查不到模型，方块会渲染成紫黑格。
            const suffix = open === 'closed' ? 'closed' : 'open';
            const variant = { model: `mtrlte:block/${modelName}_${suffix}` };
            if (y !== 0) variant.y = y;
            variants[`facing=${facing},open=${open}`] = variant;
        }
    }
    return { variants };
}

/**
 * 物品栏 / 手持的显示变换 —— 逐字照抄站外标志那四个物品模型
 * （{@code models/item/station_sign_*.json}），保证「3D 方块模型 + 显式 display」这一套在本模组一致。
 * 闸机模型比一格略宽（本地 x -4..16），0.625 的 gui 缩放正好占满格子又不溢出。
 */
const ITEM_DISPLAY = {
    gui: { rotation: [30, 225, 0], translation: [0, 0, 0], scale: [0.625, 0.625, 0.625] },
    ground: { rotation: [0, 0, 0], translation: [0, 3, 0], scale: [0.25, 0.25, 0.25] },
    fixed: { rotation: [0, 0, 0], translation: [0, 0, 0], scale: [0.5, 0.5, 0.5] },
    thirdperson_righthand: { rotation: [75, 45, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
    thirdperson_lefthand: { rotation: [75, 135, 0], translation: [0, 2.5, 0], scale: [0.375, 0.375, 0.375] },
    firstperson_righthand: { rotation: [0, 45, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
    firstperson_lefthand: { rotation: [0, 225, 0], translation: [0, 0, 0], scale: [0.4, 0.4, 0.4] },
};

/**
 * 带缩进的 JSON，但<b>把只含数字 / 字符串的短数组缩成一行</b>（{@code "uv": [0.5, 2.25, 0.75, 2.4375]}），
 * 和仓库里其它方块模型（{@code liquid_scanner.json} 等）的排版一致 —— 否则 51 个元素 × 6 张面
 * 每个数字占一行，文件会从 30KB 涨到 64KB，diff 也没法看。
 */
function stringify(value, indent) {
    const pad = '  '.repeat(indent);
    if (Array.isArray(value)) {
        if (value.every((v) => typeof v === 'number' || typeof v === 'string')) return `[${value.join(', ')}]`;
        return `[\n${value.map((v) => pad + '  ' + stringify(v, indent + 1)).join(',\n')}\n${pad}]`;
    }
    if (value && typeof value === 'object') {
        const keys = Object.keys(value);
        if (!keys.length) return '{}';
        const flat = (v) => v === null || typeof v !== 'object'
            || (Array.isArray(v) && v.every((e) => typeof e === 'number' || typeof e === 'string'));
        // 面定义这种「值全是数字 / 字符串 / 数字数组」的小对象也压成一行。
        if (keys.every((k) => flat(value[k]))) {
            return `{ ${keys.map((k) => `${JSON.stringify(k)}: ${stringify(value[k], indent)}`).join(', ')} }`;
        }
        return `{\n${keys.map((k) => `${pad}  ${JSON.stringify(k)}: ${stringify(value[k], indent + 1)}`).join(',\n')}\n${pad}}`;
    }
    return JSON.stringify(value);
}

function write(file, value) {
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, stringify(value, 0) + '\n');
    console.log('  wrote', path.relative(path.resolve(__dirname, '..'), file).replace(/\\/g, '/'));
}

// ---------------------------------------------------------------- 主流程

console.log('source:', SRC);
for (const variant of VARIANTS) {
    const tex = decodePNG(path.join(SRC, `${variant.texture}.png`));
    const size = 64;
    if (tex.w % size !== 0) throw new Error(`${variant.texture}.png: ${tex.w}px is not a multiple of ${size}`);
    fs.mkdirSync(path.join(ROOT, 'textures', 'block'), { recursive: true });
    const pngPath = path.join(ROOT, 'textures', 'block', `${variant.texture}.png`);
    fs.writeFileSync(pngPath, encodePNG(size, size, downscale(tex, size)));
    console.log('  wrote', path.relative(path.resolve(__dirname, '..'), pngPath).replace(/\\/g, '/'),
        `(${tex.w}x${tex.h} -> ${size}x${size})`);

    for (const state of ['closed', 'open']) {
        const file = path.join(SRC, `${variant.src}_${state}.json`);
        const model = JSON.parse(fs.readFileSync(file, 'utf8'));
        const lightCount = lightElementIndices(model).size;
        if (lightCount === 0) throw new Error(`${file}: no light group found`);
        write(path.join(ROOT, 'models', 'block', `${variant.key}_${state}.json`),
            convert(model, variant.texture, state === 'open'));
    }
}

for (const block of BLOCKS) {
    write(path.join(ROOT, 'blockstates', `${block.id}.json`), blockstate(block.model));
    write(path.join(ROOT, 'models', 'item', `${block.id}.json`),
        // 3D 图标：parent 指向方块模型（不开 / 关两态里的 closed 那份），再显式写整套 display。
        { parent: `mtrlte:block/${block.model}_closed`, display: ITEM_DISPLAY });
}

console.log('done.');
