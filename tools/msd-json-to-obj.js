// Blockbench 模型 JSON → OBJ（按本仓库 eyecandy 的坐标 / UV 约定导出）。
//
// 用法：node tools/msd-json-to-obj.js <src.json> <out.obj>
//
// 约定（从 railing_stair_flat.obj 反推，Blockbench 4.12 的 OBJ 导出同一套）：
//   x = bx / 16 - 0.5      （绕方块中心）
//   y = by / 16
//   z = -(bz / 16 - 0.5)   （Z 翻转）
//   u = bu / 16,  v = 1 - bv / 16
const fs = require('fs');

const src = process.argv[2];
const out = process.argv[3];
const model = JSON.parse(fs.readFileSync(src, 'utf8'));

const lines = ['# converted from ' + src.replace(/^.*[\\/]/, ''), ''];
let vCount = 0;
let vtCount = 0;
let vnCount = 0;

// 方向 → 法线（Blockbench 的六个面）
const NORMALS = {
    north: [0, 0, -1],
    south: [0, 0, 1],
    east: [1, 0, 0],
    west: [-1, 0, 0],
    up: [0, 1, 0],
    down: [0, -1, 0]
};

function P(bx, by, bz) {
    return [bx / 16 - 0.5, by / 16, -(bz / 16 - 0.5)];
}

model.elements.forEach((el, index) => {
    // 旋转：本批素材的 angle 全是 0，出现非 0 直接报错，避免悄悄画歪
    const rot = el.rotation;
    if (rot && Math.abs(Number(rot.angle || 0)) > 1e-6) {
        throw new Error('element ' + index + ' has non-zero rotation (' + JSON.stringify(rot) + ')');
    }
    const [x1, y1, z1] = el.from;
    const [x2, y2, z2] = el.to;

    // 六个角
    const c = {
        nnn: P(x1, y1, z1), nnp: P(x1, y1, z2), npn: P(x1, y2, z1), npp: P(x1, y2, z2),
        pnn: P(x2, y1, z1), pnp: P(x2, y1, z2), ppn: P(x2, y2, z1), ppp: P(x2, y2, z2)
    };

    // 面 → 逆时针（朝外）的四个角
    const QUADS = {
        north: ['ppn', 'pnn', 'nnn', 'npn'], // z = z1 那一侧
        south: ['nnp', 'pnp', 'ppp', 'npp'], // z = z2
        west: ['nnn', 'pnn', 'pnp', 'nnp'],  // x = x1
        east: ['ppn', 'npn', 'npp', 'ppp'],  // x = x2
        up: ['npn', 'ppn', 'ppp', 'npp'],    // y = y2
        down: ['nnn', 'nnp', 'pnp', 'pnn']   // y = y1
    };

    // 每个面各自的顶点索引（同一坐标不跨面复用，UV 才不会串）
    const faces = [];
    Object.keys(el.faces).forEach(face => {
        const f = el.faces[face];
        if (!f || !f.uv) {
            return;
        }
        const [u1, v1, u2, v2] = f.uv;
        let verts = QUADS[face].map(corner => c[corner]);
        // 绕序自检：算出来的法线必须和「该朝外的那一面」同向，否则翻转，
        // 这样就不依赖 Blockbench 导出时顶点顺序的细节（背面剔除开着，绕反了整面会消失）。
        const n = NORMALS[face];
        const a = verts[0], b = verts[1], c2 = verts[2];
        const e1 = [b[0] - a[0], b[1] - a[1], b[2] - a[2]];
        const e2 = [c2[0] - b[0], c2[1] - b[1], c2[2] - b[2]];
        const cross = [
            e1[1] * e2[2] - e1[2] * e2[1],
            e1[2] * e2[0] - e1[0] * e2[2],
            e1[0] * e2[1] - e1[1] * e2[0]
        ];
        const dot = cross[0] * n[0] + cross[1] * n[1] + cross[2] * n[2];
        const uvs = [
            [u1 / 16, 1 - v1 / 16],
            [u2 / 16, 1 - v1 / 16],
            [u2 / 16, 1 - v2 / 16],
            [u1 / 16, 1 - v2 / 16]
        ];
        if (dot < 0) {
            verts = verts.slice().reverse();
            uvs.reverse();
        }
        faces.push({ face, verts, uvs });
    });
    if (faces.length === 0) {
        return;
    }

    lines.push('o element_' + index);
    const base = vCount;
    const vtBase = vtCount;
    faces.forEach(({ verts, uvs }) => {
        verts.forEach(v => {
            lines.push('v ' + v.map(n => (Math.round(n * 1e6) / 1e6)).join(' '));
            vCount++;
        });
        uvs.forEach(uv => {
            lines.push('vt ' + uv.map(n => (Math.round(n * 1e6) / 1e6)).join(' '));
            vtCount++;
        });
    });

    faces.forEach(({ face }, fi) => {
        const n = NORMALS[face];
        lines.push('vn ' + n.join(' '));
        const ni = vnCount + 1;
        vnCount++;
        // 面的四个顶点在本次 element 里的偏移
        const i0 = base + fi * 4 + 1;
        const t0 = vtBase + fi * 4 + 1;
        lines.push('f ' + [0, 1, 2, 3].map(k => (i0 + k) + '/' + (t0 + k) + '/' + ni).join(' '));
    });
});

fs.writeFileSync(out, lines.join('\n') + '\n');
console.log(out + ' : elements=' + model.elements.length + ' v=' + vCount + ' vt=' + vtCount + ' vn=' + vnCount);
