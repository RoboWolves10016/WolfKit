/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file Application entry point: the widget registry, saved layouts, the top bar and the
 * animation loop.
 */

import { Store } from './nt.js';
import { Layout, newTab, pane, split } from './layout.js';
import { h, api, popover, closePopover, formatNumber } from './ui.js';
import { Values } from './widgets/values.js';
import { Graph } from './widgets/graph.js';
import { Field } from './widgets/field.js';
import { Tunables } from './widgets/tunables.js';
import { OpModes } from './widgets/opmodes.js';
import { Logs } from './widgets/logs.js';
import { Telemetry } from './widgets/telemetry.js';

/** The widget registry. Keys are stored in saved layouts. */
const WIDGETS = {
  field: { title: 'Field', create: (context, config) => new Field(context, config) },
  graph: { title: 'Graph', create: (context, config) => new Graph(context, config) },
  values: { title: 'Values', create: (context, config) => new Values(context, config) },
  telemetry: { title: 'Telemetry', create: (context, config) => new Telemetry(context, config) },
  tunables: { title: 'Tunables', create: (context, config) => new Tunables(context, config) },
  opmodes: { title: 'OpModes', create: (context, config) => new OpModes(context, config) },
  logs: { title: 'Logs', create: (context, config) => new Logs(context, config) },
};

/**
 * Returns the built-in layouts, used until layouts have been saved on the robot. A widget
 * that refers to a topic not yet logged shows nothing for it.
 */
function defaultLayouts() {
  return {
    Drive: split(
      'row',
      [1.2, 1],
      pane(
        newTab('field', {
          layers: [
            { topic: '/Drive/Path', slot: 3, style: 'path', trail: false },
            { topic: '/Drive/Odometry', slot: 0, style: 'robot', trail: true },
            { topic: '/Drive/Target', slot: 1, style: 'ghost', trail: false },
            { topic: '/Drive/Vision', slot: 2, style: 'ghost', trail: false },
          ],
        }),
      ),
      split(
        'col',
        [0.8, 1, 1],
        pane(newTab('opmodes'), newTab('logs')),
        pane(newTab('graph', { series: [{ topic: '/Drive/ErrorInches', slot: 0 }], window: 10 })),
        pane(newTab('values'), newTab('telemetry'), newTab('tunables')),
      ),
    ),
    Tuning: split(
      'row',
      [1, 1.6],
      split('col', [1, 1.4], pane(newTab('opmodes')), pane(newTab('tunables'))),
      split(
        'col',
        [1, 1],
        pane(
          newTab('graph', {
            series: [
              { topic: '/Shooter/TargetRPM', slot: 0 },
              { topic: '/Shooter/VelocityRPM', slot: 1 },
            ],
            window: 10,
          }),
        ),
        pane(
          newTab('graph', { series: [{ topic: '/Shooter/Power', slot: 2 }], window: 10 }),
          newTab('values'),
        ),
      ),
    ),
  };
}

const store = new Store();

/** Robot state and control, passed to widgets. */
const robot = {
  state() {
    const name = store.get('/WolfKit/OpMode');
    const status = store.get('/WolfKit/OpModeStatus');
    return { name: name ? name.value : '', status: (status && status.value) || 'STOPPED' };
  },
  init: (name) => command(`opmode?action=init&name=${encodeURIComponent(name)}`),
  start: () => command('opmode?action=start'),
  stop: () => command('opmode?action=stop'),
};

/** Sends a control request, reporting a failure to the user. */
async function command(path) {
  try {
    await api(path, { method: 'POST' });
  } catch (error) {
    flash('The robot did not accept that: ' + error.message);
  }
}

// ---- Layouts ----

let layouts = defaultLayouts();
let current = localStorage.getItem('wolfdash.layout') || 'Drive';
let saveTimer = null;

const layout = new Layout(
  document.getElementById('workspace'),
  WIDGETS,
  { store, robot, session: { tunableOriginals: new Map() } },
  () => {
    layouts[current] = layout.tree;
    clearTimeout(saveTimer);
    saveTimer = setTimeout(saveLayouts, 800);
  },
);

/** Saves all layouts to the robot. */
async function saveLayouts() {
  try {
    await api('layouts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ layouts }),
    });
  } catch (error) {
    flash('Could not save the layout to the robot: ' + error.message);
  }
}

/** Shows a layout by name, falling back to the first layout if it does not exist. */
function show(name) {
  if (!layouts[name]) name = Object.keys(layouts)[0];
  current = name;
  localStorage.setItem('wolfdash.layout', name);
  layout.load(layouts[name]);
  renderLayoutPicker();
}

const layoutSelect = document.getElementById('layout-select');
layoutSelect.onchange = () => show(layoutSelect.value);

function renderLayoutPicker() {
  layoutSelect.replaceChildren(
    ...Object.keys(layouts).map((name) =>
      h('option', { value: name, selected: name === current }, name),
    ),
  );
}

