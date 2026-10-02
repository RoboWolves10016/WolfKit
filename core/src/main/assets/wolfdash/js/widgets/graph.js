/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The Graph widget: a scrolling time graph of numeric values.
 *
 * All series share one y axis. A value is drawn as held until its next sample, because the
 * robot sends a value only when it changes.
 */

import {
  h,
  fitCanvas,
  seriesColor,
  freeSlot,
  cssVar,
  formatNumber,
  shortName,
  pickTopic,
  acceptTopicDrops,
} from '../ui.js';

const WINDOWS = [5, 10, 30, 60, 120];
const MARGIN = { left: 52, right: 12, top: 8, bottom: 22 };
const graphable = (topic) => topic.kind === 'number';

/** A scrolling time graph of up to eight series. */
export class Graph {
  /**
   * @param {object} context the topic store, robot control and a callback that saves settings
   * @param {object} config the widget's saved settings, modified in place
   */
  constructor(context, config) {
    this.store = context.store;
    this.save = context.save;
    this.config = config;
    config.series = config.series || [];
    config.window = config.window || 10;
    this.pausedAt = null;
    this.hoverX = null;
    this.legendKey = '';

    this.canvas = h('canvas', { class: 'graph-canvas' });
    this.tooltip = h('div', { class: 'tooltip', hidden: true });
    this.legend = h('div', { class: 'legend' });
    this.note = h(
      'div',
      { class: 'empty-note overlay-note' },
      'Drag a number here from Values, or use Add.',
    );

    const addButton = h('button', { class: 'button' }, 'Add');
    addButton.onclick = () =>
      pickTopic(addButton, this.store, graphable, (topic) => this.add(topic));
    this.pauseButton = h('button', { class: 'button', onclick: () => this.togglePause() }, 'Pause');
    const windowSelect = h(
      'select',
      {
        class: 'input',
        'aria-label': 'Time window',
        onchange: () => {
          config.window = Number(windowSelect.value);
          this.save();
        },
      },
      WINDOWS.map((seconds) =>
        h('option', { value: seconds, selected: seconds === config.window }, `${seconds} s`),
      ),
    );

    const plot = h('div', { class: 'graph-plot' }, this.canvas, this.tooltip, this.note);
    this.el = h(
      'div',
      { class: 'widget graph' },
      h(
        'div',
        { class: 'toolbar' },
        this.legend,
        h('div', { class: 'toolbar-end' }, windowSelect, this.pauseButton, addButton),
      ),
      plot,
    );

    acceptTopicDrops(plot, this.store, graphable, (topic) => this.add(topic));
    this.canvas.addEventListener('pointermove', (event) => {
      this.hoverX = event.offsetX;
    });
    this.canvas.addEventListener('pointerleave', () => {
      this.hoverX = null;
    });
  }

  /** Adds a topic as a series. */
  add(topic) {
    if (this.config.series.some((series) => series.topic === topic.name)) return;
    const slot = freeSlot(this.config.series);
    if (slot < 0) return; // All eight colours are in use.
    this.config.series.push({ topic: topic.name, slot });
    this.save();
  }

  togglePause() {
    this.pausedAt = this.pausedAt === null ? this.store.now() : null;
    this.pauseButton.textContent = this.pausedAt === null ? 'Pause' : 'Resume';
    this.pauseButton.classList.toggle('active', this.pausedAt !== null);
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    const { context: g, width, height } = fitCanvas(this.canvas);
    g.clearRect(0, 0, width, height);
    const series = this.config.series.map((entry) => ({
      entry,
      topic: this.store.get(entry.topic),
    }));
    this.note.hidden = series.length > 0;
    this.renderLegend(series);
    if (!series.length || width < 80 || height < 60) return;

    const end = this.pausedAt === null ? this.store.now() : this.pausedAt;
    const start = end - this.config.window;
    const left = MARGIN.left,
      right = width - MARGIN.right,
      top = MARGIN.top,
      bottom = height - MARGIN.bottom;
    const xOf = (time) => left + ((time - start) / (end - start)) * (right - left);

    // Scale to the visible data, including the value each series holds at the start of the window.
    let low = Infinity,
      high = -Infinity;
    for (const { topic } of series) {
      if (!topic) continue;
      for (
        let i = Math.max(0, topic.indexAt(start));
        i < topic.t.length && topic.t[i] <= end;
        i++
      ) {
        const value = topic.v[i];
        if (Number.isFinite(value)) {
          low = Math.min(low, value);
          high = Math.max(high, value);
        }
      }
    }
    if (low === Infinity) {
      low = 0;
      high = 1;
    }
    if (low === high) {
      low -= 0.5;
      high += 0.5;
    }
    const ticks = niceTicks(low, high, Math.max(2, Math.floor((bottom - top) / 44)));
    low = Math.min(low, ticks[0]);
    high = Math.max(high, ticks[ticks.length - 1]);
    const pad = (high - low) * 0.04;
    const yOf = (value) =>
      bottom - ((value - (low - pad)) / (high - low + 2 * pad)) * (bottom - top);

    // Grid and axis labels.
    g.font = '11px ' + cssVar('--font-mono');
    g.textBaseline = 'middle';
    g.lineWidth = 1;
    for (const tick of ticks) {
      const y = Math.round(yOf(tick)) + 0.5;
      g.strokeStyle = cssVar('--grid');
      g.beginPath();
      g.moveTo(left, y);
      g.lineTo(right, y);
      g.stroke();
      g.fillStyle = cssVar('--text-muted');
      g.textAlign = 'right';
      g.fillText(formatNumber(tick), left - 6, y);
    }
    g.textAlign = 'center';
    g.textBaseline = 'top';
    const step =
      this.config.window <= 10
        ? 1
        : this.config.window <= 30
          ? 5
          : this.config.window <= 60
            ? 10
            : 20;
    for (let ago = 0; ago <= this.config.window; ago += step) {
      g.fillText(ago === 0 ? 'now' : `-${ago}s`, xOf(end - ago), bottom + 6);
    }

    g.save();
    g.beginPath();
    g.rect(left, top - 2, right - left, bottom - top + 4);
    g.clip();
    g.lineWidth = 2;
    g.lineJoin = 'round';
    for (const { entry, topic } of series) {
      if (!topic || !topic.t.length) continue;
      g.strokeStyle = seriesColor(entry.slot);
      g.beginPath();
      let i = topic.indexAt(start);
      let moved = false;
      if (i >= 0) {
        g.moveTo(left, yOf(topic.v[i]));
        moved = true;
      }
      for (i = Math.max(0, i + 1); i < topic.t.length && topic.t[i] <= end; i++) {
        const x = xOf(topic.t[i]),
          y = yOf(topic.v[i]);
        if (!moved) {
          g.moveTo(x, y);
          moved = true;
          continue;
        }
        g.lineTo(x, yOf(topic.v[i - 1])); // Hold the previous value until this sample.
        g.lineTo(x, y);
      }
      if (moved) {
        const last = topic.indexAt(end);
        g.lineTo(right, yOf(topic.v[last]));
        g.stroke();
      }
    }
    g.restore();

    this.renderHover(g, series, { left, right, top, bottom, start, end, yOf });
  }

