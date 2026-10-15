import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { inflateSync } from 'node:zlib';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const read = file => readFileSync(new URL(file, root));
const text = file => read(file).toString('utf8');
const exists = file => existsSync(fileURLToPath(new URL(file, root)));
const spec = JSON.parse(text('docs/branding/icon-spec.json'));
const res = 'android/app/src/main/res/';
const drawable = name => text(`${res}drawable/ic_launcher_${name}.xml`);
const pathData = spec.contour.map(([cmd, ...values]) => cmd + values.join(',')).join(' ');

// Dependency-free PNG decoder for the generator's non-interlaced RGBA8 files.
// Reading pixels catches stale/default imagery, not just plausible filenames.
function png(file) {
    const bytes = read(file);
    assert.deepEqual(bytes.subarray(0, 8), Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    let width, height;
    const data = [];
    for (let offset = 8; offset < bytes.length;) {
        const length = bytes.readUInt32BE(offset);
        const type = bytes.toString('ascii', offset + 4, offset + 8);
        const chunk = bytes.subarray(offset + 8, offset + 8 + length);
        if (type === 'IHDR') {
            width = chunk.readUInt32BE(0);
            height = chunk.readUInt32BE(4);
            assert.deepEqual([...chunk.subarray(8)], [8, 6, 0, 0, 0], 'Expected RGBA8, no interlace');
        } else if (type === 'IDAT') data.push(chunk);
        offset += length + 12;
    }
    const encoded = inflateSync(Buffer.concat(data));
    const stride = width * 4;
    assert.equal(encoded.length, (stride + 1) * height);
    const decoded = Buffer.alloc(width * height * 4);
    const paeth = (a, b, c) => {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    };
    for (let y = 0; y < height; y++) {
        const filter = encoded[y * (stride + 1)];
        assert.ok(filter <= 4, 'Standard PNG filter');
        for (let x = 0; x < stride; x++) {
            const i = y * stride + x;
            const left = x >= 4 ? decoded[i - 4] : 0;
            const above = y ? decoded[i - stride] : 0;
            const corner = y && x >= 4 ? decoded[i - stride - 4] : 0;
            const prediction = [0, left, above, Math.floor((left + above) / 2), paeth(left, above, corner)][filter];
            decoded[i] = (encoded[y * (stride + 1) + 1 + x] + prediction) & 255;
        }
    }
    return { width, height, pixel: (x, y) => [...decoded.subarray((y * width + x) * 4, (y * width + x) * 4 + 4)] };
}

const rgba = hex => [...Buffer.from(hex.slice(1), 'hex'), 255];

test('reviewed warm palette and full symbol stay inside the adaptive circular safe zone', () => {
    assert.equal(spec.version, 1);
    assert.equal(spec.canvas, 108);
    assert.deepEqual(spec.viewport, [18, 18, 90, 90]);
    assert.deepEqual(spec.safeZone, [21, 21, 87, 87]);
    assert.deepEqual(spec.palette, { background: '#FFF3E6', contour: '#B9563F', touch: '#713E32' });
    assert.ok(spec.strokeWidth >= 4, 'Maintain legibility in small icons');
    let current;
    const points = [];
    for (const [command, ...values] of spec.contour) {
        if (command === 'M') current = values;
        else {
            assert.equal(command, 'C');
            assert.equal(values.length, 6);
            for (let i = 0; i <= 128; i++) {
                const t = i / 128, u = 1 - t;
                points.push([0, 1].map(k => u**3*current[k] + 3*u*u*t*values[k]
                    + 3*u*t*t*values[k+2] + t**3*values[k+4]));
            }
            current = values.slice(4);
        }
    }
    for (const [x, y] of points) assert.ok(Math.hypot(x - 54, y - 54) + spec.strokeWidth / 2 <= 33);
    assert.ok(Math.hypot(spec.touch.cx - 54, spec.touch.cy - 54) + spec.touch.radius <= 33);
    const xs = points.map(p => p[0]);
    const left = Math.min(Math.min(...xs) - spec.strokeWidth / 2, spec.touch.cx - spec.touch.radius);
    const right = Math.max(Math.max(...xs) + spec.strokeWidth / 2, spec.touch.cx + spec.touch.radius);
    assert.ok(right - left >= 48, 'Complete mark including its touch point must not be undersized');
});

test('Android foreground and editable SVG share the spec geometry and approved colors', () => {
    const foreground = drawable('foreground');
    assert.ok(foreground.includes(`android:pathData="${pathData}"`));
    assert.ok(foreground.includes(`android:strokeWidth="${spec.strokeWidth}"`));
    assert.ok(foreground.includes('android:strokeLineCap="round"'));
    for (const color of [spec.palette.contour, spec.palette.touch]) assert.ok(foreground.includes(color));
    assert.ok(drawable('background').includes(`android:fillColor="${spec.palette.background}"`));
    const svg = text('docs/branding/touchscene-icon.svg');
    assert.ok(svg.includes(`d="${pathData}"`));
    assert.ok(svg.includes('viewBox="18 18 72 72"'));
    assert.ok(svg.includes(`<circle cx="${spec.touch.cx}" cy="${spec.touch.cy}"`));
    for (const color of Object.values(spec.palette)) assert.ok(svg.includes(color));
    assert.doesNotMatch(svg, /<(?:text|image|filter|linearGradient|radialGradient)\b/);
    assert.doesNotMatch(foreground + drawable('background'), /3DDC84|31,63\.928|<gradient/i);
});

test('both unchanged Manifest icon names resolve to complete adaptive layers', () => {
    const manifest = text('android/app/src/main/AndroidManifest.xml');
    assert.ok(manifest.includes('android:icon="@mipmap/ic_launcher"'));
    assert.ok(manifest.includes('android:roundIcon="@mipmap/ic_launcher_round"'));
    for (const name of ['ic_launcher', 'ic_launcher_round']) {
        const adaptive = text(`${res}mipmap-anydpi-v26/${name}.xml`);
        for (const layer of ['foreground', 'background', 'monochrome']) {
            assert.ok(adaptive.includes(`<${layer} android:drawable="@drawable/ic_launcher_${layer}"`));
            assert.ok(exists(`${res}drawable/ic_launcher_${layer}.xml`));
        }
    }
});

test('monochrome artwork preserves both shapes and has no opaque full-tile background', () => {
    const mono = drawable('monochrome');
    assert.ok(mono.includes(`android:pathData="${pathData}"`));
    assert.equal((mono.match(/<path\b/g) || []).length, 2);
    assert.ok(mono.includes('android:strokeColor="#FFFFFF"'));
    assert.ok(mono.includes('android:fillColor="#FFFFFF"'));
    const coloredPaths = xml => [...xml.matchAll(/android:pathData="([^"]+)"/g)].map(match => match[1]);
    assert.deepEqual(coloredPaths(mono), coloredPaths(drawable('foreground')));
    assert.doesNotMatch(mono, /#FFF3E6|M0,0h108/);
});

test('five legacy densities have exact PNG sizes, expected pixels and no duplicate WebP', () => {
    for (const [density, size] of Object.entries({ mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 })) {
        for (const name of ['ic_launcher', 'ic_launcher_round']) {
            const file = `${res}mipmap-${density}/${name}`;
            assert.ok(!exists(`${file}.webp`), 'Old image must not shadow the new icon');
            const image = png(`${file}.png`);
            assert.equal(image.width, size);
            assert.equal(image.height, size);
            assert.equal(image.pixel(0, 0)[3], 0, 'Legacy shapes have transparent corners');
            assert.deepEqual(image.pixel(size / 2, size / 2), rgba(spec.palette.background));
            const x = Math.floor((spec.touch.cx - 18) / 72 * size);
            const y = Math.floor((spec.touch.cy - 18) / 72 * size);
            assert.deepEqual(image.pixel(x, y), rgba(spec.palette.touch));
            const contour = image.pixel(Math.floor((53 - 18) / 72 * size), Math.floor((29.5 - 18) / 72 * size));
            // LANCZOS downsampling can ring by up to three levels at 48/72px.
            assert.equal(contour[3], 255);
            for (let channel = 0; channel < 3; channel++) {
                assert.ok(Math.abs(contour[channel] - rgba(spec.palette.contour)[channel]) <= 3,
                    `${density}/${name}: resampled contour must retain the approved color`);
            }
        }
    }
});

test('repository includes a full-bleed 1024px master and clearly separate preview sheet', () => {
    const master = png('docs/branding/touchscene-icon-1024.png');
    assert.equal(master.width, 1024);
    assert.equal(master.height, 1024);
    assert.deepEqual(master.pixel(0, 0), rgba(spec.palette.background));
    const preview = png('docs/branding/preview.png');
    assert.equal(preview.width, 1120);
    assert.equal(preview.height, 640);
});
