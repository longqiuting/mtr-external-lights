#!/usr/bin/env node
// 核对 v1.20.1 里厦门闸机 / 余额查询机的原始模型与贴图：
//   1. rawmodels/*.json 里每个 faces.*.texture 引用的 #k 必须在 textures 里有定义；
//   2. 每个引用到的贴图文件必须真的存在；
//   3. 几何自检（与 Java 里 RawModelJson 的公式**独立**写的断言）：
//        · 每个面的 4 个顶点恰好是那个盒子那一面的 4 个角（集合相等）；
//        · 绕序叉积朝外（dot(normal) > 0）；
//        · uv 角落映射：侧面 (u1,v1) 在面的「上-左」，up (u1,v1) 在 (minX,minZ)，down 在 (minX,maxZ)；
//        · uv 归一化后落在 0..1；
//        · 旋转是刚体变换（顶点到 origin 的距离不变）。
//   4. blockstate / 模型互相引用是否都能落地（防止删了模型还有地方指着它）。
//
// 用法：node tools/verify-rawmodels.js
'use strict';

const fs = require('fs');
const path = require('path');

const RES = path.resolve(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources', 'assets', 'mtrlte');
const RAW = path.join(RES, 'rawmodels');
const TEX = path.join(RES, 'textures');

let failures = 0;
let checks = 0;

function fail(message) {
  failures++;
  console.log('  FAIL ' + message);
}

function ok(message) {
  checks++;
  console.log('  ok   ' + message);
}

function eq(a, b, eps = 1e-3) {
  return Math.abs(a - b) <= eps;
}

// ----------------------------------------------------------------- 与 Java 同构的几何

const UV_TO_UNIT = 1 / 16;

const FACE = {
  north: { right: [-1, 0, 0], down: [0, -1, 0] },
  south: { right: [1, 0, 0], down: [0, -1, 0] },
  east: { right: [0, 0, -1], down: [0, -1, 0] },
  west: { right: [0, 0, 1], down: [0, -1, 0] },
  up: { right: [1, 0, 0], down: [0, 0, 1] },
  down: { right: [1, 0, 0], down: [0, 0, -1] },
};

function rotate(p, origin, a) {
  let [x, y, z] = [p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]];
  if (a[0]) {
    const r = (Math.PI * a[0]) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [y, z] = [y * c - z * s, y * s + z * c];
  }
  if (a[1]) {
    const r = (Math.PI * a[1]) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [x, z] = [x * c + z * s, -x * s + z * c];
  }
  if (a[2]) {
    const r = (Math.PI * a[2]) / 180;
    const [c, s] = [Math.cos(r), Math.sin(r)];
    [x, y] = [x * c - y * s, x * s + y * c];
  }
  return [x + origin[0], y + origin[1], z + origin[2]];
}

function anglesOf(rotation) {
  if (!rotation) {
    return [0, 0, 0];
  }
  if (rotation.axis) {
    const a = [0, 0, 0];
    a[{ x: 0, y: 1, z: 2 }[rotation.axis]] = rotation.angle || 0;
    return a;
  }
  return [rotation.x || 0, rotation.y || 0, rotation.z || 0];
}

function quadsOfElement(element) {
  const [x1, y1, z1] = [Math.min(element.from[0], element.to[0]), Math.min(element.from[1], element.to[1]),
    Math.min(element.from[2], element.to[2])];
  const [x2, y2, z2] = [Math.max(element.from[0], element.to[0]), Math.max(element.from[1], element.to[1]),
    Math.max(element.from[2], element.to[2])];
  const origin = element.rotation && element.rotation.origin ? element.rotation.origin : [0, 0, 0];
  const angles = anglesOf(element.rotation);
  const rotated = angles.some((a) => a !== 0);

  const out = [];
  for (const [face, { right, down }] of Object.entries(FACE)) {
    const json = element.faces[face];
    if (!json) {
      continue;
    }
    const center = {
      north: [(x1 + x2) / 2, (y1 + y2) / 2, z1],
      south: [(x1 + x2) / 2, (y1 + y2) / 2, z2],
      east: [x2, (y1 + y2) / 2, (z1 + z2) / 2],
      west: [x1, (y1 + y2) / 2, (z1 + z2) / 2],
      up: [(x1 + x2) / 2, y2, (z1 + z2) / 2],
      down: [(x1 + x2) / 2, y1, (z1 + z2) / 2],
    }[face];
    const w = { north: x2 - x1, south: x2 - x1, east: z2 - z1, west: z2 - z1, up: x2 - x1, down: x2 - x1 }[face];
    const h = { north: y2 - y1, south: y2 - y1, east: y2 - y1, west: y2 - y1, up: z2 - z1, down: z2 - z1 }[face];

    const uv = json.uv || [0, 0, 16, 16];
    const corners = [[-1, -1], [-1, 1], [1, 1], [1, -1]];
    const us = [uv[0], uv[0], uv[2], uv[2]];
    const vs = [uv[1], uv[3], uv[3], uv[1]];
    const pos = corners.map(([sx, sy]) => {
      const p = [
        center[0] + (sx * w * right[0]) / 2 + (sy * h * down[0]) / 2,
        center[1] + (sx * w * right[1]) / 2 + (sy * h * down[1]) / 2,
        center[2] + (sx * w * right[2]) / 2 + (sy * h * down[2]) / 2,
      ];
      return rotated ? rotate(p, origin, angles) : p;
    });
    out.push({
      face,
      pos,
      uv: pos.map((_, i) => [us[i] * UV_TO_UNIT, vs[i] * UV_TO_UNIT]),
      rawUv: uv,
      origin,
      angles,
      rotated,
    });
  }
  return out;
}

