/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The Logs widget: the log files recorded on the robot, newest first, for download.
 */

import { h, api } from '../ui.js';

/** A list of recorded log files. */
export class Logs {
  /** @param {object} context the topic store and robot control */
  constructor(context) {
    this.robot = context.robot;
    this.lastStatus = '';
    this.table = h('div', { class: 'log-list' });
    this.el = h(
      'div',
      { class: 'widget logs' },
      h(
        'div',
        { class: 'toolbar' },
        h(
          'span',
          { class: 'toolbar-label' },
          'One file per OpMode run. Open them in AdvantageScope.',
        ),
        h(
          'div',
          { class: 'toolbar-end' },
          h('button', { class: 'button', onclick: () => this.load() }, 'Refresh'),
        ),
      ),
      this.table,
    );
    this.load();
  }

  /** Loads the list of log files from the robot. */
  async load() {
    try {
      const logs = await (await api('logs')).json();
      this.table.replaceChildren(
        ...logs.map((log) =>
          h(
            'a',
            {
              class: 'log-row',
              href: 'api/log?name=' + encodeURIComponent(log.name),
              download: log.name,
            },
            h('span', { class: 'log-name' }, log.name.replace(/\.wpilog$/, '')),
            h(
              'span',
              { class: 'log-meta' },
              new Date(log.modified).toLocaleString([], {
                dateStyle: 'medium',
                timeStyle: 'short',
              }),
            ),
            h('span', { class: 'log-meta' }, size(log.bytes)),
          ),
        ),
      );
      if (!logs.length)
        this.table.append(
          h('div', { class: 'empty-note' }, 'No logs yet. Run an OpMode to record one.'),
        );
    } catch (error) {
      this.table.replaceChildren(
        h('div', { class: 'error-note' }, 'Could not load the log list: ' + error.message),
      );
    }
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    // Reload when a run ends, since it has produced a new file.
    const { status } = this.robot.state();
    if (status !== this.lastStatus) {
      if (status === 'STOPPED' && this.lastStatus) setTimeout(() => this.load(), 500);
      this.lastStatus = status;
    }
  }
}

function size(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' kB';
  return (bytes / 1024 / 1024).toFixed(1) + ' MB';
}
