// Isolated Android emulator tests. Never run adb against a user's device.
const fs = require('fs');
const path = require('path');
const assert = require('assert/strict');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

module.exports = function attach(p0) {
  const output = path.join(p0.logDir, 'original-media-results.json');
  const result = {
    actualUserProject: false, actualUserPhone: false, releaseReady: false,
    environment: {}, checks: [], errors: [], started: new Date().toISOString()
  };
  function save() { fs.writeFileSync(output, JSON.stringify(result, null, 2)); }
  async function waitFor(fn, timeout = 60000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (await p0.page.evaluate(fn)) return;
      await delay(400);
    }
    throw new Error('Timed out: ' + fn.toString());
  }
  async function snapshot() {
    await waitFor(() => !state.importing && !v038Saving && !v038Restoring);
    return p0.page.evaluate(async () => {
      clearTimeout(v038SaveTimer);
      if (!await v038SaveProject(true)) throw new Error('Project save failed');
      const db = await v038Db();
      const tx = db.transaction(['project', V038_MEDIA], 'readonly');
      const store = tx.objectStore(V038_MEDIA);
      const [project, keys, records] = await Promise.all([
        v038Req(tx.objectStore('project').get(V038_PROJECT)),
        v038Req(store.getAllKeys()), v038Req(store.getAll())
      ]);
      db.close();
      delete project.savedAt;
      const media = [];
      for (let i = 0; i < records.length; i++) {
        const bytes = await records[i].blob.arrayBuffer();
        const hash = await crypto.subtle.digest('SHA-256', bytes);
        media.push({ key: keys[i], bytes: bytes.byteLength,
          sha256: Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, '0')).join('') });
      }
      return { project, media };
    });
  }
  async function environment() {
    result.environment.android = await p0.adb(['shell', 'getprop', 'ro.build.version.release']);
    result.environment.sdk = await p0.adb(['shell', 'getprop', 'ro.build.version.sdk']);
    result.environment.model = await p0.adb(['shell', 'getprop', 'ro.product.model']);
    result.environment.hardwareAcceleration = false;
    result.environment.instrumentation = await p0.adb(['logcat', '-d', '-s', 'LEDOA_P0_TEST:I', '*:S']);
    result.environment.web = await p0.page.evaluate(() => {
      const v = document.createElement('video');
      return { agent: navigator.userAgent, origin: location.origin,
        hevc: v.canPlayType('video/mp4; codecs="hvc1.1.6.L30.B0"'),
        avc: v.canPlayType('video/mp4; codecs="avc1.640028"'),
        mp4Recorder: MediaRecorder.isTypeSupported('video/mp4;codecs=avc1,mp4a.40.2'),
        bridge: !!window.AndroidBridge };
    });
    p0.page.on('pageerror', error => { result.errors.push(error.message); save(); });
    save();
    return result.environment;
  }
  async function importOriginals() {
    assert.equal(await p0.page.evaluate(() => state.clips.length), 0, 'Only use a fresh isolated test project');
    const files = ['1000067125.mp4', '1000067126.mp4'].map(name => ({
      name, mimeType: 'video/mp4', buffer: fs.readFileSync(path.join(p0.root, 'upload', name))
    }));
    await p0.page.locator('#mediaInput').setInputFiles(files, { timeout: 120000 });
    await waitFor(() => state.clips.length === 2 && !state.importing, 120000);
    const imported = await p0.page.evaluate(() => state.clips.map(c => ({
      type: c.type, duration: c.sourceDuration, trimStart: c.trimStart, trimEnd: c.trimEnd,
      readyState: c.rt?.el.readyState, error: c.rt?.el.error?.code || null
    })));
    result.checks.push({ name: 'Import supplied HEVC originals into fresh emulator project', imported });
    save();
    return imported;
  }
  async function boundary(label, useTail = false) {
    await p0.page.evaluate(tail => {
      pause(); hideSheets();
      const [a, b] = state.clips;
      a.trimStart = tail ? Math.max(0, a.sourceDuration - 27.5) : 0;
      a.trimEnd = tail ? a.sourceDuration : 27.5;
      b.trimStart = 0; b.trimEnd = b.sourceDuration;
      for (const c of state.clips) { c.transition = 'cut'; c.speed = 1; }
      recalc(); seek(26.0); play();
    }, useTail);
    const samples = [], start = Date.now();
    let passed = false;
    while (Date.now() - start < 45000) {
      await delay(700);
      const sample = await p0.page.evaluate(() => ({
        wall: performance.now(), playing: state.playing, time: state.currentTime,
        index: activeInfo(state.currentTime)?.idx,
        attached: state.clips.filter(c => c.rt?.el.getAttribute('src')).length,
        media: state.clips.map(c => ({ currentTime: c.rt?.el.currentTime,
          ready: c.rt?.el.readyState, paused: c.rt?.el.paused,
          seeking: c.rt?.el.seeking, ended: c.rt?.el.ended,
          frames: c.rt?.el.webkitDecodedFrameCount, error: c.rt?.el.error?.code || null }))
      }));
      samples.push(sample);
      if (sample.time >= 30.0 && sample.index === 1) { passed = true; break; }
      if (!sample.playing && Date.now() - start > 12000) break;
    }
    const diagnostics = await p0.page.evaluate(() => {
      pause(); return window.LedoaPlaybackDiagnostics?.snapshot() || null;
    });
    const item = { name: label, useTail, passed, samples, diagnostics };
    result.checks.push(item); save();
    return { name: label, passed, samples: samples.length, last: samples.at(-1) };
  }
  async function addFeaturesAndSave() {
    await p0.page.evaluate(() => {
      pause();
      state.texts.push({ id: 'native-subtitle', kind: 'caption', text: '기존 자막 보존 시험',
        font: 'Gowun Dodum', size: 48, position: 'bottom', color: '#ffffff',
        bg: '#000000', bgOpacity: 0, start: 0, end: totalDuration() });
      LedoaMotionEditor.add(); LedoaMotionEditor.choose('MT-135');
      const motion = selectedOverlay();
      motion.text = '타이포 검증'; motion.start = 0; motion.end = totalDuration();
      motion.font = 'Gowun Dodum'; hideSheets(); seek(0); renderAll();
    });
    await p0.page.locator('#overlayInput').setInputFiles({ name: 'synthetic-blue.jpg',
      mimeType: 'image/jpeg', buffer: fs.readFileSync(path.join(p0.root, 'p0_mobile/fixtures/blue.jpg')) });
    await waitFor(() => state.overlays.some(o => o.type === 'image'));
    const original = await snapshot();
    fs.writeFileSync(path.join(p0.logDir, 'compatibility-before-private.json'), JSON.stringify(original));
    result.checks.push({ name: 'Baseline project saved with originals, caption, image and MT-135',
      media: original.media, clips: original.project.clips.length,
      overlays: original.project.overlays.length, texts: original.project.texts.length });
    save(); return { media: original.media, clips: original.project.clips.length };
  }
  async function compareUpgrade(label) {
    await waitFor(() => state.clips.length === 2 && !v038Restoring, 120000);
    const before = JSON.parse(fs.readFileSync(path.join(p0.logDir, 'compatibility-before-private.json')));
    const after = await snapshot();
    assert.deepEqual(after, before);
    const item = { name: label, passed: true, media: after.media,
      clipCount: after.project.clips.length, overlays: after.project.overlays.length };
    result.checks.push(item); save(); return item;
  }
  async function exportShort() {
    await p0.page.evaluate(() => {
      pause();
      state.clips[0].trimStart = 0; state.clips[0].trimEnd = 2;
      state.clips[1].trimStart = 0; state.clips[1].trimEnd = 2;
      recalc(); seek(0); openSheet('exportSheet');
      const original = saveBlobNative;
      window.p0NativeSave = null;
      saveBlobNative = async function(blob, name) {
        window.p0NativeSave = { name, bytes: blob.size, type: blob.type, completed: false };
        await original(blob, name); window.p0NativeSave.completed = true;
      };
    });
    await p0.page.locator('#startExportBtn').click({ timeout: 30000 });
    const start = Date.now(), samples = [];
    while (Date.now() - start < 120000) {
      await delay(1000);
      const sample = await p0.page.evaluate(() => ({ exporting: state.exporting,
        status: $('exportStatus').textContent, mime: state.recorder?.mimeType,
        save: window.p0NativeSave }));
      samples.push(sample);
      if (sample.save?.completed || /실패|오류|멈춤|중단/.test(sample.status)) break;
    }
    const item = { name: 'Four second original HEVC project export through native save bridge',
      samples, passed: false, qualityUnverified: true };
    result.checks.push(item); save(); return item;
  }
  async function restoreCompatibilityFixture() {
    const before = JSON.parse(fs.readFileSync(path.join(p0.logDir, 'compatibility-before-private.json')));
    await p0.page.evaluate(project => {
      pause();
      for (const clip of state.clips) {
        const metadata = project.clips.find(c => c.id === clip.id);
        if (!metadata) throw new Error('Missing test clip');
        clip.trimStart = metadata.trimStart; clip.trimEnd = metadata.trimEnd;
        clip.transition = metadata.transition; clip.speed = metadata.speed;
      }
      recalc(); seek(project.currentTime); hideSheets();
    }, before.project);
    return compareUpgrade('Compatibility fixture restored before rollback install');
  }
  return { result, save, waitFor, environment, importOriginals, boundary,
    addFeaturesAndSave, compareUpgrade, exportShort, restoreCompatibilityFixture, snapshot };
};
