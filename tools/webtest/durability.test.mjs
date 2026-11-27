/* Drives the shipped durability helpers out of app.html.
 *
 * A durability bar is only honest if it uses the item's real maximum: damage points alone cannot
 * say how much life is left. These checks pin the arithmetic and the "no maximum, no bar" rule.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, '../../src/main/resources/companion/app.html'), 'utf8');
const script = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map(m => m[1])
  .sort((a, b) => b.length - a.length)[0];

const start = script.indexOf('/* Durability of one stack');
// The durability helpers sit just before the slot translation and the slot renderer. Anchor on
// the handler translation, which must not depend on how a slot is drawn, so restyling the
// inventory does not break this test the way replacing slotCell with mcSlot once did.
const end = script.indexOf('/* Which container slot a player-inventory index is');
if (start < 0 || end < 0) throw new Error('could not locate the durability helpers');

const build = new Function(script.slice(start, end) + '\nreturn { durabilityOf, durabilityColour };');
const { durabilityOf, durabilityColour } = build();

const checks = [];
const check = (name, cond, detail = '') => {
  checks.push(!!cond);
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name + (detail ? '  [' + detail + ']' : ''));
};

// An undamaged pickaxe.
let d = durabilityOf({ itemId: 'minecraft:diamond_pickaxe', damage: 0, maxDamage: 1561 });
check('an undamaged tool has full durability', d && d.remaining === 1561 && d.ratio === 1);

// A nearly broken one.
d = durabilityOf({ itemId: 'minecraft:diamond_pickaxe', damage: 1540, maxDamage: 1561 });
check('a nearly broken tool keeps 21 points', d && d.remaining === 21);
check('a nearly broken tool reads as critical', durabilityColour(d.ratio) === 'var(--bad)');

// Half worn.
d = durabilityOf({ itemId: 'minecraft:iron_pickaxe', damage: 125, maxDamage: 250 });
check('a half worn tool is at 50%', d && Math.abs(d.ratio - 0.5) < 1e-9);
// Exactly half is the boundary: the game shows yellow here, so the bar warns rather than reading
// as healthy when half the tool's life is already gone.
check('exactly 50% warns', durabilityColour(d.ratio) === 'var(--warn)');
check('just above 50% is healthy', durabilityColour(0.51) === 'var(--good)');
check('just above 25% still warns', durabilityColour(0.26) === 'var(--warn)');
check('at 25% it is critical', durabilityColour(0.25) === 'var(--bad)');

// A third worn.
d = durabilityOf({ itemId: 'minecraft:shield', damage: 224, maxDamage: 336 });
check('a third worn tool warns', durabilityColour(d.ratio) === 'var(--warn)');

// Items that do not wear out.
check('an item with no maximum has no bar', durabilityOf({ itemId: 'minecraft:dirt', damage: 0 }) === null);
check('a zero maximum has no bar', durabilityOf({ itemId: 'minecraft:dirt', maxDamage: 0 }) === null);
check('a missing item has no bar', durabilityOf(null) === null);

// A snapshot from an older client: damage but no maximum.
check('damage without a maximum cannot make a bar',
  durabilityOf({ itemId: 'minecraft:iron_pickaxe', damage: 40 }) === null);

// Nonsense values must not produce a negative or over-full bar.
d = durabilityOf({ itemId: 'minecraft:iron_pickaxe', damage: -5, maxDamage: 250 });
check('negative damage is clamped to full', d && d.remaining === 250);
d = durabilityOf({ itemId: 'minecraft:iron_pickaxe', damage: 9999, maxDamage: 250 });
check('damage past the maximum clamps to zero', d && d.remaining === 0);
check('zero remaining reads as critical', durabilityColour(0) === 'var(--bad)');

const failed = checks.filter(c => !c).length;
console.log('\n' + (checks.length - failed) + '/' + checks.length + ' checks passed');
process.exit(failed === 0 ? 0 : 1);
