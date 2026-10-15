import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import test from 'node:test';

// No machine-specific SDK/tool path, shell unzip, Android device or network is needed.
const root = fileURLToPath(new URL('../', import.meta.url));
const sdk = path.join(root, 'android/opencv-sdk');
const provenance = JSON.parse(readFileSync(path.join(sdk, 'native-runtime-provenance.json'), 'utf8'));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const sourceBytes = item => readFileSync(path.join(sdk, item.path));

function elf(bytes) {
    assert.equal(bytes.subarray(0, 4).toString('hex'), '7f454c46', 'Not an ELF binary');
    assert.equal(bytes[4], 2, 'ELF must be 64-bit');
    assert.equal(bytes[5], 1, 'ELF must be little endian');
    assert.equal(bytes.readUInt16LE(16), 3, 'ELF must be a shared object');
    assert.equal(bytes.readUInt16LE(18), 183, 'ELF must target AArch64 / arm64-v8a');
    const integer64 = offset => {
        const value = bytes.readBigUInt64LE(offset);
        assert.ok(value <= BigInt(Number.MAX_SAFE_INTEGER), 'ELF offset/value exceeds safe range');
        return Number(value);
    };
    const programOffset = integer64(32);
    const programSize = bytes.readUInt16LE(54);
    const programs = Array.from({ length: bytes.readUInt16LE(56) }, (_, index) => {
        const start = programOffset + index * programSize;
        const segment = {
            type: bytes.readUInt32LE(start), flags: bytes.readUInt32LE(start + 4),
            offset: integer64(start + 8), vaddr: integer64(start + 16),
            filesz: integer64(start + 32), memsz: integer64(start + 40), align: integer64(start + 48),
        };
        assert.ok(segment.offset + segment.filesz <= bytes.length, 'Truncated ELF segment');
        return segment;
    });
    const loads = programs.filter(program => program.type === 1);
    assert.ok(loads.length >= 2, 'Expected executable/data PT_LOAD segments');
    const dynamic = programs.find(program => program.type === 2);
    assert.ok(dynamic, 'Missing PT_DYNAMIC');
    const tags = [];
    for (let offset = dynamic.offset; offset < dynamic.offset + dynamic.filesz; offset += 16) {
        const tag = integer64(offset);
        if (tag === 0) break;
        tags.push([tag, integer64(offset + 8)]);
    }
    const stringAddress = tags.find(([tag]) => tag === 5)?.[1];
    const stringSegment = loads.find(segment => stringAddress >= segment.vaddr &&
        stringAddress < segment.vaddr + segment.filesz);
    assert.ok(stringSegment, 'Missing dynamic string table');
    const stringsOffset = stringSegment.offset + stringAddress - stringSegment.vaddr;
    const cString = offset => {
        const end = bytes.indexOf(0, offset);
        assert.ok(end >= offset, 'Unterminated ELF string');
        return bytes.toString('utf8', offset, end);
    };
    const needed = tags.filter(([tag]) => tag === 1).map(([, value]) => cString(stringsOffset + value));
    const soname = cString(stringsOffset + tags.find(([tag]) => tag === 14)[1]);
    const notes = [];
    const align4 = value => (value + 3) & ~3;
    for (const segment of programs.filter(program => program.type === 4)) {
        for (let offset = segment.offset; offset + 12 <= segment.offset + segment.filesz;) {
            const namesz = bytes.readUInt32LE(offset);
            const descsz = bytes.readUInt32LE(offset + 4);
            const type = bytes.readUInt32LE(offset + 8);
            const nameOffset = offset + 12;
            const descOffset = nameOffset + align4(namesz);
            assert.ok(descOffset + descsz <= segment.offset + segment.filesz, 'Truncated ELF note');
            notes.push({ name: bytes.toString('utf8', nameOffset, nameOffset + namesz).replace(/\0+$/, ''),
                type, desc: bytes.subarray(descOffset, descOffset + descsz) });
            offset = descOffset + align4(descsz);
        }
    }
    const buildId = notes.find(note => note.name === 'GNU' && note.type === 3)?.desc.toString('hex');
    const android = notes.find(note => note.name === 'Android' && note.desc.length >= 132)?.desc;
    assert.ok(android, 'Missing NDK provenance note');
    const ndkRelease = android.toString('utf8', 4, 68).replace(/\0+$/, '');
    const ndkBuild = android.toString('utf8', 68, 132).replace(/\0+$/, '');

    // AGP may strip debug/symbol sections. Normalize only their ELF section-table
    // header references, then hash every loaded code/data byte and segment layout.
    const loadImage = createHash('sha256');
    for (const segment of loads) {
        const loaded = Buffer.from(bytes.subarray(segment.offset, segment.offset + segment.filesz));
        if (segment.offset === 0) { loaded.fill(0, 40, 48); loaded.fill(0, 58, 64); }
        loadImage.update(JSON.stringify(segment)).update('\0').update(loaded);
    }
    return { needed, soname, buildId, ndkRelease, ndkBuild, loadImageSha256: loadImage.digest('hex') };
}

