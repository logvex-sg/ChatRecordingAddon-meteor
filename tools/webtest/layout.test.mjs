/* Guards the shell layout against the defects that were fixed by hand.
 *
 * Each check below describes something that was actually wrong in the shipped app: a navy card
 * on a crimson theme, a rail with no way back, a phone header that ate half the screen, labels
 * that failed contrast. They are static checks on app.html rather than a browser run because the
 * regression is always a rule that was deleted or overridden again, and a stylesheet assertion
 * catches that without needing a headless browser in CI.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, '../../src/main/resources/companion/app.html'), 'utf8');

const checks = [];
const check = (name, cond, detail = '') => {
  checks.push(!!cond);
  console.log((cond ? 'PASS  ' : 'FAIL  ') + name + (detail ? '  [' + detail + ']' : ''));
};

/* Relative luminance and WCAG contrast, matching the browser's definition so the numbers here
   are the numbers an audit tool would report. */
const luminance = (hex) => {
  const parts = hex.replace('#', '').match(/../g).map(h => {
    const v = parseInt(h, 16) / 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2];
};
const contrast = (a, b) => {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

// ---- colour ------------------------------------------------------------------------------

// The card used to be a hardcoded navy gradient, which looked like a different product on every
// theme except the one it was written for.
check('the card surface is a theme token, not a fixed colour',
  /background:\s*linear-gradient\(180deg, var\(--card-top\), var\(--card-bottom\)\)/.test(html));
check('no navy surface survives anywhere in the stylesheet',
  !/24,\s*35,\s*61/.test(html) && !/18,\s*27,\s*48/.test(html));
check('the log dividers use the themed line colour, not a navy one',
  !/37,\s*50,\s*82/.test(html));

// Canvas painting cannot read CSS variables, so the map and radar used to hardcode their
// background and accent and ignore the theme entirely.
check('the canvases take their background from the theme',
  !/#0a1020/i.test(html) && /themeColor\("--bg"/.test(html));
check('the radar takes its accent from the theme',
  !/strokeStyle\s*=\s*"#ff3b5c"/.test(html) && /themeColor\("--accent"/.test(html));

// Every theme needs a readable faint tone. It was 3.1:1 against the panel, below the 4.5:1 that
// body-sized text needs, and it is used for real labels.
const themes = [...html.matchAll(/^ {2}(\w+): \{\n {4}label: "[^"]+",\n {4}vars: \{([\s\S]*?)\n {4}\}/gm)]
  .map(m => ({ name: m[1], vars: m[2] }));
check('every theme declares its colours', themes.length === 5, themes.map(t => t.name).join(', '));
for (const theme of themes) {
  const grab = (name) => {
    const m = theme.vars.match(new RegExp('"' + name + '": "([^"]+)"'));
    return m ? m[1] : null;
  };
  const faint = grab('--faint');
  const surfaces = ['--panel', '--panel-2', '--bg'].map(grab).filter(Boolean);
  check(theme.name + ' declares a faint tone', !!faint);
  if (!faint) continue;
  const worst = Math.min(...surfaces.map(s => contrast(faint, s)));
  check(theme.name + ' faint text is readable on every surface', worst >= 4.5,
    'worst ' + worst.toFixed(2) + ':1');
  check(theme.name + ' declares card surface tokens',
    /"--card-top"/.test(theme.vars) && /"--card-bottom"/.test(theme.vars));
}

// ---- structure ---------------------------------------------------------------------------

// Hiding the rail used to remove the asides element that contains the only "Show rail" button,
// and the choice was persisted, so the app reopened with no navigation at all.
// The rule has to live in the base stylesheet: a mobile-only rule would leave the desktop rail
// just as inescapable as before.
const baseCss = html.slice(0, html.indexOf('@media'));
check('a control exists outside the rail to bring the rail back',
  /id="btn-rail-reveal"/.test(html)
  && /\.shell\.rail-hidden \.rail-reveal \{ display: flex; \}/.test(baseCss));
check('the reveal control is wired to the rail state',
  /btn-rail-reveal"\)\.onclick = \(\) => setRail\(false\)/.test(html));

// The rail foot (Appearance / Clients / Hide rail) slid below the fold on a short window because
// the whole rail scrolled. Now the list scrolls and the foot stays pinned.
check('the rail list is the scrolling region', /flex: 1 1 auto; min-height: 0; overflow-y: auto/.test(html));
const footRule = html.match(/aside \.rail-foot \{([^}]*)\}/);
check('the rail foot is pinned rather than squeezed',
  footRule && /flex: 0 0 auto;/.test(footRule[1]), footRule ? footRule[1].trim() : 'rule missing');
const asideRule = html.match(/\n  aside \{([\s\S]*?)\n  \}/);
check('the rail itself does not scroll, so the foot stays put',
  asideRule && /overflow: hidden;/.test(asideRule[1]));

// A nowrap flex strip inside a grid item overflows the page unless the item is allowed to be
// narrower than its content. The base rail is a column, so only the mobile rule needs this.
const mobileCss = html.slice(html.indexOf('@media (max-width: 860px)'));
const mobileNav = mobileCss.match(/\n {4}nav \{([\s\S]*?)\n {4}\}/);
check('the mobile tab strip can shrink instead of widening the page',
  mobileNav && /min-width: 0;/.test(mobileNav[1]),
  mobileNav ? 'nav rule found' : 'mobile nav rule missing');
// Two auto grid rows shared the leftover viewport height, leaving a dead band under the header.
check('the one-column shell does not stretch its rows', /align-content: start;/.test(html));

// ---- interaction -------------------------------------------------------------------------

// Escape did nothing in a dialog; the only exit was the Close button.
check('Escape closes the open dialog', /querySelector\("dialog\[open\]"\)[\s\S]{0,80}\.close\(\)/.test(html));

const failed = checks.filter(c => !c).length;
console.log('\n' + (checks.length - failed) + '/' + checks.length + ' checks passed');
process.exit(failed === 0 ? 0 : 1);
