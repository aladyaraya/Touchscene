// Maintainer-only regeneration. Normal checks need neither Git nor full history.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const commit = '3eea391f32985ddd79f94a560fc1dabc27aa5b8c';
const pkg = 'android/app/src/main/java/com/insta360/kmpsdk/demo/';
const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' });
const normalize = text => text.replace(/\r\n/g, '\n').trimEnd();
const original = file => normalize(git('show', `${commit}:${file}`));
const sources = git('ls-tree', '-r', '--name-only', commit, '--', pkg).trim().split('\n').map(path => ({
    path, sha256: createHash('sha256').update(original(path), 'utf8').digest('hex'),
}));
if (sources.length !== 101) throw new Error('Unexpected original source manifest');
const references = ['layout/fragment_preview.xml', 'layout/fragment_connection.xml',
    'layout/fragment_local_image_recognition.xml', 'values/strings.xml', 'values-en/strings.xml'];
const referenceTexts = Object.fromEntries(references.map(relative => {
    const file = `android/app/src/main/res/${relative}`;
    return [file, original(file)];
}));
const speechFile = `${pkg}touchscene/SpeechAndDescription.kt`;
const speech = original(speechFile);
// Only the protected local block is needed; never bundle old cloud configuration.
referenceTexts[speechFile] = speech.slice(speech.indexOf('data class SceneDescription'),
    speech.indexOf('class AndroidSpeechOutput')) + 'class AndroidSpeechOutput';
writeFileSync(new URL('original-baseline.json', import.meta.url), JSON.stringify({
    schemaVersion: 1, commit, normalization: 'UTF-8, CRLF to LF, trimEnd', sources, referenceTexts,
}, null, 2) + '\n');
console.log(`Generated ${sources.length} source hashes and ${Object.keys(referenceTexts).length} references from ${commit}`);
