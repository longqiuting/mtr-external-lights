#!/usr/bin/env node
/*
 * Generate the train-lamp flare texture we draw our own head/tail lights with.
 *
 * Why generated here and not shipped as a PNG someone downloaded: the shape has to be *ours*
 * (RTE's vehicle_light_flare.png is All-Rights-Reserved), and a procedural definition is the only
 * version of "a radial falloff" that can be reviewed, tuned and re-derived in a diff. The PNG in
 * `v1.20.1/src/main/resources/assets/mtrlte/textures/vehicle/light_flare.png` is the output of this
 * file; regenerate with:
 *
 *     node tools/gen-light-flare.js
 *
 * Profile (both terms in radius r, 0 at the centre and 1 at the edge of the square):
 *
 *     core = exp(-(r / 0.17)²)                      the lamp itself, ~1 in the middle 8% of the radius
 *     halo = 0.38 * exp(-(r / 0.52)²)               the bloom around it, wide and weak
 *     edge = max(0, 1 - r⁴)                          forces exactly 0 at the border, no hard cut
 *     alpha = min(1, core + halo) * edge
 *
 * RGB is pure white on purpose: the per-lamp colour is a vertex colour (the beam's own colour from
 * the pack / editor), so the texture only carries the *shape*. Additive blending is
 * (SRC_ALPHA, ONE), i.e. this alpha weights the colour - no premultiplication needed.
 *
 * Size 128x128: a flare covers a handful of pixels at 100+ blocks, and up close 128 already looks
 * smooth with LINEAR filtering; 256 doubled the file for nothing.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const SIZE = 128;
const CORE_SIGMA = 0.17;
const HALO_SIGMA = 0.52;
const HALO_WEIGHT = 0.38;

/** The alpha profile, 0..1, for a normalised radius (0 centre .. 1 edge). Exported for checks. */
function alphaAt(r) {
    const core = Math.exp(-((r / CORE_SIGMA) ** 2));
    const halo = HALO_WEIGHT * Math.exp(-((r / HALO_SIGMA) ** 2));
    const edge = Math.max(0, 1 - r ** 4);
    return Math.min(1, core + halo) * edge;
}

function buildPixels() {
    const px = Buffer.alloc(SIZE * SIZE * 4);
    const c = (SIZE - 1) / 2;
    for (let y = 0; y < SIZE; y++) {
        for (let x = 0; x < SIZE; x++) {
            const dx = (x - c) / c;
            const dy = (y - c) / c;
            const r = Math.min(1, Math.sqrt(dx * dx + dy * dy));
            const a = Math.round(255 * alphaAt(r));
            const o = (y * SIZE + x) * 4;
            px[o] = 255;
            px[o + 1] = 255;
            px[o + 2] = 255;
            px[o + 3] = a;
        }
    }
    return px;
}

// --- minimal PNG encoder (RGBA8, no interlace) ------------------------------------------------

const CRC_TABLE = (() => {
    const table = new Int32Array(256);
    for (let n = 0; n < 256; n++) {
        let c = n;
        for (let k = 0; k < 8; k++) {
            c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        }
        table[n] = c;
    }
    return table;
})();

function crc32(buf) {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) {
        c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    }
    return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length, 0);
    const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(body), 0);
    return Buffer.concat([len, body, crc]);
}

function encodePng(pixels) {
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(SIZE, 0);
    ihdr.writeUInt32BE(SIZE, 4);
    ihdr[8] = 8;    // bit depth
    ihdr[9] = 6;    // colour type: truecolour + alpha
    ihdr[10] = 0;   // deflate
    ihdr[11] = 0;   // adaptive filtering
    ihdr[12] = 0;   // no interlace

    // One filter byte (0 = None) per scanline, then the RGBA row.
    const raw = Buffer.alloc(SIZE * (1 + SIZE * 4));
    for (let y = 0; y < SIZE; y++) {
        const o = y * (1 + SIZE * 4);
        raw[o] = 0;
        pixels.copy(raw, o + 1, y * SIZE * 4, (y + 1) * SIZE * 4);
    }
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr),
        chunk('IDAT', zlib.deflateSync(raw, { level: 9 })),
        chunk('IEND', Buffer.alloc(0)),
    ]);
}

function main() {
    const pixels = buildPixels();
    const png = encodePng(pixels);
    const out = path.join(__dirname, '..', 'v1.20.1', 'src', 'main', 'resources',
        'assets', 'mtrlte', 'textures', 'vehicle', 'light_flare.png');
    fs.mkdirSync(path.dirname(out), { recursive: true });
    fs.writeFileSync(out, png);

    // Self-check: the decoded alpha must be the profile we wrote, and the border must be 0.
    const check = (r) => Math.round(255 * alphaAt(r));
    const at = (x, y) => pixels[(y * SIZE + x) * 4 + 3];
    const c = (SIZE - 1) / 2;
    const samples = [0, 0.1, 0.25, 0.5, 0.75, 1.0].map((r) => {
        const x = Math.round(c + r * c);
        return `r=${r.toFixed(2)} a=${at(x, Math.round(c))} (expected ${check(r)})`;
    });
    console.log(`wrote ${out} (${png.length} bytes, ${SIZE}x${SIZE} RGBA)`);
    console.log(`centre alpha=${at(Math.round(c), Math.round(c))}, corner alpha=${at(0, 0)}`);
    for (const line of samples) {
        console.log('  ' + line);
    }
    const ok = at(Math.round(c), Math.round(c)) >= 250 && at(0, 0) === 0
        && at(0, Math.round(c)) === check(1);
    console.log(ok ? 'profile OK' : 'profile MISMATCH');
    if (!ok) {
        process.exitCode = 1;
    }
}

if (require.main === module) {
    main();
}

module.exports = { alphaAt, SIZE };
