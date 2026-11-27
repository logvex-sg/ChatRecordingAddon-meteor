/* Drives the shipped radar drawing and slot translation out of app.html.
 *
 * Both are geometry that a browser cannot complain about: a dot placed with a flipped axis still
 * lands inside the circle, and a click on the wrong slot index acts on a different stack. Neither
 * throws, so only arithmetic checks catch them. The functions are sliced out of the real script
 * rather than reimplemented, so this fails if the shipped code changes behaviour.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, '../../src/main/resources/companion/app.html'), 'utf8');
const script = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1])
  .sort((a, b) => b.length - a.length)[0];

const checks = [];
const check = (name, cond, detail = '') => {
  checks.push(!!cond);
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name + (detail ? '  [' + detail + ']' : ''));
};

// ---------------------------------------------------------------- slot translation

const translateStart = script.indexOf('function handlerSlotFor(');
const translateEnd = script.indexOf('/** The armour slot');
if (translateStart < 0 || translateEnd < 0) throw new Error('could not locate handlerSlotFor');
const handlerSlotFor = new Function(
  script.slice(translateStart, translateEnd) + '\nreturn handlerSlotFor;')();

check('hotbar 0 is handler slot 36', handlerSlotFor(0) === 36);
check('hotbar 8 is handler slot 44', handlerSlotFor(8) === 44);
check('main inventory 9 is unchanged', handlerSlotFor(9) === 9);
check('main inventory 35 is unchanged', handlerSlotFor(35) === 35);
check('armour 39 is handler slot 5', handlerSlotFor(39) === 5, String(handlerSlotFor(39)));
check('armour 36 is handler slot 8', handlerSlotFor(36) === 8, String(handlerSlotFor(36)));
check('off hand 40 is handler slot 45', handlerSlotFor(40) === 45);

// ---------------------------------------------------------------- radar

// drawRadar reads module-level state, so it is rebuilt with a controllable one.
const radarStart = script.indexOf('function drawRadar(');
if (radarStart < 0) throw new Error('could not locate drawRadar');
const radarEnd = script.indexOf('\n}\n', radarStart) + 3;
const radarBody = script.slice(radarStart, radarEnd);

function draw(entities, options) {
  const radarState = {
    northUp: options.northUp, yaw: options.dragYaw || 0,
    range: options.range || 64, dragging: false, lastX: 0
  };
  const RADAR_KINDS = {
    player: { colour: '#8fe3ff' }, hostile: { colour: '#ff5f5f' },
    passive: { colour: '#8de08d' }, item: { colour: '#ffd166' }
  };
  const arcs = [];
  const texts = [];
  const lines = [];
  const ctx = {
    fillStyle: '', strokeStyle: '', lineWidth: 1, font: '',
    clearRect() {}, beginPath() {}, fill() {}, stroke() {},
    arc(x, y, r) { arcs.push({ x, y, r }); },
    moveTo(x, y) { lines.push({ x, y, from: true }); },
    lineTo(x, y) { lines.push({ x, y, from: false }); },
    fillText(t, x, y) { texts.push({ t, x, y }); }
  };
  const canvas = { width: 600, height: 600, getContext: () => ctx };
  const fn = new Function('radarState', 'RADAR_KINDS', radarBody + '\nreturn drawRadar;')(
    radarState, RADAR_KINDS);
  fn(canvas, entities, options.me || { yaw: 0 });
  // Entity markers are 4.5 px (6 for a player); the rings and the player dot are larger.
  const markers = arcs.filter(a => Math.abs(a.r - 4.5) < 0.01 || Math.abs(a.r - 6) < 0.01);
  return { markers, texts, arcs, lines };
}

const CENTRE = 300;
const entity = (dx, dz, extra = {}) =>
  ({ kind: 'hostile', dx, dz, dy: 0, yaw: 0, distance: Math.hypot(dx, dz), ...extra });

// North-up: the canvas top is north, right is east.
const north = draw([entity(0, -20)], { northUp: true }).markers[0];
check('north-up puts a northern entity above the centre', north.x === CENTRE && north.y < CENTRE,
  `${north.x},${north.y}`);
const east = draw([entity(20, 0)], { northUp: true }).markers[0];
check('north-up puts an eastern entity right of the centre', east.y === CENTRE && east.x > CENTRE,
  `${east.x},${east.y}`);
const south = draw([entity(0, 20)], { northUp: true }).markers[0];
check('north-up puts a southern entity below the centre', south.x === CENTRE && south.y > CENTRE,
  `${south.x},${south.y}`);
const west = draw([entity(-20, 0)], { northUp: true }).markers[0];
check('north-up puts a western entity left of the centre', west.y === CENTRE && west.x < CENTRE,
  `${west.x},${west.y}`);

// Follow facing: whatever the player faces is at the top. A Minecraft yaw of 0 is south.
const facings = [
  ['south', 0, [0, 1]],
  ['west', 90, [-1, 0]],
  ['north', 180, [0, -1]],
  ['east', -90, [1, 0]]
];
for (const [name, yaw, [fx, fz]] of facings) {
  const m = draw([entity(fx * 20, fz * 20)], { northUp: false, me: { yaw } }).markers[0];
  check(`facing ${name} (yaw ${yaw}) puts what is ahead at the top`,
    m.x === CENTRE && m.y < CENTRE, `${m.x},${m.y}`);
}

// A drag offset turns the view on top of the facing turn.
const dragged = draw([entity(0, -20)], { northUp: true, dragYaw: 90 }).markers[0];
check('a drag offset rotates the north-up view', Math.abs(dragged.x - CENTRE) > 50,
  `${dragged.x},${dragged.y}`);

// Range filtering and the close-range label rule.
const ranged = draw([entity(0, -200), entity(0, -30)], { northUp: true, range: 64 });
check('an entity beyond the range is not drawn', ranged.markers.length === 1,
  String(ranged.markers.length));
const labelled = draw([entity(0, -10), entity(0, -50)], { northUp: true, range: 64 });
const labels = labelled.texts.filter(t => /^\d+m$/.test(t.t)).map(t => t.t);
check('only a close entity carries a distance label', labels.length === 1 && labels[0] === '10m',
  labels.join(','));

// The facing tail points the way the entity looks. A Minecraft yaw of 180 is north, so the tail
// must go up; drawing it with the raw yaw would point it south and be invisible on a small dot.
for (const [name, yaw, wantUp] of [['north', 180, true], ['south', 0, false]]) {
  const d = draw([entity(0, -10, { kind: 'player', yaw })], { northUp: true });
  const head = d.markers[0];
  // The tail is the last stroke after the marker: moveTo(head) then lineTo(tip).
  const tip = [...d.lines].reverse().find(l => !l.from);
  const up = tip && tip.y < head.y;
  check(`an entity facing ${name} (yaw ${yaw}) draws its tail ${wantUp ? 'up' : 'down'}`,
    up === wantUp, tip ? `${tip.x.toFixed(1)},${tip.y.toFixed(1)} vs head ${head.x},${head.y}` : 'no tail');
}

// The vertical tick appears only when the height difference is worth showing.
const flat = draw([entity(0, -10, { dy: 0 })], { northUp: true });
const tall = draw([entity(0, -10, { dy: 8 })], { northUp: true });
check('a level entity draws no height tick', flat.arcs.length <= tall.arcs.length,
  `${flat.arcs.length} vs ${tall.arcs.length}`);

console.log(`\n${checks.filter(Boolean).length}/${checks.length} checks passed`);
if (checks.some(c => !c)) process.exit(1);
