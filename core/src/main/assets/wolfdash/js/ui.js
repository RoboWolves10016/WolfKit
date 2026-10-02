/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file Shared utilities: element creation, popovers, the topic picker, value formatting and
 * chart colours.
 */

/**
 * Creates an element.
 *
 * @param {string} tag the tag name
 * @param {object} [props] properties and attributes; keys starting with "on" add listeners
 * @param {...(Node|string|Array)} children child nodes or text; null and false are skipped
 * @returns {HTMLElement}
 * @example h('div', { class: 'row', onclick: handler }, h('span', {}, 'Name'), value)
 */
export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else if (key === 'class') el.className = value;
    else if (key === 'style') el.style.cssText = value;
    else if (key in el && key !== 'list') el[key] = value;
    else el.setAttribute(key, value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child.nodeType ? child : document.createTextNode(String(child)));
  }
  return el;
}

// Categorical colours for graph series and field layers. The order is fixed and keeps adjacent
// colours distinguishable under colour-vision deficiency. An item keeps its slot once assigned.
const SERIES = {
  light: ['#2a78d6', '#eb6834', '#1baf7a', '#eda100', '#e87ba4', '#008300', '#4a3aa7', '#e34948'],
  dark: ['#3987e5', '#d95926', '#199e70', '#c98500', '#d55181', '#008300', '#9085e9', '#e66767'],
};
export const SLOT_COUNT = 8;

/** @returns {boolean} whether the dark theme is in effect */
export function isDark() {
  const theme = document.documentElement.dataset.theme;
  if (theme) return theme === 'dark';
  return matchMedia('(prefers-color-scheme: dark)').matches;
}

/** @returns {string} the colour of a slot in the current theme */
export function seriesColor(slot) {
  return SERIES[isDark() ? 'dark' : 'light'][slot % SLOT_COUNT];
}

/**
 * @param {Array<{slot: number}>} items the items that already hold slots
 * @returns {number} the lowest unused slot, or -1 if all are in use
 */
export function freeSlot(items) {
  for (let slot = 0; slot < SLOT_COUNT; slot++) {
    if (!items.some((item) => item.slot === slot)) return slot;
  }
  return -1;
}

/** @returns {string} the value of a CSS custom property on the document root */
export function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}

/** Formats a number for display with a precision suited to its magnitude. */
export function formatNumber(value) {
  if (!Number.isFinite(value)) return String(value);
  const abs = Math.abs(value);
  if (abs !== 0 && (abs >= 1e6 || abs < 1e-3)) return value.toExponential(2);
  const digits = abs >= 100 ? 1 : abs >= 1 ? 2 : 4;
  return value.toFixed(digits).replace(/\.?0+$/, '') || '0';
}

/** Formats a topic's latest value for display. */
export function formatValue(topic) {
  const value = topic.value;
  if (value === undefined) return '';
  switch (topic.kind) {
    case 'number':
      return topic.type === 'boolean' ? String(value) : formatNumber(value);
    case 'pose':
      return `${value.x.toFixed(1)}, ${value.y.toFixed(1)} in  ${degrees(value.h).toFixed(0)}°`;
    case 'pose3d':
      return `${value.x.toFixed(1)}, ${value.y.toFixed(1)}, ${value.z.toFixed(1)} in`;
    case 'path':
      return `${value.length} poses`;
    case 'array':
      return '[' + value.map(formatNumber).join(', ') + ']';
    default:
      return String(value);
  }
}

/** Converts radians to degrees, normalised to the range -180 to 180. */
export function degrees(radians) {
  const turns = radians / (2 * Math.PI);
  return (turns - Math.round(turns)) * 360;
}

