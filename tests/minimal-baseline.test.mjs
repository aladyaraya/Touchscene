import { createHash } from 'node:crypto';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import assert from 'node:assert/strict';
import test from 'node:test';

const root = fileURLToPath(new URL('../', import.meta.url));
const baseline = '3eea391f32985ddd79f94a560fc1dabc27aa5b8c';
const pkg = 'android/app/src/main/java/com/insta360/kmpsdk/demo/';
const normalize = text => text.replace(/\r\n/g, '\n').trimEnd();
const source = file => readFileSync(path.join(root, file), 'utf8');
const fixture = JSON.parse(source('tests/fixtures/original-baseline.json'));
assert.equal(fixture.commit, baseline, 'Only the reviewed original baseline may be used');
const original = file => {
    assert.equal(typeof fixture.referenceTexts[file], 'string', `Missing original reference: ${file}`);
    return fixture.referenceTexts[file];
};
const textHash = text => createHash('sha256').update(normalize(text), 'utf8').digest('hex');
const cloudChanges = new Set(['touchscene/RemoteAiSceneDescriber.kt', 'touchscene/RemoteAsrTranscriber.kt',
    'touchscene/SpeechAndDescription.kt']);
const previewLayout = 'android/app/src/main/res/layout/fragment_preview.xml';
const homeLayout = 'android/app/src/main/res/layout/fragment_connection.xml';
const unifiedStrings = ['unified_action_live', 'unified_action_frozen', 'unified_action_recording',
    'unified_action_processing', 'unified_action_listening', 'unified_action_description',
    'unified_home_camera', 'unified_home_image', 'unified_home_open_camera', 'unified_home_open_picker'];
const holdStrings = ['hold_mic_hint', 'hold_mic_recording', 'hold_mic_processing',
    'hold_mic_button_description', 'hold_mic_button_recording_description',
    'hold_mic_button_processing_description', 'hold_layer_gesture_hint'];
const preservedHoldCopy = {
    values: ['按住说话，松开发送', '正在录音，松开发送', '正在识别…',
        '按住说话，松开发送。使用读屏时，双击并按住开始说话，松开发送。',
        '正在录音，松开发送。使用读屏时保持按住，松开发送。', '正在识别语音，请稍候。', '双击画面切换模式'],
    'values-en': ['Hold to speak, release to send', 'Recording, release to send', 'Recognizing…',
        'Hold to speak, release to send. With TalkBack, double-tap and hold to speak, then release to send.',
        'Recording, release to send. With TalkBack, keep holding and release to send.',
        'Recognizing speech, please wait.', 'Double-tap the image to switch modes'],
};
const stripComments = text => text.replace(/<!--[\s\S]*?-->/g, '');
const escapeRegExp = text => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const canonicalXml = text => normalize(stripComments(text)).replace(/>\s+</g, '><');

// Locate the complete element, including nested elements with the same XML tag.
// A non-greedy LinearLayout regex would otherwise stop before the legacy controls.
function elementWithId(xml, id) {
    const opening = new RegExp(`<([\\w.:-]+)\\b[^>]*\\bandroid:id="@\\+id/${escapeRegExp(id)}"[^>]*>`, 'g');
    const matches = [...xml.matchAll(opening)];
    assert.equal(matches.length, 1, `Expected exactly one ${id} element`);
    const match = matches[0];
    const start = match.index;
    const contentStart = start + match[0].length;
    if (/\/\s*>$/.test(match[0])) {
        return { start, end: contentStart, opening: match[0], inner: '', text: match[0] };
    }
    const tags = new RegExp(`</?${escapeRegExp(match[1])}\\b[^>]*>`, 'g');
    tags.lastIndex = contentStart;
    let depth = 1;
    for (let token; (token = tags.exec(xml));) {
        if (token[0].startsWith('</')) depth--;
        else if (!/\/\s*>$/.test(token[0])) depth++;
        if (depth === 0) {
            const end = tags.lastIndex;
            return { start, end, opening: match[0], inner: xml.slice(contentStart, token.index), text: xml.slice(start, end) };
        }
    }
    assert.fail(`Unclosed ${id} element`);
}

