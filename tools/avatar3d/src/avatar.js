// Avatar 3D do Euno: modelo VRM animado (respira, pisca, olha, fala e mostra emoções).
// O app controla tudo por window.Avatar (ver o fim do arquivo). Gerado para assets/avatar3d/avatar.js (npm run build).
import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';

const VISEMES = ['aa', 'ih', 'ou', 'ee', 'oh'];
const EMOTIONS = ['happy', 'sad', 'surprised', 'angry', 'relaxed'];

const canvas = document.getElementById('c');
const renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true, premultipliedAlpha: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.setClearColor(0x000000, 0);
renderer.outputColorSpace = THREE.SRGBColorSpace;

const scene = new THREE.Scene();
const camera = new THREE.PerspectiveCamera(20, 1, 0.1, 20);
const light = new THREE.DirectionalLight(0xffffff, Math.PI * 0.85);
light.position.set(0.6, 1, 1.2).normalize();
scene.add(light);
scene.add(new THREE.AmbientLight(0xffffff, 0.25));

const lookTarget = new THREE.Object3D();
scene.add(lookTarget);

const st = {
  vrm: null,
  loaded: false,
  error: null,
  mouth: {}, // alvo dos visemas (0..1)
  cur: {}, // valor atual (suavizado)
  emotion: null,
  emotionWeight: 0,
  mode: 'idle',
  look: { x: 0, y: 0 },
  paused: false,
  nextBlink: 2,
  blinkT: -1,
  headY: 1.4,
  top: 0,
  height: 0,
  framing: 'busto',
  bust: 0.37, // fração da altura no "busto"
};

// Modelos de proporção realista (cabeça pequena em relação ao corpo) pedem um busto mais fechado para o rosto aparecer.
const BUST_BY_MODEL = { guardiao: 0.26 };

function resize() {
  const w = canvas.clientWidth || window.innerWidth;
  const h = canvas.clientHeight || window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / Math.max(h, 1);
  camera.updateProjectionMatrix();
  frame();
}
window.addEventListener('resize', resize);

function frame() {
  // Enquadramento de busto (cabeça e ombros) ou meio corpo.
  // Pelo tamanho real do modelo (serve para qualquer VRM): busto = ~37% de cima; corpo = ~62%.
  const H = st.height > 0.3 ? st.height : 1.6;
  const top = st.height > 0.3 ? st.top : st.headY + 0.2;
  const span = H * (st.framing === 'corpo' ? 0.62 : st.bust);
  const centerY = top - span * 0.47;
  const dist = (span / 2) / Math.tan(THREE.MathUtils.degToRad(camera.fov / 2)) / Math.min(1, camera.aspect);
  camera.position.set(0, centerY, dist);
  camera.lookAt(0, centerY, 0);
  lookTarget.position.set(0, st.headY, dist);
}

function notify(kind, detail) {
  try {
    if (window.EunoAvatar && window.EunoAvatar[kind]) window.EunoAvatar[kind](String(detail || ''));
  } catch (e) { /* sem ponte (teste no navegador) */ }
}

function relaxArms(vrm) {
  const h = vrm.humanoid;
  // VRM 0.x olha para -Z antes de girar: o sentido do giro dos braços é o contrário.
  const k = vrm.meta && vrm.meta.metaVersion === '0' ? -1 : 1;
  const set = (name, x, y, z) => { const b = h.getNormalizedBoneNode(name); if (b) b.rotation.set(x, y, z); };
  // VRM vem em pose "T": baixa os braços para uma pose natural.
  set('leftUpperArm', 0, 0, -1.2 * k);
  set('rightUpperArm', 0, 0, 1.2 * k);
  set('leftLowerArm', 0, -0.15 * k, 0);
  set('rightLowerArm', 0, 0.15 * k, 0);
}

function load(url) {
  const loader = new GLTFLoader();
  loader.register((parser) => new VRMLoaderPlugin(parser));
  loader.load(
    url,
    (gltf) => {
      const vrm = gltf.userData.vrm;
      if (!vrm) { st.error = 'arquivo sem VRM'; notify('onError', st.error); return; }
      if (VRMUtils.removeUnnecessaryVertices) VRMUtils.removeUnnecessaryVertices(gltf.scene);
      if (VRMUtils.combineSkeletons) VRMUtils.combineSkeletons(gltf.scene);
      VRMUtils.rotateVRM0(vrm);
      vrm.scene.traverse((o) => { o.frustumCulled = false; });
      scene.add(vrm.scene);
      relaxArms(vrm);
      vrm.update(0);
      const head = vrm.humanoid.getNormalizedBoneNode('head');
      if (head) { const p = new THREE.Vector3(); head.getWorldPosition(p); st.headY = p.y + 0.06; }
      const box = new THREE.Box3().setFromObject(vrm.scene);
      if (isFinite(box.max.y)) { st.top = box.max.y; st.height = box.max.y - Math.max(0, box.min.y); }
      if (vrm.lookAt) vrm.lookAt.target = lookTarget;
      st.vrm = vrm;
      st.loaded = true;
      resize();
      notify('onReady', (vrm.meta && (vrm.meta.name || vrm.meta.title)) || 'avatar');
    },
    undefined,
    (err) => { st.error = String((err && err.message) || err); notify('onError', st.error); },
  );
}

function approach(cur, target, k) { return cur + (target - cur) * k; }