const layoutMenuButton = document.getElementById('layout-menu');
layoutMenuButton.onclick = () => {
  const name = h('input', {
    class: 'input',
    type: 'text',
    placeholder: 'New layout name',
    'aria-label': 'New layout name',
  });
  const create = (tree) => {
    const title = name.value.trim();
    if (!title || layouts[title]) return name.focus();
    layouts[title] = JSON.parse(JSON.stringify(tree));
    closePopover();
    show(title);
    saveLayouts();
  };
  const remove = h(
    'button',
    { class: 'menu-item danger-text', disabled: Object.keys(layouts).length < 2 },
    `Delete "${current}"`,
  );
  remove.onclick = () => {
    if (remove.dataset.armed) {
      delete layouts[current];
      closePopover();
      show(Object.keys(layouts)[0]);
      saveLayouts();
    } else {
      remove.dataset.armed = '1';
      remove.textContent = `Click again to delete "${current}"`;
    }
  };
  const file = h('input', {
    type: 'file',
    accept: 'application/json',
    hidden: true,
    onchange: () => importLayouts(file.files[0]),
  });
  popover(
    layoutMenuButton,
    h(
      'div',
      { class: 'menu' },
      h(
        'div',
        { class: 'form' },
        name,
        h(
          'div',
          { class: 'button-row' },
          h('button', { class: 'button', onclick: () => create(layout.tree) }, 'Save copy as'),
          h(
            'button',
            { class: 'button', onclick: () => create(pane(newTab('values'))) },
            'New empty',
          ),
        ),
      ),
      h('div', { class: 'menu-rule' }),
      h(
        'button',
        {
          class: 'menu-item',
          onclick: () => {
            closePopover();
            exportLayouts();
          },
        },
        'Export layouts to a file',
      ),
      h(
        'button',
        { class: 'menu-item', onclick: () => file.click() },
        'Import layouts from a file',
      ),
      file,
      h(
        'button',
        {
          class: 'menu-item',
          onclick: () => {
            closePopover();
            Object.assign(layouts, defaultLayouts());
            show(current);
            saveLayouts();
          },
        },
        'Restore the built-in layouts',
      ),
      remove,
    ),
  );
};

function exportLayouts() {
  const blob = new Blob([JSON.stringify({ layouts }, null, 2)], { type: 'application/json' });
  const link = h('a', { href: URL.createObjectURL(blob), download: 'wolfdash-layouts.json' });
  link.click();
  URL.revokeObjectURL(link.href);
}

async function importLayouts(file) {
  closePopover();
  try {
    const imported = JSON.parse(await file.text()).layouts;
    if (!imported || typeof imported !== 'object') throw new Error('no layouts in that file');
    Object.assign(layouts, imported);
    show(current);
    saveLayouts();
  } catch (error) {
    flash('Could not import that file: ' + error.message);
  }
}

// ---- Top bar ----

const health = {
  link: document.getElementById('health-link'),
  opMode: document.getElementById('health-opmode'),
  battery: document.getElementById('health-battery'),
  loop: document.getElementById('health-loop'),
};
const stopButton = document.getElementById('stop');
stopButton.onclick = () => robot.stop();

/** Sets the text and status level of a health chip, touching the DOM only on change. */
function setChip(el, text, level) {
  if (el.textContent !== text) el.textContent = text;
  if (el.dataset.level !== level) el.dataset.level = level;
}

function renderHealth() {
  const { name, status } = robot.state();
  setChip(
    health.link,
    store.connected ? 'Connected' : 'Disconnected',
    store.connected ? 'good' : 'critical',
  );
  setChip(
    health.opMode,
    status === 'STOPPED' ? 'No OpMode' : `${name} · ${status === 'INIT' ? 'Init' : 'Running'}`,
    status === 'RUNNING' ? 'good' : status === 'INIT' ? 'warning' : 'idle',
  );

  const battery = store.get('/WolfKit/BatteryVolts');
  const volts = battery && store.connected ? battery.value : undefined;
  setChip(
    health.battery,
    volts === undefined ? 'Battery –' : `Battery ${volts.toFixed(1)} V`,
    volts === undefined ? 'idle' : volts < 11.5 ? 'critical' : volts < 12.2 ? 'warning' : 'idle',
  );

  const loop = store.get('/WolfKit/LoopMs');
  const fresh = loop && store.connected && store.now() - loop.time < 1;
  setChip(
    health.loop,
    fresh ? `Loop ${formatNumber(loop.value)} ms` : 'Loop –',
    fresh && loop.value > 30 ? 'warning' : 'idle',
  );

  stopButton.disabled = !store.connected || status === 'STOPPED';
}

const themeButton = document.getElementById('theme');
themeButton.onclick = () => {
  const dark = document.documentElement.dataset.theme
    ? document.documentElement.dataset.theme === 'dark'
    : matchMedia('(prefers-color-scheme: dark)').matches;
  document.documentElement.dataset.theme = dark ? 'light' : 'dark';
  localStorage.setItem('wolfdash.theme', document.documentElement.dataset.theme);
};

const toast = document.getElementById('toast');
let toastTimer = null;
/** Shows a message to the user for a few seconds. */
function flash(message) {
  toast.textContent = message;
  toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toast.hidden = true;
  }, 5000);
}

// ---- Start-up ----

async function start() {
  try {
    const saved = (await (await api('layouts')).json()).layouts;
    if (saved && Object.keys(saved).length) layouts = saved;
  } catch (error) {
    flash('Could not load saved layouts, so the built-in ones are showing.');
  }
  show(current);

  let lastHealth = 0;
  const frame = (time) => {
    layout.update();
    if (time - lastHealth > 200) {
      lastHealth = time;
      renderHealth();
    }
    requestAnimationFrame(frame);
  };
  requestAnimationFrame(frame);
}

// Exposed for debugging from the browser console.
window.wolfdash = { store, layout, robot };

start();
