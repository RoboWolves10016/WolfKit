/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The Field widget: a top-down view of the field.
 *
 * Any pose or list of poses can be added as a layer and drawn as a robot, a ghost, an arrow or
 * a path, optionally with a trail of its recent positions.
 */

import {
  h,
  fitCanvas,
  seriesColor,
  freeSlot,
  cssVar,
  shortName,
  pickTopic,
  acceptTopicDrops,
  popover,
} from '../ui.js';

const FIELD_INCHES = 144;
const TILE_INCHES = 24;
const STYLES = { robot: 'Robot', ghost: 'Ghost', arrow: 'Arrow' };
const drawable = (topic) => topic.kind === 'pose' || topic.kind === 'path';

/** A top-down field view with configurable layers. */
export class Field {
  /**
   * @param {object} context the topic store, robot control and a callback that saves settings
   * @param {object} config the widget's saved settings, modified in place
   */
  constructor(context, config) {
    this.store = context.store;
    this.save = context.save;
    this.config = config;
    config.layers = config.layers || [];
    config.rotation = config.rotation === undefined ? 90 : config.rotation;
    config.units = config.units || 'pedro';
    config.robotSize = config.robotSize || 18;
    config.trailSeconds = config.trailSeconds || 5;
    this.layerKey = '';
    this.pointer = null;

    this.canvas = h('canvas', { class: 'field-canvas' });
    this.layers = h('div', { class: 'legend field-layers' });
    this.readout = h('div', { class: 'field-readout' });
    this.note = h(
      'div',
      { class: 'empty-note overlay-note' },
      'Drag a pose here from Values, or use Add layer.',
    );

    const addButton = h('button', { class: 'button' }, 'Add layer');
    addButton.onclick = () =>
      pickTopic(addButton, this.store, drawable, (topic) => this.add(topic));
    const settingsButton = h('button', { class: 'button' }, 'View');
    settingsButton.onclick = () => popover(settingsButton, this.settings());

    const plot = h('div', { class: 'field-plot' }, this.canvas, this.note, this.readout);
    this.el = h(
      'div',
      { class: 'widget field' },
      h(
        'div',
        { class: 'toolbar' },
        this.layers,
        h('div', { class: 'toolbar-end' }, settingsButton, addButton),
      ),
      plot,
    );

    acceptTopicDrops(plot, this.store, drawable, (topic) => this.add(topic));
    this.canvas.addEventListener('pointermove', (event) => {
      this.pointer = { x: event.offsetX, y: event.offsetY };
    });
    this.canvas.addEventListener('pointerleave', () => {
      this.pointer = null;
    });
  }

  /** Adds a topic as a layer. */
  add(topic) {
    if (this.config.layers.some((layer) => layer.topic === topic.name)) return;
    const slot = freeSlot(this.config.layers);
    if (slot < 0) return;
    // The first pose is drawn as the robot. Later poses default to ghosts.
    const hasRobot = this.config.layers.some((layer) => layer.style === 'robot');
    this.config.layers.push({
      topic: topic.name,
      slot,
      style: topic.kind === 'path' ? 'path' : hasRobot ? 'ghost' : 'robot',
      trail: false,
    });
    this.save();
  }