function zipEntries(bytes) {
    let end = -1;
    for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65_557); offset--) {
        if (bytes.readUInt32LE(offset) === 0x06054b50 &&
            offset + 22 + bytes.readUInt16LE(offset + 20) === bytes.length) { end = offset; break; }
    }
    assert.ok(end >= 0, 'Missing ZIP end-of-directory');
    const entries = new Map();
    const count = bytes.readUInt16LE(end + 10);
    let central = bytes.readUInt32LE(end + 16);
    assert.notEqual(count, 0xffff, 'ZIP64 APK is not supported by this small guard');
    for (let index = 0; index < count; index++) {
        assert.equal(bytes.readUInt32LE(central), 0x02014b50, 'Invalid ZIP central directory');
        const nameLength = bytes.readUInt16LE(central + 28);
        const name = bytes.toString('utf8', central + 46, central + 46 + nameLength);
        assert.ok(!entries.has(name), `Duplicate ZIP entry: ${name}`);
        entries.set(name, {
            method: bytes.readUInt16LE(central + 10), compressed: bytes.readUInt32LE(central + 20),
            size: bytes.readUInt32LE(central + 24), offset: bytes.readUInt32LE(central + 42),
        });
        central += 46 + nameLength + bytes.readUInt16LE(central + 30) + bytes.readUInt16LE(central + 32);
    }
    return {
        names: [...entries.keys()],
        read(name) {
            const entry = entries.get(name);
            assert.ok(entry, `APK is missing ${name}`);
            assert.ok(entry.size > 0 && entry.size < 128 * 1024 * 1024, 'Native ZIP entry has invalid size');
            assert.equal(bytes.readUInt32LE(entry.offset), 0x04034b50, 'Invalid ZIP local header');
            const offset = entry.offset + 30 + bytes.readUInt16LE(entry.offset + 26) + bytes.readUInt16LE(entry.offset + 28);
            const compressed = bytes.subarray(offset, offset + entry.compressed);
            assert.ok(entry.method === 0 || entry.method === 8, 'Unsupported native ZIP compression');
            const extracted = entry.method === 0 ? compressed : inflateRawSync(compressed, { maxOutputLength: entry.size });
            assert.equal(extracted.length, entry.size, 'Truncated native ZIP entry');
            return extracted;
        },
    };
}

test('bundled runtime and original OpenCV have verified provenance, exact hashes and matching ARM64 NDK notes', () => {
    const runtime = sourceBytes(provenance.runtime);
    const openCv = sourceBytes(provenance.originalOpenCv);
    assert.equal(runtime.length, provenance.runtime.bytes);
    for (const item of [provenance.runtime, provenance.originalOpenCv, provenance.notice]) {
        assert.equal(sha256(sourceBytes(item)), item.sha256, `Source hash changed: ${item.path}`);
    }
    const cppElf = elf(runtime);
    const cvElf = elf(openCv);
    assert.equal(cppElf.buildId, provenance.runtime.buildId);
    assert.equal(cvElf.buildId, provenance.originalOpenCv.buildId);
    for (const info of [cppElf, cvElf]) {
        assert.equal(info.ndkRelease, 'r25c');
        assert.equal(info.ndkBuild, '9519653');
    }
    assert.equal(cppElf.soname, 'libc++_shared.so');
    assert.equal(cvElf.soname, 'libopencv_java4.so');
    assert.ok(cvElf.needed.includes(cppElf.soname), 'Original OpenCV needs this C++ runtime');
    assert.match(readFileSync(path.join(sdk, provenance.notice.path), 'utf8'), /LLVM Exceptions/);
});

for (const [variant, apkPath] of [
    ['Debug', 'android/app/build/outputs/apk/debug/app-debug.apk'],
    ['Release', 'android/app/build/outputs/apk/release/app-release-unsigned.apk'],
]) {
    test(`${variant} APK includes original OpenCV plus matching C++ runtime, with unchanged loaded-image hashes`, () => {
        // Missing/stale APKs fail explicitly; build both variants before running this guard.
        const apk = zipEntries(readFileSync(path.join(root, apkPath)));
        // Keep the existing CameraX/ML Kit dependencies; do not mistake them for vendor SDKs.
        const expectedNames = ['lib/arm64-v8a/libc++_shared.so',
            'lib/arm64-v8a/libimage_processing_util_jni.so', 'lib/arm64-v8a/libmlkitcommonpipeline.so',
            'lib/arm64-v8a/libopencv_java4.so', 'lib/arm64-v8a/libsurface_util_jni.so'];
        assert.deepEqual(apk.names.filter(name => /^lib\/[^/]+\/[^/]+\.so$/.test(name)).sort(), expectedNames,
            'Unexpected ABI, missing runtime or vendor camera library in APK');
        for (const item of [provenance.runtime, provenance.originalOpenCv]) {
            const name = `lib/arm64-v8a/${path.basename(item.path)}`;
            const source = elf(sourceBytes(item));
            const packagedBytes = apk.read(name);
            const packaged = elf(packagedBytes);
            assert.equal(packaged.buildId, item.buildId, `${name} build ID`);
            assert.equal(packaged.ndkRelease, source.ndkRelease, `${name} NDK release`);
            assert.equal(packaged.ndkBuild, source.ndkBuild, `${name} NDK build`);
            assert.equal(packaged.soname, source.soname, `${name} SONAME`);
            assert.deepEqual(packaged.needed, source.needed, `${name} dependencies`);
            assert.equal(packaged.loadImageSha256, source.loadImageSha256, `${name} loaded code/data changed`);
            // Whole-file equality is preferred, but stripping non-loadable symbols is valid.
            assert.ok(sha256(packagedBytes) === item.sha256 || packaged.loadImageSha256 === source.loadImageSha256);
        }
    });
}
