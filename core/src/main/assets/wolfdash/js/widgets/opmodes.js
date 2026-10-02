/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The OpModes widget: selects an OpMode and inits, starts or stops it.
 */

import { h, api } from '../ui.js';

const KIND_LABELS = { AUTONOMOUS: 'Autonomous', TELEOP: 'TeleOp' };

/** OpMode selection and control. */
export class OpModes {
  /**
   * @param {object} context the topic store, robot control and a callback that saves settings
   * @param {object} config the widget's saved settings, modified in place
   */
  constructor(context, config) {
    this.store = context.store;
    this.robot = context.robot;
    this.save = context.save;
    this.config = config;
    this.shown = '';

    this.select = h('select', {
      class: 'input opmode-select',
      'aria-label': 'OpMode',
      onchange: () => {
        config.selected = this.select.value;
        this.save();
      },
    });
    this.initButton = h(
      'button',
      { class: 'button', onclick: () => this.robot.init(this.select.value) },
      'Init',
    );
    this.startButton = h(
      'button',
      { class: 'button primary', onclick: () => this.robot.start() },
      'Start',
    );
    this.stopButton = h(
      'button',
      { class: 'button danger', onclick: () => this.robot.stop() },
      'Stop',
    );
    this.status = h('div', { class: 'opmode-status' });
    this.error = h('div', { class: 'error-note', hidden: true });
    const refresh = h('button', { class: 'button', onclick: () => this.load() }, 'Refresh list');

    this.el = h(
      'div',
      { class: 'widget opmodes' },
      h(
        'div',
        { class: 'opmode-controls' },
        this.select,
        h(
          'div',
          { class: 'button-row' },
          this.initButton,
          this.startButton,
          this.stopButton,
          refresh,
        ),
      ),
      this.status,
      this.error,
    );
    this.load();
  }

  /** Loads the list of OpModes from the robot. */
  async load() {
    try {
      const opModes = await (await api('opmodes')).json();
      const groups = new Map();
      for (const opMode of opModes) {
        const label = KIND_LABELS[opMode.kind] || opMode.kind;
        if (!groups.has(label)) groups.set(label, []);
        groups.get(label).push(opMode);
      }
      this.select.replaceChildren(
        ...[...groups].map(([label, list]) =>
          h(
            'optgroup',
            { label },
            list
              .sort((a, b) => a.name.localeCompare(b.name))
              .map((opMode) =>
                h(
                  'option',
                  { value: opMode.name, selected: opMode.name === this.config.selected },
                  opMode.name,
                ),
              ),
          ),
        ),
      );
      this.error.hidden = true;
    } catch (error) {
      this.error.textContent = 'Could not load the OpMode list: ' + error.message;
      this.error.hidden = false;
    }
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    const { name, status } = this.robot.state();
    const key = name + status + this.store.connected + this.select.value;
    if (key === this.shown) return;
    this.shown = key;
    const idle = status === 'STOPPED';
    // Reflect the active OpMode, which may have been started from the Driver Station.
    if (!idle && [...this.select.options].some((option) => option.value === name))
      this.select.value = name;
    this.initButton.disabled = !this.store.connected || !idle || !this.select.value;
    this.startButton.disabled = !this.store.connected || status !== 'INIT';
    this.stopButton.disabled = !this.store.connected || idle;
    this.select.disabled = !idle;
    this.status.textContent = !this.store.connected
      ? 'Not connected to the robot.'
      : idle
        ? 'No OpMode running.'
        : status === 'INIT'
          ? `${name} is initialised. Start it when the robot is clear.`
          : `${name} is running.`;
  }
}
