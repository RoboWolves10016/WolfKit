/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The Tunables widget: edits the values robot code reads with WolfKit.tunable, and shows
 * the buttons it declares with WolfKit.button.
 */

import { h } from '../ui.js';

const TUNING = '/Tuning/';
const BUTTONS = '/Buttons/';

/** Editors for tunables and buttons. */
export class Tunables {
  /**
   * @param {object} context the topic store, robot control and a callback that saves settings
   * @param {object} config the widget's saved settings, modified in place
   */
  constructor(context, config) {
    this.store = context.store;
    this.generation = -1;
    this.rows = [];
    // The value each tunable had when first seen, used to mark changes.
    this.original = context.session.tunableOriginals;

    this.list = h('div', { class: 'tunable-list' });
    this.buttons = h('div', { class: 'button-row' });
    this.copyButton = h(
      'button',
      { class: 'button', onclick: () => this.copyChanged() },
      'Copy changed',
    );
    this.el = h(
      'div',
      { class: 'widget tunables' },
      h(
        'div',
        { class: 'toolbar' },
        h(
          'span',
          { class: 'toolbar-label' },
          'Changes apply at once and last until the robot app restarts.',
        ),
        h('div', { class: 'toolbar-end' }, this.copyButton),
      ),
      this.buttons,
      this.list,
    );
  }

  /** Refreshes the widget. Called once per animation frame while the widget is visible. */
  update() {
    if (this.generation !== this.store.generation) this.rebuild();
    for (const row of this.rows) row.refresh();
  }

  rebuild() {
    this.generation = this.store.generation;
    const topics = [...this.store.topics.values()].sort((a, b) => a.name.localeCompare(b.name));
    const tunables = topics.filter((topic) => topic.name.startsWith(TUNING));
    const buttons = topics.filter((topic) => topic.name.startsWith(BUTTONS));

    this.buttons.replaceChildren(
      ...buttons.map((topic) =>
        h(
          'button',
          { class: 'button', onclick: () => this.store.publish(topic.name, true) },
          topic.name.slice(BUTTONS.length),
        ),
      ),
    );
    this.buttons.hidden = buttons.length === 0;

    this.rows = [];
    const children = [];
    let lastGroup = null;
    for (const topic of tunables) {
      const key = topic.name.slice(TUNING.length);
      const cut = key.lastIndexOf('/');
      const group = cut < 0 ? '' : key.slice(0, cut);
      if (group !== lastGroup) {
        lastGroup = group;
        if (group) children.push(h('div', { class: 'tunable-group' }, group));
      }
      children.push(this.row(topic, key.slice(cut + 1)));
    }
    if (!tunables.length && !buttons.length) {
      children.push(
        h(
          'div',
          { class: 'empty-note' },
          'No tunables yet. They appear once robot code calls WolfKit.tunable.',
        ),
      );
    }
    this.list.replaceChildren(...children);
  }

  row(topic, label) {
    const original = () => this.original.get(topic.name);
    // Values are shown at full precision so that they can be copied into code exactly.
    const display = (value) => String(value);
    let input;
    if (topic.type === 'boolean') {
      input = h('input', {
        type: 'checkbox',
        class: 'switch',
        onchange: () => this.store.publish(topic.name, input.checked),
      });
    } else {
      input = h('input', {
        class: 'input tunable-input',
        type: 'text',
        inputMode: topic.type === 'double' ? 'decimal' : 'text',
        spellcheck: false,
        onkeydown: (event) => {
          if (event.key === 'Enter') input.blur();
          if (event.key === 'Escape') {
            input.value = display(topic.value);
            input.blur();
          }
        },
        onchange: () => {
          if (topic.type === 'string') return this.store.publish(topic.name, input.value);
          const number = Number(input.value);
          if (input.value.trim() === '' || !Number.isFinite(number))
            input.value = display(topic.value);
          else this.store.publish(topic.name, number);
        },
      });
    }
    const was = h('button', {
      class: 'was',
      title: 'Put back the original value',
      onclick: () => this.store.publish(topic.name, original()),
    });
    input.setAttribute('aria-label', label);
    const el = h(
      'div',
      { class: 'tunable-row' },
      h('span', { class: 'tunable-name' }, label),
      was,
      input,
    );

    this.rows.push({
      topic,
      refresh: () => {
        if (topic.value === undefined) return;
        if (!this.original.has(topic.name)) this.original.set(topic.name, topic.value);
        const changed = topic.value !== original();
        el.classList.toggle('changed', changed);
        was.hidden = !changed;
        if (changed) was.textContent = 'was ' + display(original());
        if (topic.type === 'boolean') input.checked = topic.value;
        else if (document.activeElement !== input && input.value !== display(topic.value))
          input.value = display(topic.value);
      },
    });
    return el;
  }

  /** Copies the changed tunables to the clipboard, one "key = value" per line. */
  copyChanged() {
    const lines = this.rows
      .filter(
        ({ topic }) =>
          this.original.has(topic.name) && topic.value !== this.original.get(topic.name),
      )
      .map(({ topic }) => `${topic.name.slice(TUNING.length)} = ${topic.value}`);
    navigator.clipboard.writeText(lines.join('\n')).then(() => {
      this.copyButton.textContent = lines.length ? `Copied ${lines.length}` : 'Nothing changed';
      setTimeout(() => {
        this.copyButton.textContent = 'Copy changed';
      }, 1500);
    });
  }
}
