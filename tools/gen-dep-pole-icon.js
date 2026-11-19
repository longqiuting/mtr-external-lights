// 生成 DEP 挂杆的物品图标（等轴测渲染 dep_pole 模型，不复用任何贴图）
// 用法: node tools/gen-dep-pole-icon.js
const fs = require('fs');
const zlib = require('zlib');

const MODEL = 'v1.20.1/src/main/resources/assets/mtrlte/rawmodels/dep_pole.json';
const OUT = 'v1.20.1/src/main/resources/assets/mtrlte/textures/item/dep_pole.png';
const W = 96, H = 96, SS = 4; // 超采样
const BG = [0, 0, 0, 0];

const model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));

// ---- 变换 ----
const rad = d => d * Math.PI / 180;
const rotY = (p, a, o) => {
    const dx = p[0] - o[0], dz = p[2] - o[2], c = Math.cos(rad(a)), s = Math.sin(rad(a));
    return [o[0] + dx * c + dz * s, p[1], o[2] - dx * s + dz * c];
};
const rotX = (p, a, o) => {
    const dy = p[1] - o[1], dz = p[2] - o[2], c = Math.cos(rad(a)), s = Math.sin(rad(a));
    return [p[0], o[1] + dy * c - dz * s, o[2] + dy * s + dz * c];
};
const rotZ = (p, a, o) => {
    const dx = p[0] - o[0], dy = p[1] - o[1], c = Math.cos(rad(a)), s = Math.sin(rad(a));
    return [o[0] + dx * c - dy * s, o[1] + dx * s + dy * c, p[2]];
};

const YAW = 35, PITCH = 22;
const project = (p) => {
    // 先绕 Y 转，再绕 X 俯视
    let q = rotY(p, YAW, [8, 0, 8]);
    q = rotX(q, PITCH, [8, 8, 8]);
    return { x: q[0], y: -q[1] + 16, z: q[2] }; // y 翻成屏幕向下
};

const facesOf = (e) => {
    const [x0, y0, z0] = e.from, [x1, y1, z1] = e.to;
    const c = [
        [x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1],
        [x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]
    ];
    const r = e.rotation || {};
    let pts = c.map(p => {
        let q = p;
        if (r.x) q = rotX(q, r.x, r.origin);
        if (r.y) q = rotY(q, r.y, r.origin);
        if (r.z) q = rotZ(q, r.z, r.origin);
        return q;
    });
    const idx = [[0, 1, 2, 3], [4, 5, 6, 7], [0, 1, 5, 4], [3, 2, 6, 7], [0, 3, 7, 4], [1, 2, 6, 5]];
    const normals = [[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]];
    return idx.map((f, i) => ({ pts: f.map(k => pts[k]), n: normals[i] }));
};

// 世界坐标 -> 屏幕（等轴测后取包围盒铺满）
let all = [];
for (const e of model.elements) for (const f of facesOf(e)) all.push(...f.pts);
const proj = all.map(project);
const minX = Math.min(...proj.map(p => p.x)), maxX = Math.max(...proj.map(p => p.x));
const minY = Math.min(...proj.map(p => p.y)), maxY = Math.max(...proj.map(p => p.y));
const pad = 10;
const scale = Math.min((W - 2 * pad) / (maxX - minX), (H - 2 * pad) / (maxY - minY));
const toScreen = (p) => {
    const q = project(p);
    return {
        x: (q.x - (minX + maxX) / 2) * scale + W / 2,
        y: (q.y - (minY + maxY) / 2) * scale + H / 2,
        z: q.z
    };
};

// ---- 光与色 ----
const LIGHT = (() => { const v = [0.45, 0.8, 0.4], l = Math.hypot(...v); return v.map(x => x / l); })();
const BASE = [0x8a, 0x8a, 0x8c];
const shade = (n) => {
    // 法线跟着视角旋转
    let q = rotY(n, YAW, [0, 0, 0]);
    q = rotX(q, PITCH, [0, 0, 0]);
    const d = Math.max(0, q[0] * LIGHT[0] + q[1] * LIGHT[1] + q[2] * LIGHT[2]);
    return 0.55 + 0.55 * d;
};

// ---- 光栅 ----
const buf = new Float32Array(W * SS * H * SS * 4);
for (let i = 0; i < buf.length; i += 4) { buf[i] = BG[0]; buf[i + 1] = BG[1]; buf[i + 2] = BG[2]; buf[i + 3] = BG[3]; }