  /** Builds the view settings form. */
  settings() {
    const config = this.config;
    const field = (label, control) =>
      h('label', { class: 'form-row' }, h('span', {}, label), control);
    const select = (key, options, parse = String) => {
      const el = h(
        'select',
        {
          class: 'input',
          onchange: () => {
            config[key] = parse(el.value);
            this.save();
          },
        },
        options.map(([value, label]) =>
          h('option', { value, selected: String(config[key]) === String(value) }, label),
        ),
      );
      return el;
    };
    const size = h('input', {
      class: 'input',
      type: 'number',
      min: 6,
      max: 24,
      step: 0.5,
      value: config.robotSize,
      onchange: () => {
        config.robotSize = Math.max(6, Math.min(24, Number(size.value) || 18));
        this.save();
      },
    });
    const trail = h('input', {
      class: 'input',
      type: 'number',
      min: 1,
      max: 120,
      step: 1,
      value: config.trailSeconds,
      onchange: () => {
        config.trailSeconds = Math.max(1, Math.min(120, Number(trail.value) || 5));
        this.save();
      },
    });
    return h(
      'div',
      { class: 'form' },
      field(
        'Rotate view',
        select(
          'rotation',
          [
            [0, '0°'],
            [90, '90°'],
            [180, '180°'],
            [270, '270°'],
          ],
          Number,
        ),
      ),
      field(
        'Cursor position in',
        select('units', [
          ['pedro', 'Pedro coordinates'],
          ['ftc', 'Field-centre coordinates'],
        ]),
      ),
      field('Robot size (in)', size),
      field('Trail length (s)', trail),
    );
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    this.renderLayers();
    const { context: g, width, height } = fitCanvas(this.canvas);
    g.clearRect(0, 0, width, height);
    this.note.hidden = this.config.layers.length > 0;
    const size = Math.min(width, height) - 16;
    if (size < 60) return;

    const scale = size / FIELD_INCHES;
    const cx = width / 2,
      cy = height / 2;
    const angle = (this.config.rotation * Math.PI) / 180;
    const cos = Math.cos(angle),
      sin = Math.sin(angle);
    // Maps field inches (origin at the centre, y up) to screen pixels, applying the view rotation.
    const toScreen = (x, y) => [cx + (x * cos - y * sin) * scale, cy - (x * sin + y * cos) * scale];
    const toField = (px, py) => {
      const rx = (px - cx) / scale,
        ry = -(py - cy) / scale;
      return [rx * cos + ry * sin, -rx * sin + ry * cos];
    };

    this.drawFloor(g, cx, cy, size, scale);
    const now = this.store.now();
    for (const layer of this.config.layers) {
      const topic = this.store.get(layer.topic);
      if (!topic || topic.value === undefined) continue;
      const color = seriesColor(layer.slot);
      if (topic.kind === 'path') this.drawPath(g, topic.value, color, toScreen);
      else {
        if (layer.trail) this.drawTrail(g, topic, now, color, toScreen);
        this.drawPose(g, topic.value, layer.style, color, toScreen, scale, angle);
      }
    }
    this.renderReadout(toField, cx, cy, size);
  }

  drawFloor(g, cx, cy, size, scale) {
    const left = cx - size / 2,
      top = cy - size / 2;
    g.fillStyle = cssVar('--field');
    g.fillRect(left, top, size, size);
    g.strokeStyle = cssVar('--grid');
    g.lineWidth = 1;
    for (let i = 1; i < FIELD_INCHES / TILE_INCHES; i++) {
      const offset = Math.round(i * TILE_INCHES * scale) + 0.5;
      g.beginPath();
      g.moveTo(left + offset, top);
      g.lineTo(left + offset, top + size);
      g.stroke();
      g.beginPath();
      g.moveTo(left, top + offset);
      g.lineTo(left + size, top + offset);
      g.stroke();
    }
    g.strokeStyle = cssVar('--border-strong');
    g.strokeRect(left + 0.5, top + 0.5, size - 1, size - 1);
  }

  drawPath(g, poses, color, toScreen) {
    if (poses.length < 2) return;
    g.strokeStyle = color;
    g.lineWidth = 2;
    g.lineJoin = 'round';
    g.beginPath();
    poses.forEach((pose, index) => {
      const [x, y] = toScreen(pose.x, pose.y);
      if (index === 0) g.moveTo(x, y);
      else g.lineTo(x, y);
    });
    g.stroke();
    const [endX, endY] = toScreen(poses[poses.length - 1].x, poses[poses.length - 1].y);
    g.fillStyle = color;
    g.beginPath();
    g.arc(endX, endY, 4, 0, Math.PI * 2);
    g.fill();
  }

  drawTrail(g, topic, now, color, toScreen) {
    const from = now - this.config.trailSeconds;
    let i = Math.max(0, topic.indexAt(from));
    if (topic.t.length - i < 2) return;
    g.strokeStyle = color;
    g.lineWidth = 2;
    g.lineJoin = 'round';
    g.globalAlpha = 0.45;
    g.beginPath();
    for (let first = true; i < topic.t.length; i++, first = false) {
      const [x, y] = toScreen(topic.v[i].x, topic.v[i].y);
      if (first) g.moveTo(x, y);
      else g.lineTo(x, y);
    }
    g.stroke();
    g.globalAlpha = 1;
  }