/** @returns {string} a topic name without its leading slash */
export function shortName(name) {
  return name.replace(/^\//, '');
}

let openPopover = null;

/** Closes the open popover, if any. */
export function closePopover() {
  if (openPopover) openPopover.remove();
  openPopover = null;
}

/**
 * Shows content in a floating panel anchored to an element. Only one popover is open at a
 * time; clicking outside it or pressing Escape closes it.
 *
 * @param {HTMLElement} anchor the element to position against
 * @param {Node} content the panel's content
 * @returns {HTMLElement} the panel
 */
export function popover(anchor, content) {
  closePopover();
  const panel = h('div', { class: 'popover' }, content);
  document.body.append(panel);
  const box = anchor.getBoundingClientRect();
  const width = panel.offsetWidth,
    height = panel.offsetHeight;
  panel.style.left = Math.max(8, Math.min(box.left, innerWidth - width - 8)) + 'px';
  panel.style.top =
    (box.bottom + height + 8 > innerHeight ? Math.max(8, box.top - height - 4) : box.bottom + 4) +
    'px';
  openPopover = panel;
  return panel;
}

document.addEventListener(
  'pointerdown',
  (event) => {
    if (openPopover && !openPopover.contains(event.target)) closePopover();
  },
  true,
);
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') closePopover();
});

/**
 * Shows a searchable list of topics in a popover.
 *
 * @param {HTMLElement} anchor the element to position against
 * @param {Store} store the topic store
 * @param {function(Topic): boolean} accepts selects the topics to list
 * @param {function(Topic): void} choose called with the topic the user picks
 */
export function pickTopic(anchor, store, accepts, choose) {
  const list = h('div', { class: 'pick-list' });
  const search = h('input', {
    class: 'input',
    type: 'search',
    placeholder: 'Search values',
    oninput: fill,
  });
  function fill() {
    const query = search.value.toLowerCase();
    const matches = [...store.topics.values()]
      .filter(
        (topic) =>
          accepts(topic) &&
          !topic.name.startsWith('/.schema/') &&
          topic.name.toLowerCase().includes(query),
      )
      .sort((a, b) => a.name.localeCompare(b.name));
    list.replaceChildren(
      ...matches.map((topic) =>
        h(
          'button',
          {
            class: 'pick-item',
            onclick: () => {
              closePopover();
              choose(topic);
            },
          },
          shortName(topic.name),
        ),
      ),
    );
    if (!matches.length)
      list.append(
        h(
          'div',
          { class: 'empty-note' },
          'Nothing matches. Values appear once robot code logs them.',
        ),
      );
  }
  fill();
  popover(anchor, h('div', { class: 'picker' }, search, list));
  search.focus();
}

/** The drag-and-drop data type that carries a topic name. */
export const TOPIC_DRAG_TYPE = 'application/x-wolf-topic';

/**
 * Makes an element a drop target for topics dragged from the Values widget.
 *
 * @param {HTMLElement} el the drop target
 * @param {Store} store the topic store
 * @param {function(Topic): boolean} accepts selects the topics that may be dropped
 * @param {function(Topic): void} choose called with a dropped topic
 */
export function acceptTopicDrops(el, store, accepts, choose) {
  el.addEventListener('dragover', (event) => {
    if (!event.dataTransfer.types.includes(TOPIC_DRAG_TYPE)) return;
    event.preventDefault();
    el.classList.add('drop-ready');
  });
  el.addEventListener('dragleave', () => el.classList.remove('drop-ready'));
  el.addEventListener('drop', (event) => {
    el.classList.remove('drop-ready');
    const topic = store.get(event.dataTransfer.getData(TOPIC_DRAG_TYPE));
    if (!topic) return;
    event.preventDefault();
    if (accepts(topic)) choose(topic);
  });
}

/**
 * Sizes a canvas's backing store to its displayed size and the device pixel ratio.
 *
 * @param {HTMLCanvasElement} canvas
 * @returns {{context: CanvasRenderingContext2D, width: number, height: number}} a context
 *   scaled to CSS pixels, and the canvas size in CSS pixels
 */
export function fitCanvas(canvas) {
  const ratio = window.devicePixelRatio || 1;
  const width = canvas.clientWidth,
    height = canvas.clientHeight;
  if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
  }
  const context = canvas.getContext('2d');
  context.setTransform(ratio, 0, 0, ratio, 0, 0);
  return { context, width, height };
}

/**
 * Calls the dashboard API.
 *
 * @param {string} path the path relative to the API root, such as "opmodes"
 * @param {RequestInit} [options] fetch options
 * @returns {Promise<Response>} the response
 * @throws {Error} with the response text if the request fails
 */
export async function api(path, options) {
  const response = await fetch('api/' + path, options);
  if (!response.ok) throw new Error(await response.text());
  return response;
}