  renderHover(g, series, frame) {
    const { left, right, top, bottom, start, end, yOf } = frame;
    if (this.hoverX === null || this.hoverX < left || this.hoverX > right) {
      this.tooltip.hidden = true;
      return;
    }
    const x = this.hoverX;
    const time = start + ((x - left) / (right - left)) * (end - start);
    g.strokeStyle = cssVar('--text-muted');
    g.lineWidth = 1;
    g.beginPath();
    g.moveTo(x + 0.5, top);
    g.lineTo(x + 0.5, bottom);
    g.stroke();

    const rows = [];
    for (const { entry, topic } of series) {
      const index = topic ? topic.indexAt(time) : -1;
      if (index < 0) continue;
      const value = topic.v[index];
      g.fillStyle = seriesColor(entry.slot);
      g.strokeStyle = cssVar('--surface');
      g.lineWidth = 2;
      g.beginPath();
      g.arc(x, yOf(value), 4, 0, Math.PI * 2);
      g.fill();
      g.stroke();
      rows.push(
        h(
          'div',
          { class: 'tooltip-row' },
          h('span', { class: 'swatch', style: `background: ${seriesColor(entry.slot)}` }),
          h('span', { class: 'tooltip-name' }, shortName(entry.topic)),
          h(
            'span',
            { class: 'tooltip-value' },
            topic.type === 'boolean' ? String(value === 1) : formatNumber(value),
          ),
        ),
      );
    }
    this.tooltip.replaceChildren(
      h('div', { class: 'tooltip-title' }, `${(end - time).toFixed(2)} s ago`),
      ...rows,
    );
    this.tooltip.hidden = false;
    const flip = x > (left + right) / 2;
    this.tooltip.style.left = flip ? '' : `${x + 12}px`;
    this.tooltip.style.right = flip ? `${this.canvas.clientWidth - x + 12}px` : '';
    this.tooltip.style.top = `${top + 4}px`;
  }

  renderLegend(series) {
    // The legend is rebuilt only when the set of series changes. Values are updated in place.
    const key =
      series.map(({ entry }) => entry.topic + entry.slot).join('|') +
      document.documentElement.dataset.theme;
    if (key !== this.legendKey) {
      this.legendKey = key;
      this.legendValues = [];
      this.legend.replaceChildren(
        ...series.map(({ entry }) => {
          const value = h('span', { class: 'legend-value' });
          this.legendValues.push({ name: entry.topic, value });
          return h(
            'span',
            { class: 'legend-item' },
            h('span', { class: 'swatch', style: `background: ${seriesColor(entry.slot)}` }),
            h('span', { class: 'legend-name' }, shortName(entry.topic)),
            value,
            h(
              'button',
              {
                class: 'tab-close',
                title: 'Remove from graph',
                'aria-label': 'Remove ' + shortName(entry.topic),
                onclick: () => {
                  this.config.series.splice(this.config.series.indexOf(entry), 1);
                  this.save();
                },
              },
              '×',
            ),
          );
        }),
      );
    }
    for (const { name, value } of this.legendValues) {
      const topic = this.store.get(name);
      const text =
        !topic || topic.value === undefined
          ? 'no data'
          : topic.type === 'boolean'
            ? String(topic.value)
            : formatNumber(topic.value);
      if (value.textContent !== text) value.textContent = text;
    }
  }
}

/**
 * @param {number} low the lowest value to cover
 * @param {number} high the highest value to cover
 * @param {number} count the approximate number of intervals
 * @returns {number[]} tick values at a round step covering the range
 */
function niceTicks(low, high, count) {
  const raw = (high - low) / count;
  const magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 5, 10].map((m) => m * magnitude).find((s) => s >= raw);
  const ticks = [];
  for (let tick = Math.floor(low / step) * step; tick <= high + step * 0.5; tick += step) {
    ticks.push(Math.abs(tick) < step * 1e-9 ? 0 : tick);
  }
  return ticks;
}