function attributes(opening) {
    return Object.fromEntries([...opening.matchAll(/([\w:-]+)="([^"]*)"/g)].map(match => [match[1], match[2]]));
}

function maskApprovedPreviewChanges(text) {
    let xml = stripComments(text);
    // The round mic subtree is explicitly removed, not replaced by another map overlay.
    if (/android:id="@\+id\/voice_mic_group"/.test(xml)) {
        const mic = elementWithId(xml, 'voice_mic_group');
        xml = xml.slice(0, mic.start) + xml.slice(mic.end);
    }
    const bar = elementWithId(xml, 'bottom_action_bar');
    return canonicalXml(xml.slice(0, bar.start) + '<APPROVED_CAMERA_ACTIONS />' + xml.slice(bar.end));
}

function layoutTree(xml) {
    const stack = [];
    let rootNode;
    for (const token of stripComments(xml).matchAll(/<(\/?)([\w.:-]+)\b[^>]*>/g)) {
        if (token[1]) {
            assert.equal(stack.pop()?.tag, token[2], 'Layout tags must be balanced');
            continue;
        }
        const parent = stack.at(-1);
        const node = { tag: token[2], attributes: attributes(token[0]), children: [], parent };
        if (parent) parent.children.push(node);
        else { assert.ok(!rootNode, 'Layout has one root'); rootNode = node; }
        if (!/\/\s*>$/.test(token[0])) stack.push(node);
    }
    assert.equal(stack.length, 0);
    return rootNode;
}

function allNodes(node) {
    return [node, ...node.children.flatMap(allNodes)];
}

function stringEntries(xml) {
    const entries = [...stripComments(xml).matchAll(/<(string|string-array|plurals)\s+[^>]*\bname="([^"]+)"[^>]*>[\s\S]*?<\/\1>/g)];
    const strings = new Map(entries.map(match => [match[2], normalize(match[0])]));
    assert.equal(strings.size, entries.length, 'String resources must have unique names');
    return strings;
}

test('all original source files except approved cloud adapters retain baseline contents', () => {
    const files = fixture.sources;
    assert.equal(new Set(files.map(item => item.path)).size, 101, 'No duplicate baseline sources');
    let checked = 0;
    for (const { path: file, sha256 } of files) {
        assert.ok(file.startsWith(pkg) && !file.includes('..'), 'Baseline path must stay in original source root');
        if (cloudChanges.has(file.slice(pkg.length))) continue;
        assert.ok(existsSync(path.join(root, file)), `Original source missing: ${file}`);
        assert.equal(textHash(source(file)), sha256, file);
        checked++;
    }
    assert.equal(files.length, 101, 'The original source manifest must not shrink');
    assert.equal(checked, 98, 'Exactly the three approved cloud adapters remain exempt');
});

test('speech fallback does not change original local semantics or YUV conversion', () => {
    const file = pkg + 'touchscene/SpeechAndDescription.kt';
    const block = text => normalize(text.slice(text.indexOf('data class SceneDescription'), text.indexOf('class AndroidSpeechOutput')));
    assert.equal(block(source(file)), block(original(file)));
});

test('original local image layout remains completely unchanged', () => {
    const file = 'android/app/src/main/res/layout/fragment_local_image_recognition.xml';
    assert.equal(normalize(source(file)), normalize(original(file)), file);
});

test('preview changes remove only the round microphone and replace the bottom controls', () => {
    const current = source(previewLayout);
    const before = original(previewLayout);
    assert.equal(maskApprovedPreviewChanges(current), maskApprovedPreviewChanges(before),
        'The 75% map, live preview, legacy controls and all XML outside approved subtrees stay original');
    assert.doesNotMatch(current, /android:id="@\+id\/(?:voice_mic_group|voice_mic_btn|voice_mic_hint|upload_image_btn)"/);
    assert.equal(attributes(elementWithId(current, 'layer_container').opening)['app:layout_constraintHeight_percent'], '0.75');
    assert.equal(canonicalXml(elementWithId(current, 'legacy_controls_container').text),
        canonicalXml(elementWithId(before, 'legacy_controls_container').text));
});

test('camera bottom quarter is one full-area action with only a noninteractive hint overlay', () => {
    const current = stripComments(source(previewLayout));
    const before = stripComments(original(previewLayout));
    const bar = elementWithId(current, 'bottom_action_bar');
    const barAttributes = attributes(bar.opening);
    const oldBarAttributes = attributes(elementWithId(before, 'bottom_action_bar').opening);
    assert.match(bar.opening, /^<FrameLayout\b/);
    for (const name of ['android:gravity', 'android:orientation', 'android:paddingHorizontal', 'android:paddingVertical']) {
        assert.ok(!(name in barAttributes), `Bottom wrapper must not inset the action: ${name}`);
        delete oldBarAttributes[name];
    }
    assert.deepEqual(barAttributes, oldBarAttributes, 'Do not expand the approved action-bar wrapper changes');
    assert.deepEqual([...bar.inner.matchAll(/<(?!\/)([\w.:-]+)\b[^>]*>/g)].map(match => match[1]),
        ['com.google.android.material.button.MaterialButton', 'TextView']);
    assert.deepEqual([...bar.inner.matchAll(/android:id="@\+id\/([^"]+)"/g)].map(match => match[1]),
        ['toggle_describe_live_btn', 'layer_gesture_hint']);
    assert.doesNotMatch(current, /android:id="@\+id\/upload_image_btn"/);

    const buttonAttributes = attributes(elementWithId(current, 'toggle_describe_live_btn').opening);
    const oldButtonAttributes = attributes(elementWithId(before, 'toggle_describe_live_btn').opening);
    assert.equal(buttonAttributes['android:layout_width'], 'match_parent');
    assert.equal(buttonAttributes['android:layout_height'], 'match_parent');
    assert.ok(!('android:layout_weight' in buttonAttributes));
    assert.ok(!('android:layout_marginEnd' in buttonAttributes));
    const approvedButtonAttributes = {
        'android:text': '@string/unified_action_live',
        'android:contentDescription': '@string/unified_action_description',
        'android:textSize': '22sp',
        'app:cornerRadius': '0dp',
        'android:minWidth': '0dp',
        'android:minHeight': '0dp',
        'android:insetLeft': '0dp',
        'android:insetRight': '0dp',
        'android:gravity': 'center',
    };
    for (const [name, value] of Object.entries(approvedButtonAttributes)) assert.equal(buttonAttributes[name], value, name);
    for (const name of ['android:layout_width', 'android:layout_height', 'android:layout_weight', 'android:layout_marginEnd',
        ...Object.keys(approvedButtonAttributes)]) {
        delete buttonAttributes[name];
        delete oldButtonAttributes[name];
    }
    assert.deepEqual(buttonAttributes, oldButtonAttributes, 'Unapproved primary-button styling stays original');
    const hint = attributes(elementWithId(current, 'layer_gesture_hint').opening);
    assert.equal(hint['android:text'], '@string/hold_layer_gesture_hint');
    assert.equal(hint['android:clickable'], 'false');
    assert.equal(hint['android:focusable'], 'false');
    assert.equal(hint['android:importantForAccessibility'], 'no');
    assert.equal(hint['android:layout_gravity'], 'bottom|center_horizontal');
    assert.equal(hint['android:layout_marginBottom'], '10dp');
});

test('unified resources are narrowly added in both locales and every original string remains exact', () => {
    for (const directory of ['values', 'values-en']) {
        const file = `android/app/src/main/res/${directory}/strings.xml`;
        const current = stringEntries(source(file));
        const before = stringEntries(original(file));
        for (const [name, value] of before) assert.equal(current.get(name), value, `${directory}: original string changed: ${name}`);
        const additions = [...current.keys()].filter(name => !before.has(name)).sort();
        assert.deepEqual(additions, [...holdStrings, ...unifiedStrings].sort(), `${directory}: do not expand the resource exception`);
        holdStrings.forEach((name, index) => assert.equal(current.get(name),
            `<string name="${name}">${preservedHoldCopy[directory][index]}</string>`,
            `${directory}: preserve the previous hold copy too: ${name}`));
        if (directory === 'values') {
            assert.match(current.get('hold_mic_hint'), /按住[\s\S]*松开/);
            assert.match(current.get('hold_mic_recording'), /录音[\s\S]*松开/);
            assert.match(current.get('hold_layer_gesture_hint'), />双击画面切换模式<\/string>/);
            assert.match(current.get('unified_action_live'), /点按[\s\S]*长按/);
            assert.match(current.get('unified_action_recording'), /正在录像[\s\S]*长按[\s\S]*停止录像/,
                'Video recording must explain the spoken stop command, not claim release-to-send');
        }
    }
});

test('homepage offers exactly two equally weighted full-half actions and keeps other IDs hidden', () => {
    const current = stripComments(source(homeLayout));
    const before = stripComments(original(homeLayout));
    const tree = layoutTree(current);
    assert.equal(tree.tag, 'com.insta360.kmpsdk.demo.widget.GestureAwareNestedScrollView');
    assert.equal(tree.attributes['android:id'], '@+id/connection_scroll_view');
    assert.equal(tree.attributes['android:fillViewport'], 'true');
    assert.equal(tree.attributes['android:layout_width'], 'match_parent');
    assert.equal(tree.attributes['android:layout_height'], 'match_parent');
    assert.equal(tree.children.length, 1);
    const row = tree.children[0];
    assert.equal(row.tag, 'LinearLayout');
    assert.equal(row.attributes['android:layout_width'], 'match_parent');
    assert.equal(row.attributes['android:layout_height'], 'match_parent');
    assert.equal(row.attributes['android:orientation'], 'vertical');
    for (const wrapper of [tree, row]) {
        assert.ok(!Object.keys(wrapper.attributes).some(name => /^android:(?:padding|layout_margin)/.test(name)),
            'Full-half homepage actions must not be reduced by page padding or margins');
    }
    assert.equal(row.children.length, 3, 'Two actions and one hidden compatibility subtree only');
    const [camera, image, legacy] = row.children;
    for (const [button, id, text, description] of [
        [camera, 'btn_wifi', 'unified_home_camera', 'unified_home_camera'],
        [image, 'btn_local_image', 'unified_home_image', 'unified_home_image'],
    ]) {
        assert.equal(button.tag, 'com.google.android.material.button.MaterialButton');
        assert.equal(button.attributes['android:id'], `@+id/${id}`);
        assert.equal(button.attributes['android:layout_width'], 'match_parent');
        assert.equal(button.attributes['android:layout_height'], '0dp');
        assert.equal(button.attributes['android:layout_weight'], '1');
        assert.ok(!Object.keys(button.attributes).some(name => /^android:layout_margin/.test(name)));
        for (const name of ['android:minWidth', 'android:minHeight', 'android:insetLeft', 'android:insetRight',
            'android:insetTop', 'android:insetBottom']) assert.equal(button.attributes[name], '0dp', `${id}: ${name}`);
        assert.equal(button.attributes['android:text'], `@string/${text}`);
        assert.equal(button.attributes['android:contentDescription'], `@string/${description}`);
        assert.ok(!button.attributes['android:visibility'] || button.attributes['android:visibility'] === 'visible');
    }
    assert.equal(legacy.attributes['android:visibility'], 'gone');
    const ids = xml => [...xml.matchAll(/android:id="@\+id\/([^"]+)"/g)].map(match => match[1]).sort();
    assert.deepEqual(ids(current), ids(before), 'Do not add new home/legacy controls or remove existing binding IDs');
    const hiddenIds = new Set(allNodes(legacy).map(node => node.attributes['android:id']).filter(Boolean));
    for (const id of ids(before).filter(id => !['connection_scroll_view', 'btn_wifi', 'btn_local_image'].includes(id))) {
        assert.ok(hiddenIds.has(`@+id/${id}`), `Old home entry must stay hidden: ${id}`);
    }
});

test('approved preview masking cannot hide map, live-preview geometry or legacy-control edits', () => {
    const beforeLayout = original(previewLayout);
    const expected = maskApprovedPreviewChanges(beforeLayout);
    const mapChanged = beforeLayout.replace('app:layout_constraintHeight_percent="0.75"', 'app:layout_constraintHeight_percent="0.50"');
    assert.notEqual(maskApprovedPreviewChanges(mapChanged), expected);
    for (const [id, before, after] of [
        ['live_preview_container', 'android:layout_width="match_parent"', 'android:layout_width="80dp"'],
        ['legacy_controls_container', 'android:visibility="gone"', 'android:visibility="visible"'],
    ]) {
        const element = elementWithId(beforeLayout, id);
        assert.ok(element.text.includes(before), `Expected baseline attribute for ${id}`);
        const changed = beforeLayout.slice(0, element.start) + element.text.replace(before, after) + beforeLayout.slice(element.end);
        assert.notEqual(maskApprovedPreviewChanges(changed), expected, `${id} must remain protected`);
    }
});

test('phone build has no vendor SDK dependencies or Maven credentials', () => {
    const build = source('android/app/build.gradle.kts');
    assert.doesNotMatch(build, /implementation\(libs\.inskmp\./);
    assert.match(build, /java\.setSrcDirs\(phoneSourceDirs\)/);
    assert.match(build, /"src\/mobile\/java"/);
    assert.doesNotMatch(source('android/settings.gradle.kts'), /androidsdk\.insta360|instaCredential|INSTA360_MAVEN/);
    assert.doesNotMatch(source('android/app/src/main/AndroidManifest.xml'), /USB_DEVICE_ATTACHED|CameraSessionForegroundService|BLUETOOTH|ACCESS_FINE_LOCATION/);
});

test('voice photo saves exactly once and refreshes description from that same saved URI', () => {
    const preview = source('android/app/src/mobile/java/com/insta360/kmpsdk/demo/ui/capture/PreviewFragment.kt');
    const photo = preview.slice(preview.indexOf('private fun takePhonePhoto()'), preview.indexOf('private fun startPhoneRecording()'));
    assert.match(photo, /requestStillFrame\(refresh = touchSceneCoordinator\?\.currentSessionState\(\) != TactileSessionState\.LIVE,\s*saveToGallery = true\)/);
    assert.doesNotMatch(photo, /\.takePhoto\(|freezeCurrentFrame\(|returnToLive\(/,
        'A voice photo uses the shared single-photo request, even while a map is frozen');
    const request = preview.slice(preview.indexOf('private fun requestStillFrame('),
        preview.indexOf('private fun captureRequestedStillIfReady()'));
    assert.match(request, /stillRequestSaveToGallery = saveToGallery/);
    assert.match(request, /pendingStillCaptureGeneration = generation/);
    assert.match(request, /captureRequestedStillIfReady\(\)/);
    assert.doesNotMatch(request, /\.takePhoto\(/, 'Readiness retry must not introduce an independent capture call');
    const capture = preview.slice(preview.indexOf('private fun captureRequestedStillIfReady()'),
        preview.indexOf('private fun failStillRequest('));
    assert.equal([...preview.matchAll(/\.takePhoto\(/g)].length, 1, 'All page captures share one real shutter call');
    assert.match(capture, /pendingStillCaptureGeneration = null[\s\S]*camera\.takePhoto\(saveToGallery = stillRequestSaveToGallery\)/,
        'Claim the pending generation before the single CameraX call');
    assert.match(capture, /result\.onSuccess\s*\{\s*uri\s*->[\s\S]*?processPhoneImage\(uri, generation\)/);
    assert.doesNotMatch(capture, /freezeCurrentFrame\(|requestStillFrame\(/,
        'The exact saved URI is processed without a second freeze/refresh photo');
    assert.match(preview, /private fun finishStillRequest[\s\S]*?describeLatestScene\(\)/);
});

test('active phone preview uses hold input, no picker, and the original-image narration pipeline', () => {
    const preview = source('android/app/src/mobile/java/com/insta360/kmpsdk/demo/ui/capture/PreviewFragment.kt');
    assert.match(preview, /bindUnifiedAction\(binding\.toggleDescribeLiveBtn\)/);
    assert.doesNotMatch(preview, /binding\.voiceMic(?:Btn|Hint)|onMicButtonClicked|beginMicPrompt/,
        'The removed round microphone must not retain bindings or startup-prompt recording');
    assert.doesNotMatch(preview, /attachTactileHapticFeedback\(binding\.toggleDescribeLiveBtn\)/,
        'The unified button must not use the original continuous-vibration helper');
    assert.doesNotMatch(preview, /ActivityResultContracts\.(?:OpenDocument|GetContent|PickVisualMedia|StartActivityForResult)|ACTION_(?:OPEN_DOCUMENT|GET_CONTENT|PICK)|uploadImageBtn|uploadCameraImage|imagePickerLauncher|photoPickerLauncher/,
        'Only the camera page loses image selection; its old or replacement picker must not remain reachable');

    const originalImage = preview.slice(preview.indexOf('private fun processPhoneImage('),
        preview.indexOf('private fun setupTouchScene()'));
    assert.match(originalImage, /decodeSampledBitmap\(resolver, uri, MAX_UPLOAD_LONG_EDGE\)/);
    assert.match(originalImage, /bitmap\.toYuv420Frame\(now, now\)/);
    assert.match(originalImage, /LatestCameraFrameStore\.publish\(frame\)/);
    assert.match(originalImage, /touchSceneCoordinator\?\.analyzeAndFreeze\(frame, rotationOverride = 0\)/);
    assert.doesNotMatch(originalImage, /tactileMapView|debugMode|renderToBitmap|drawToBitmap/,
        'Description input must be the decoded captured image, never the visible edge/contour layer');

    const flush = preview.slice(preview.indexOf('private fun flushPendingNarration()'),
        preview.indexOf('private fun describeLatestScene()'));
    assert.match(flush, /val text = if \(layerHintAnnounced\) pending\.text else/);
    assert.ok(flush.includes('"。双击画面，可在原图、边缘、轮廓间切换。"'),
        'The first eligible description must append the approved exact layer-gesture hint');
    assert.match(flush, /layerHintAnnounced = true[\s\S]*speechOutput\?\.speak\(text\)/);
    // The first test already byte-protects ManualWavRecorder and WavPcmEncoder against
    // the baseline, including the original 16 kHz mono PCM16 WAV format. Do not relax it.
});
