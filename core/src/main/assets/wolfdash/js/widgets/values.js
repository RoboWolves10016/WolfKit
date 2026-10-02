/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The Values widget: every logged value, grouped by the slashes in its key. A row can be
 * dragged onto a Graph or Field widget.
 */

import { h, formatValue, TOPIC_DRAG_TYPE } from '../ui.js';

const REFRESH_MS = 100;

/** A searchable tree of all logged values. */
export class Values {
  /**
   * @param {object} context the topic store, robot control and a callback that saves settings
   * @param {object} config the widget's saved settings, modified in place
   */
  constructor(context, config) {
    this.store = context.store;
    this.save = context.save;
    this.config = config;
    config.collapsed = config.collapsed || [];
    this.generation = -1;
    this.lastRefresh = 0;
    this.cells = [];

    this.search = h('input', {
      class: 'input',
      type: 'search',
      placeholder: 'Search values',
      'aria-label': 'Search values',
      oninput: () => this.rebuild(),
    });
    this.list = h('div', { class: 'value-list' });
    this.el = h(
      'div',
      { class: 'widget values' },
      h('div', { class: 'toolbar' }, this.search),
      this.list,
    );
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    if (this.generation !== this.store.generation) this.rebuild();
    const now = performance.now();
    if (now - this.lastRefresh < REFRESH_MS) return;
    this.lastRefresh = now;
    for (const { topic, cell } of this.cells) {
      const text = formatValue(topic);
      if (cell.textContent !== text) cell.textContent = text;
    }
  }

  /** Rebuilds the tree from the current topics and search text. */
  rebuild() {
    this.generation = this.store.generation;
    this.cells = [];
    const query = this.search.value.trim().toLowerCase();
    const topics = [...this.store.topics.values()]
      .filter(
        (topic) => !topic.name.startsWith('/.schema/') && topic.name.toLowerCase().includes(query),
      )
      .sort((a, b) => a.name.localeCompare(b.name));

    const root = { groups: new Map(), topics: [] };
    for (const topic of topics) {
      const parts = topic.name.replace(/^\//, '').split('/');
      let group = root;
      for (const part of parts.slice(0, -1)) {
        if (!group.groups.has(part)) group.groups.set(part, { groups: new Map(), topics: [] });
        group = group.groups.get(part);
      }
      group.topics.push({ topic, label: parts[parts.length - 1] });
    }

    this.list.replaceChildren(...this.rows(root, '', 0, query !== ''));
    if (!topics.length) {
      this.list.append(
        h(
          'div',
          { class: 'empty-note' },
          this.store.topics.size
            ? 'Nothing matches that search.'
            : 'No values yet. They appear once robot code calls WolfKit.log.',
        ),
      );
    }
  }

  rows(group, path, depth, forceOpen) {
    const out = [];
    for (const [name, child] of group.groups) {
      const childPath = path + '/' + name;
      const collapsed = !forceOpen && this.config.collapsed.includes(childPath);
      out.push(
        h(
          'button',
          {
            class: 'value-group',
            style: `padding-left: ${8 + depth * 14}px`,
            'aria-expanded': String(!collapsed),
            onclick: () => {
              const list = this.config.collapsed;
              if (collapsed) list.splice(list.indexOf(childPath), 1);
              else list.push(childPath);
              this.save();
              this.rebuild();
            },
          },
          h('span', { class: 'caret' }, collapsed ? '▸' : '▾'),
          name,
        ),
      );
      if (!collapsed) out.push(...this.rows(child, childPath, depth + 1, forceOpen));
    }
    for (const { topic, label } of group.topics) {
      const cell = h('span', { class: 'value-cell' }, formatValue(topic));
      this.cells.push({ topic, cell });
      out.push(
        h(
          'div',
          {
            class: 'value-row',
            style: `padding-left: ${8 + depth * 14 + 14}px`,
            draggable: true,
            title: 'Drag onto a graph or the field',
            ondragstart: (event) => {
              event.dataTransfer.setData(TOPIC_DRAG_TYPE, topic.name);
              event.dataTransfer.effectAllowed = 'copy';
            },
          },
          h('span', { class: 'value-name' }, label),
          cell,
        ),
      );
    }
    return out;
  }
}