  drawPose(g, pose, style, color, toScreen, scale, viewAngle) {
    const [x, y] = toScreen(pose.x, pose.y);
    const half = (this.config.robotSize * scale) / 2;
    g.save();
    g.translate(x, y);
    g.rotate(-(pose.h + viewAngle)); // Negated because the canvas y axis points down.
    g.lineWidth = 2;
    g.strokeStyle = color;
    if (style === 'arrow') {
      g.beginPath();
      g.moveTo(-half, 0);
      g.lineTo(half, 0);
      g.moveTo(half - 6, -5);
      g.lineTo(half, 0);
      g.lineTo(half - 6, 5);
      g.stroke();
    } else {
      const radius = Math.min(4, half / 3);
      g.beginPath();
      g.roundRect(-half, -half, half * 2, half * 2, radius);
      if (style === 'ghost') {
        g.globalAlpha = 0.14;
        g.fillStyle = color;
        g.fill();
        g.globalAlpha = 1;
        g.setLineDash([5, 4]);
        g.stroke();
        g.setLineDash([]);
      } else {
        g.globalAlpha = 0.9;
        g.fillStyle = color;
        g.fill();
        g.globalAlpha = 1;
        g.strokeStyle = cssVar('--surface');
        g.stroke(); // An outline separates overlapping marks.
      }
      // Heading indicator: a line from the centre to the front edge.
      g.strokeStyle = style === 'ghost' ? color : cssVar('--surface');
      g.beginPath();
      g.moveTo(0, 0);
      g.lineTo(half, 0);
      g.stroke();
    }
    g.restore();
  }

  renderReadout(toField, cx, cy, size) {
    const p = this.pointer;
    if (!p || Math.abs(p.x - cx) > size / 2 || Math.abs(p.y - cy) > size / 2) {
      this.readout.textContent = '';
      return;
    }
    let [x, y] = toField(p.x, p.y);
    if (this.config.units === 'pedro') [x, y] = [72 - y, x + 72];
    const frame = this.config.units === 'pedro' ? 'Pedro' : 'Field';
    this.readout.textContent = `${frame}  x ${x.toFixed(1)}  y ${y.toFixed(1)} in`;
  }

  renderLayers() {
    const config = this.config;
    const key =
      config.layers.map((layer) => layer.topic + layer.slot + layer.style + layer.trail).join('|') +
      this.store.generation +
      document.documentElement.dataset.theme;
    if (key === this.layerKey) return;
    this.layerKey = key;
    this.layers.replaceChildren(
      ...config.layers.map((layer) => {
        const topic = this.store.get(layer.topic);
        const isPath = layer.style === 'path' || (topic && topic.kind === 'path');
        const style = h(
          'select',
          {
            class: 'input small',
            'aria-label': 'Style for ' + shortName(layer.topic),
            onchange: () => {
              layer.style = style.value;
              this.save();
            },
          },
          Object.entries(STYLES).map(([value, label]) =>
            h('option', { value, selected: layer.style === value }, label),
          ),
        );
        const trail = h('input', {
          type: 'checkbox',
          checked: layer.trail,
          onchange: () => {
            layer.trail = trail.checked;
            this.save();
          },
        });
        return h(
          'span',
          { class: 'legend-item' },
          h('span', { class: 'swatch', style: `background: ${seriesColor(layer.slot)}` }),
          h('span', { class: 'legend-name' }, shortName(layer.topic)),
          isPath
            ? h('span', { class: 'legend-value' }, 'Path')
            : [style, h('label', { class: 'check' }, trail, 'Trail')],
          h(
            'button',
            {
              class: 'tab-close',
              title: 'Remove layer',
              'aria-label': 'Remove ' + shortName(layer.topic),
              onclick: () => {
                config.layers.splice(config.layers.indexOf(layer), 1);
                this.save();
              },
            },
            '×',
          ),
        );
      }),
    );
  }
}