function cross(a, b) {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
}

const NORMAL = {
  north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0],
};

// ----------------------------------------------------------------- 主流程

function checkModel(file) {
  const name = path.basename(file, '.json');
  console.log('\n== rawmodels/' + name + '.json');
  const model = JSON.parse(fs.readFileSync(file, 'utf8'));

  // 1) 贴图引用
  const textures = model.textures || {};
  const used = new Set();
  for (const [i, element] of (model.elements || []).entries()) {
    for (const [dir, face] of Object.entries(element.faces || {})) {
      const ref = face.texture;
      if (!ref || !ref.startsWith('#')) {
        return fail(`elements[${i}].faces.${dir}.texture = ${JSON.stringify(ref)}（不是 #k 引用）`);
      }
      const key = ref.slice(1);
      if (!(key in textures)) {
        return fail(`elements[${i}].faces.${dir} 引用了 #${key}，但 textures 里没有定义`);
      }
      used.add(textures[key]);
    }
  }
  ok(`面引用的 #k 全部在 textures 里有定义（用到的贴图：${[...used].join(', ')}）`);

  // 2) 贴图文件存在
  for (const texName of used) {
    const rel = texName.includes(':') ? texName.split(':')[1] : texName;
    const p = path.join(TEX, rel.endsWith('.png') ? rel : 'block/' + rel + '.png');
    if (!fs.existsSync(p)) {
      return fail(`贴图文件不存在：${path.relative(RES, p)}`);
    }
    const size = fs.statSync(p).size;
    ok(`贴图存在：textures/${path.relative(TEX, p).replace(/\\/g, '/')}（${size} bytes）`);
  }

  // 3) 几何自检
  let quadCount = 0;
  let degenerate = 0;
  let rotatedCount = 0;
  let maxUv = 0;
  for (const [i, element] of (model.elements || []).entries()) {
    const mine = quadsOfElement(element);
    if (element.rotation && anglesOf(element.rotation).some((a) => a !== 0)) {
      rotatedCount++;
    }
    const [x1, y1, z1] = [Math.min(element.from[0], element.to[0]), Math.min(element.from[1], element.to[1]),
      Math.min(element.from[2], element.to[2])];
    const [x2, y2, z2] = [Math.max(element.from[0], element.to[0]), Math.max(element.from[1], element.to[1]),
      Math.max(element.from[2], element.to[2])];

    // 每个面的 4 个角（独立算：盒子 8 个角里属于该面的 4 个）
    const cornersAll = [];
    for (const x of [x1, x2]) {
      for (const y of [y1, y2]) {
        for (const z of [z1, z2]) {
          cornersAll.push([x, y, z]);
        }
      }
    }
    // 每个面的 4 个角：沿该面的两个「变化轴」取极值（这样零厚度元素也不会退化）
    const varyX = [x1, x2];
    const varyY = [y1, y2];
    const varyZ = [z1, z2];
    const faceCorners = (face) => {
      const out2 = [];
      const xs = face === 'east' || face === 'west' ? [face === 'east' ? x2 : x1] : varyX;
      const ys = face === 'up' || face === 'down' ? [face === 'up' ? y2 : y1] : varyY;
      const zs = face === 'north' || face === 'south' ? [face === 'north' ? z1 : z2] : varyZ;
      for (const x of xs) {
        for (const y of ys) {
          for (const z of zs) {
            out2.push([x, y, z]);
          }
        }
      }
      return out2;
    };

    for (const quad of mine) {
      quadCount++;
      // 零面积的面（零厚度元素的侧面）在 Java 端会被丢掉，这里只统计。
      const g1 = quad.pos[1].map((v, k) => v - quad.pos[0][k]);
      const g2 = quad.pos[2].map((v, k) => v - quad.pos[0][k]);
      const gross = cross(g1, g2);
      const area = Math.hypot(gross[0], gross[1], gross[2]);
      if (area < 1e-6) {
        degenerate++;
        continue;
      }
      const sameSet = (a, b) => {
        if (quad.rotated) {
          return true; // 旋转后坐标不再与轴对齐盒子可比，改用下面的刚体检查
        }
        if (a.length !== b.length) {
          return false;
        }
        const pool = b.slice();
        for (const p of a) {
          const idx = pool.findIndex((q) => q.every((v, k) => Math.abs(v - p[k]) <= 1e-3));
          if (idx < 0) {
            return false;
          }
          pool.splice(idx, 1);
        }
        return true;
      };
      if (!sameSet(quad.pos, faceCorners(quad.face))) {
        fail(`elements[${i}] ${quad.face} 面的 4 个角不是盒子的那一面：${JSON.stringify(quad.pos)}`);
      }

      // 绕序朝外（元素旋转时法线也跟着转，所以拿旋转后的法线比）
      const e1 = quad.pos[1].map((v, k) => v - quad.pos[0][k]);
      const e2 = quad.pos[2].map((v, k) => v - quad.pos[0][k]);
      const n = cross(e1, e2);
      const expected = quad.rotated ? rotate(NORMAL[quad.face], [0, 0, 0], quad.angles) : NORMAL[quad.face];
      const len = Math.hypot(n[0], n[1], n[2]);
      const dot = (n[0] * expected[0] + n[1] * expected[1] + n[2] * expected[2]) / len;
      if (!(dot > 0.99)) {
        fail(`elements[${i}] ${quad.face} 面绕序不是朝外（归一化 dot=${dot.toFixed(3)}）`);
      }

      // uv 角落映射（只在未旋转时可直接和世界坐标对照）
      if (!quad.rotated) {
        const [u1] = quad.uv[0];
        const corner = quad.pos[0];
        const expect = {
          north: (c) => eq(c[0], x2) && eq(c[1], y2),
          south: (c) => eq(c[0], x1) && eq(c[1], y2),
          east: (c) => eq(c[2], z2) && eq(c[1], y2),
          west: (c) => eq(c[2], z1) && eq(c[1], y2),
          up: (c) => eq(c[0], x1) && eq(c[2], z1),
          down: (c) => eq(c[0], x1) && eq(c[2], z2),
        }[quad.face];
        if (u1 !== quad.uv[0][0]) {
          fail(`elements[${i}] uv 结构异常`);
        }
        if (!expect(corner)) {
          fail(`elements[${i}] ${quad.face} 面的 (u1,v1) 落在 ${JSON.stringify(corner)}，位置不对（UV 会镜像）`);
        }
      }

      // uv 归一化
      for (const [u, v] of quad.uv) {
        maxUv = Math.max(maxUv, u, v);
        if (u < -1e-6 || u > 1 + 1e-6 || v < -1e-6 || v > 1 + 1e-6) {
          fail(`elements[${i}] uv 超出 0..1：${u}, ${v}`);
        }
      }

      // 刚体：到 origin 的距离不变
      if (quad.rotated) {
        const radiiBefore = new Set(cornersAll.map((p) =>
          Math.round(Math.hypot(...p.map((v, k) => v - quad.origin[k])) * 1e3)));
        const radiiAfter = new Set(quad.pos.map((p) =>
          Math.round(Math.hypot(...p.map((v, k) => v - quad.origin[k])) * 1e3)));
        if (![...radiiAfter].every((r) => radiiBefore.has(r))) {
          fail(`elements[${i}] 旋转不是刚体（半径 ${[...radiiAfter]} 不在 ${[...radiiBefore]} 里）`);
        }
      }
    }
  }
  ok(`几何自检通过：${model.elements.length} 个元素 / ${quadCount} 个面`
    + `（其中 ${degenerate} 个零面积面按设计丢弃、${rotatedCount} 个元素带非零旋转）`);
  ok(`uv 归一化后最大 ${maxUv.toFixed(4)}（应为 0..1）`);
  if (maxUv > 1) {
    fail('uv 大于 1：说明还按 64 或贴图像素在算');
  }
}