let lastNow = performance.now();
let acc = 0;
let t = 0;
function loop() {
  requestAnimationFrame(loop);
  const now = performance.now();
  const dt = Math.min((now - lastNow) / 1000, 0.1);
  lastNow = now;
  if (st.paused) return;
  acc += dt;
  if (acc < 1 / 32) return; // ~30 quadros por segundo: poupa bateria
  const step = acc;
  acc = 0;
  t += step;
  const vrm = st.vrm;
  if (!vrm) { renderer.render(scene, camera); return; }
  const h = vrm.humanoid;
  const em = vrm.expressionManager;

  // Respiração e balanço leve do corpo e da cabeça.
  const breath = Math.sin(t * 2 * Math.PI / 4.2);
  const spine = h.getNormalizedBoneNode('spine'); if (spine) spine.rotation.x = 0.015 * breath;
  const chest = h.getNormalizedBoneNode('chest'); if (chest) chest.rotation.x = 0.02 * breath;
  const neck = h.getNormalizedBoneNode('neck');
  const head = h.getNormalizedBoneNode('head');
  let hx = 0.03 * Math.sin(t * 0.7), hy = 0.05 * Math.sin(t * 0.43), hz = 0.02 * Math.sin(t * 0.37);
  if (st.mode === 'listening') { hz += 0.09; hx += 0.04; }
  if (st.mode === 'thinking') { hx -= 0.12; hy += 0.18; }
  if (st.mode === 'speaking') { hx += 0.04 * Math.sin(t * 5.1) * (st.cur.aa || 0.3); hy += 0.03 * Math.sin(t * 1.9); }
  if (st.mode === 'sleeping') { hx += 0.25; }
  hy += st.look.x * 0.25; hx += -st.look.y * 0.15;
  if (neck) neck.rotation.set(hx * 0.4, hy * 0.4, hz * 0.4);
  if (head) head.rotation.set(hx * 0.6, hy * 0.6, hz * 0.6);
  lookTarget.position.x = st.look.x * 0.6;
  lookTarget.position.y = st.headY + st.look.y * 0.4 + (st.mode === 'thinking' ? 0.5 : 0);

  if (em) {
    // Fala: visemas suavizados (abrem rápido e fecham um pouco mais devagar).
    for (const v of VISEMES) {
      const target = st.mode === 'speaking' ? (st.mouth[v] || 0) : 0;
      const c = st.cur[v] || 0;
      st.cur[v] = approach(c, target, target > c ? 0.65 : 0.4);
      em.setValue(v, st.cur[v]);
    }
    // Emoção: entra e sai suave; enquanto fala, um pouco mais leve para a boca aparecer.
    for (const e of EMOTIONS) {
      let target = st.emotion === e ? st.emotionWeight : 0;
      if (st.mode === 'speaking' && e === 'happy') target *= 0.55;
      const c = st.cur[e] || 0;
      st.cur[e] = approach(c, target, 0.12);
      em.setValue(e, st.cur[e]);
    }
    // Piscar de tempos em tempos (e olhos fechados dormindo).
    let blink = 0;
    if (st.mode === 'sleeping') blink = 1;
    else {
      st.nextBlink -= step;
      if (st.nextBlink <= 0 && st.blinkT < 0) { st.blinkT = 0; st.nextBlink = 2 + Math.random() * 3.5; }
      if (st.blinkT >= 0) {
        st.blinkT += step;
        const p = st.blinkT / 0.16;
        blink = p < 0.5 ? p * 2 : Math.max(0, 2 - p * 2);
        if (p >= 1) st.blinkT = -1;
      }
    }
    if ((st.cur.happy || 0) > 0.5) blink = Math.max(0, blink - (st.cur.happy - 0.5));
    em.setValue('blink', blink);
  }
  vrm.update(step);
  renderer.render(scene, camera);
}

// Interface usada pelo app (Kotlin chama via evaluateJavascript).
window.Avatar = {
  load,
  /** Pesos dos visemas: {aa, ih, ou, ee, oh} de 0 a 1. */
  setMouth(w) { st.mouth = w || {}; },
  /** Emoção: happy, sad, surprised, angry, relaxed ou null; peso 0..1. */
  setEmotion(name, weight) { st.emotion = name || null; st.emotionWeight = weight == null ? 1 : weight; },
  /** idle, listening, thinking, speaking, sleeping. */
  setMode(m) { st.mode = m || 'idle'; },
  lookAt(x, y) { st.look.x = x || 0; st.look.y = y || 0; },
  setPaused(p) { st.paused = !!p; },
  setFraming(f) { st.framing = f === 'corpo' ? 'corpo' : 'busto'; frame(); },
  status() { return JSON.stringify({ loaded: st.loaded, error: st.error, mode: st.mode }); },
  /** Foto do avatar agora (PNG em data URL), para a miniatura no chat e na tela inicial. */
  snapshot() { if (!st.vrm) return ''; renderer.render(scene, camera); return canvas.toDataURL('image/png'); },
  /** Diagnóstico: alturas dos ossos (para conferir o enquadramento). */
  bones() {
    if (!st.vrm) return '{}';
    const o = { top: st.top, height: st.height, headY: st.headY };
    for (const n of ['hips', 'spine', 'chest', 'upperChest', 'neck', 'head', 'leftEye']) {
      const b = st.vrm.humanoid.getNormalizedBoneNode(n);
      if (b) { const p = new THREE.Vector3(); b.getWorldPosition(p); o[n] = +p.y.toFixed(3); }
    }
    return JSON.stringify(o);
  },
  value(name) { return st.vrm && st.vrm.expressionManager ? st.vrm.expressionManager.getValue(name) : null; },
};

resize();
loop();
const params = new URLSearchParams(location.search);
const modelUrl = params.get('model') || 'models/avatar.vrm';
const modelId = (modelUrl.split('/').pop() || '').replace(/\.vrm$/, '');
if (BUST_BY_MODEL[modelId]) st.bust = BUST_BY_MODEL[modelId];
load(modelUrl);
