/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The Telemetry widget: the text an OpMode sends to the Driver Station.
 */

import { h } from '../ui.js';

/** A view of the Driver Station telemetry text. */
export class Telemetry {
  /** @param {object} context the topic store and robot control */
  constructor(context) {
    this.store = context.store;
    this.text = h('pre', { class: 'telemetry-text' });
    this.note = h(
      'div',
      { class: 'empty-note' },
      'Nothing yet. Lines appear when an OpMode calls telemetry.addData and telemetry.update.',
    );
    this.el = h('div', { class: 'widget telemetry' }, this.note, this.text);
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    const topic = this.store.get('/Telemetry');
    const text = topic && topic.value !== undefined ? topic.value : '';
    this.note.hidden = text !== '';
    if (this.text.textContent !== text) this.text.textContent = text;
  }
}