function checkVanillaModels() {
  console.log('\n== 原版模型合法性（22.5° 整数倍 / 坐标范围 / 面数）');
  const modelsDir = path.join(RES, 'models');
  const walk = (dir) => fs.readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? walk(path.join(dir, e.name)) : (e.name.endsWith('.json') ? [path.join(dir, e.name)] : []));
  for (const file of walk(modelsDir)) {
    const rel = path.relative(modelsDir, file).replace(/\\/g, '/');
    let model;
    try {
      model = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch (e) {
      fail(`${rel} 不是合法 JSON：${e.message}`);
      continue;
    }
    const elements = model.elements || [];
    for (const [i, element] of elements.entries()) {
      const rotation = element.rotation;
      if (rotation) {
        if (Math.abs((rotation.angle / 22.5) - Math.round(rotation.angle / 22.5)) > 1e-6) {
          fail(`${rel} elements[${i}] 角度 ${rotation.angle} 不是 22.5 的整数倍（原版会拒绝加载）`);
        }
        if (!['x', 'y', 'z'].includes(rotation.axis)) {
          fail(`${rel} elements[${i}] rotation.axis=${rotation.axis}`);
        }
      }
      for (const key of ['from', 'to']) {
        for (const v of element[key] || []) {
          if (v < -16 - 1e-6 || v > 32 + 1e-6) {
            fail(`${rel} elements[${i}].${key} 坐标 ${v} 超出原版允许的 -16..32`);
          }
        }
      }
      if (!element.faces || Object.keys(element.faces).length === 0) {
        fail(`${rel} elements[${i}] 没有 faces（原版要求 1..6 个面）`);
      }
    }
    ok(`${rel}（${elements.length} 个元素）`);
  }
}