const fillPoly = (pts, rgb) => {
    const xs = pts.map(p => p.x), ys = pts.map(p => p.y);
    const y0 = Math.max(0, Math.floor(Math.min(...ys))), y1 = Math.min(H * SS - 1, Math.ceil(Math.max(...ys)));
    for (let y = y0; y <= y1; y++) {
        const sy = y + 0.5;
        const xsAt = [];
        for (let i = 0; i < pts.length; i++) {
            const a = pts[i], b = pts[(i + 1) % pts.length];
            if ((a.y <= sy && b.y > sy) || (b.y <= sy && a.y > sy)) {
                xsAt.push(a.x + (sy - a.y) / (b.y - a.y) * (b.x - a.x));
            }
        }
        xsAt.sort((a, b) => a - b);
        for (let k = 0; k + 1 < xsAt.length; k += 2) {
            const xa = Math.max(0, Math.ceil(xsAt[k] - 0.5)), xb = Math.min(W * SS - 1, Math.floor(xsAt[k + 1] - 0.5));
            for (let x = xa; x <= xb; x++) {
                const o = (y * W * SS + x) * 4;
                buf[o] = rgb[0]; buf[o + 1] = rgb[1]; buf[o + 2] = rgb[2]; buf[o + 3] = 255;
            }
        }
    }
};

// 收集所有面（含法线剔除 + 深度排序）
const list = [];
let ei = 0;
for (const e of model.elements) {
    const twist = 1 + (ei % 2 === 0 ? 0.07 : -0.07); // 逐段明暗差，让扭转看得出来
    ei++;
    for (const f of facesOf(e)) {
        const sp = f.pts.map(toScreen);
        const n = project([f.n[0] + 8, f.n[1] + 0, f.n[2] + 8]);
        const c = project([8, 0, 8]);
        // 屏幕空间法线方向判断：转化为 2D 朝向
        const s = shade(f.n);
        const rgb = BASE.map(v => Math.min(255, Math.round(v * s * twist)));
        const depth = sp.reduce((a, p) => a + p.z, 0) / 4;
        // 背面剔除：投影后面积为负即背对
        let area = 0;
        for (let i = 0; i < sp.length; i++) {
            const a = sp[i], b = sp[(i + 1) % sp.length];
            area += a.x * b.y - b.x * a.y;
        }
        if (area <= 0) continue;
        list.push({ sp: sp.map(p => ({ x: p.x * SS, y: p.y * SS })), rgb, depth });
    }
}
list.sort((a, b) => a.depth - b.depth);
for (const f of list) fillPoly(f.sp, f.rgb);

// ---- 下采样 + 写 PNG ----
const out = Buffer.alloc(W * H * 4);
for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
        let r = 0, g = 0, b = 0, a = 0;
        for (let sy = 0; sy < SS; sy++) {
            for (let sx = 0; sx < SS; sx++) {
                const o = ((y * SS + sy) * W * SS + (x * SS + sx)) * 4;
                const al = buf[o + 3] / 255;
                r += buf[o] * al; g += buf[o + 1] * al; b += buf[o + 2] * al; a += al;
            }
        }
        const n = SS * SS;
        const o = (y * W + x) * 4;
        if (a > 0) { out[o] = Math.round(r / a); out[o + 1] = Math.round(g / a); out[o + 2] = Math.round(b / a); out[o + 3] = Math.round(a / n * 255); }
        else { out[o] = out[o + 1] = out[o + 2] = 0; out[o + 3] = 0; }
    }
}

// PNG 编码
const crcTable = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc32 = (b) => { let c = 0xffffffff; for (const x of b) c = crcTable[(c ^ x) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const t = Buffer.from(type, 'ascii');
    const body = Buffer.concat([t, data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
    return Buffer.concat([len, body, crc]);
};
const raw = Buffer.alloc(H * (W * 4 + 1));
for (let y = 0; y < H; y++) {
    raw[y * (W * 4 + 1)] = 0;
    out.copy(raw, y * (W * 4 + 1) + 1, y * W * 4, (y + 1) * W * 4);
}
const ihdr = Buffer.alloc(13);
ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4);
ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
const png = Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0))
]);
fs.writeFileSync(OUT, png);
console.log('wrote', OUT, W + 'x' + H, png.length + 'B', 'faces=' + list.length);