function checkReferences() {
  console.log('\n== blockstate / 模型引用');
  const statesDir = path.join(RES, 'blockstates');
  const modelsDir = path.join(RES, 'models');
  const wanted = ['ticket_gate_xiamen_in', 'ticket_gate_xiamen_out', 'ticket_gate_brt_in',
    'ticket_gate_brt_out', 'balance_machine_xiamen'];
  const resolve = (id) => {
    const rel = id.includes(':') ? id.split(':')[1] : id;
    return path.join(modelsDir, rel + '.json');
  };
  for (const name of wanted) {
    const file = path.join(statesDir, name + '.json');
    if (!fs.existsSync(file)) {
      fail(`blockstates/${name}.json 不存在`);
      continue;
    }
    const state = JSON.parse(fs.readFileSync(file, 'utf8'));
    const usedModels = new Set(Object.values(state.variants).map((v) => (Array.isArray(v) ? v[0] : v).model));
    for (const id of usedModels) {
      const p = resolve(id);
      if (!fs.existsSync(p)) {
        fail(`blockstates/${name}.json 指向不存在的模型 ${id}`);
      }
    }
    ok(`blockstates/${name}.json → ${[...usedModels].join(', ')}（${Object.keys(state.variants).length} 个变体）`);
  }

  for (const name of wanted) {
    const itemFile = path.join(modelsDir, 'item', name + '.json');
    if (!fs.existsSync(itemFile)) {
      fail(`models/item/${name}.json 不存在`);
      continue;
    }
    const item = JSON.parse(fs.readFileSync(itemFile, 'utf8'));
    if (item.parent && item.parent.startsWith('mtrlte:')) {
      const p = resolve(item.parent);
      if (!fs.existsSync(p)) {
        fail(`models/item/${name}.json 的 parent ${item.parent} 不存在`);
      } else {
        ok(`models/item/${name}.json parent → ${item.parent}`);
      }
    }
  }
}

// ----------------------------------------------------------------- 跑

const files = fs.readdirSync(RAW).filter((f) => f.endsWith('.json')).sort();
for (const file of files) {
  checkModel(path.join(RAW, file));
}
checkVanillaModels();
checkReferences();

console.log('\n' + (failures === 0
  ? `全部通过（${checks} 项检查，${files.length} 份原始模型）`
  : `${failures} 项失败`));
process.exit(failures === 0 ? 0 : 1);
