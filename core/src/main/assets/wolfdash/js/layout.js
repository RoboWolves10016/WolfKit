/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The split-pane layout.
 *
 * A layout is a tree. Inner nodes are resizable splits; leaves are panes that hold widgets as
 * tabs. A tab can be dragged onto another pane to move it, or onto the edge of a pane to split
 * that pane.
 *
 *   split: { type: 'split', dir: 'row' | 'col', sizes: [fractions], children: [nodes] }
 *   pane:  { type: 'pane', active: index, tabs: [{ id, widget, config }] }
 */

import { h, popover, closePopover } from './ui.js';

const MIN_FRACTION = 0.08;
let nextId = Date.now();

/**
 * Creates a tab.
 *
 * @param {string} widget the widget type, a key of the widget registry
 * @param {object} [config] the widget's saved settings
 * @returns {{id: string, widget: string, config: object}}
 */
export function newTab(widget, config = {}) {
  return { id: 't' + (nextId++).toString(36), widget, config };
}

/** Creates a pane node holding the given tabs. */
export function pane(...tabs) {
  return { type: 'pane', active: 0, tabs };
}

/**
 * Creates a split node.
 *
 * @param {'row'|'col'} dir the direction in which the children are laid out
 * @param {number[]} sizes the relative size of each child
 * @param {...object} children the child nodes
 */
export function split(dir, sizes, ...children) {
  return { type: 'split', dir, sizes, children };
}

/** Renders a layout tree and applies the user's changes to it. */
export class Layout {
  /**
   * @param {HTMLElement} root the element to render into
   * @param {object} widgets the widget registry: { type: { title, create(context, config) } }
   * @param {object} context passed to every widget on creation
   * @param {function(): void} changed called after any change that should be saved
   */
  constructor(root, widgets, context, changed) {
    this.root = root;
    this.widgets = widgets;
    this.context = context;
    this.changed = changed;
    this.tree = pane();
    this.instances = new Map(); // Widget instances by tab id.
    this.dragging = null; // The tab being dragged, as { pane, index }.
  }

  /** Replaces the layout with a copy of a tree. */
  load(tree) {
    this.tree = JSON.parse(JSON.stringify(tree));
    this.render();
  }

  /** Updates every visible widget. Called once per animation frame. */
  update() {
    for (const node of this.panes(this.tree)) {
      const tab = node.tabs[node.active];
      const instance = tab && this.instances.get(tab.id);
      if (instance && instance.update) instance.update();
    }
  }

  /** Yields every pane under a node. */
  *panes(node) {
    if (node.type === 'pane') yield node;
    else for (const child of node.children) yield* this.panes(child);
  }

  /** Rebuilds the DOM from the tree, reusing widget instances and destroying those removed. */
  render() {
    this.tree = tidy(this.tree) || pane();
    const live = new Set();
    this.root.replaceChildren(this.build(this.tree, live));
    for (const [id, instance] of this.instances) {
      if (live.has(id)) continue;
      if (instance.destroy) instance.destroy();
      this.instances.delete(id);
    }
  }

  /** Renders and reports a structural change. */
  commit() {
    this.render();
    this.changed();
  }

  build(node, live) {
    return node.type === 'pane' ? this.buildPane(node, live) : this.buildSplit(node, live);
  }

  buildSplit(node, live) {
    const el = h('div', { class: 'split ' + node.dir });
    node.children.forEach((child, index) => {
      if (index > 0) el.append(this.buildDivider(node, index, el));
      const cell = h(
        'div',
        { class: 'split-cell', style: `flex: ${node.sizes[index]} 1 0` },
        this.build(child, live),
      );
      el.append(cell);
    });
    return el;
  }

  buildDivider(node, index, container) {
    const divider = h('div', { class: 'divider' });
    divider.addEventListener('pointerdown', (down) => {
      down.preventDefault();
      divider.setPointerCapture(down.pointerId);
      const horizontal = node.dir === 'row';
      const total = horizontal ? container.clientWidth : container.clientHeight;
      const start = horizontal ? down.clientX : down.clientY;
      const before = node.sizes[index - 1],
        after = node.sizes[index];
      const sum = node.sizes.reduce((a, b) => a + b, 0);
      const move = (event) => {
        const delta = (((horizontal ? event.clientX : event.clientY) - start) / total) * sum;
        const clamped = Math.max(
          MIN_FRACTION * sum - before,
          Math.min(after - MIN_FRACTION * sum, delta),
        );
        node.sizes[index - 1] = before + clamped;
        node.sizes[index] = after - clamped;
        const cells = [...container.children].filter((child) =>
          child.classList.contains('split-cell'),
        );
        cells[index - 1].style.flex = `${node.sizes[index - 1]} 1 0`;
        cells[index].style.flex = `${node.sizes[index]} 1 0`;
      };
      const up = () => {
        divider.removeEventListener('pointermove', move);
        divider.removeEventListener('pointerup', up);
        this.changed();
      };
      divider.addEventListener('pointermove', move);
      divider.addEventListener('pointerup', up);
    });
    return divider;
  }

  buildPane(node, live) {
    node.active = Math.max(0, Math.min(node.active, node.tabs.length - 1));
    const tabs = node.tabs.map((tab, index) => {
      const name = (this.widgets[tab.widget] || { title: tab.widget }).title;
      const el = h(
        'div',
        {
          class: 'tab' + (index === node.active ? ' active' : ''),
          draggable: true,
          onclick: () => {
            node.active = index;
            this.commit();
          },
          ondragstart: (event) => {
            this.dragging = { pane: node, index };
            event.dataTransfer.setData('application/x-wolf-tab', tab.id);
            event.dataTransfer.effectAllowed = 'move';
            document.body.classList.add('dragging-tab');
          },
          ondragend: () => {
            this.dragging = null;
            document.body.classList.remove('dragging-tab');
          },
        },
        h('span', { class: 'tab-name' }, name),
        h(
          'button',
          {
            class: 'tab-close',
            title: 'Close ' + name,
            'aria-label': 'Close ' + name,
            onclick: (event) => {
              event.stopPropagation();
              node.tabs.splice(index, 1);
              this.commit();
            },
          },
          '×',
        ),
      );
      return el;
    });

    const add = h(
      'button',
      { class: 'icon-button', title: 'Add a widget to this pane', 'aria-label': 'Add widget' },
      '+',
    );
    add.onclick = () =>
      popover(
        add,
        h(
          'div',
          { class: 'menu' },
          Object.entries(this.widgets).map(([key, widget]) =>
            h(
              'button',
              {
                class: 'menu-item',
                onclick: () => {
                  closePopover();
                  node.tabs.push(newTab(key));
                  node.active = node.tabs.length - 1;
                  this.commit();
                },
              },
              widget.title,
            ),
          ),
        ),
      );

    const body = h('div', { class: 'pane-body' });
    const tab = node.tabs[node.active];
    if (tab) {
      live.add(tab.id);
      let instance = this.instances.get(tab.id);
      if (!instance) {
        const spec = this.widgets[tab.widget];
        instance = spec
          ? spec.create({ ...this.context, save: () => this.changed() }, tab.config)
          : { el: h('div', { class: 'empty-note' }, `Unknown widget "${tab.widget}"`) };
        this.instances.set(tab.id, instance);
      }
      body.append(instance.el);
    } else {
      body.append(h('div', { class: 'empty-note' }, 'Empty pane. Use + to add a widget.'));
    }
    // A hidden tab keeps its widget instance until the tab is closed.
    for (const other of node.tabs) if (this.instances.has(other.id)) live.add(other.id);

    const dropZone = h('div', { class: 'drop-zone' });
    const head = h('div', { class: 'pane-head' }, h('div', { class: 'tabs' }, tabs), add);
    const el = h('div', { class: 'pane' }, head, body, dropZone);
    this.acceptTabs(el, head, body, dropZone, node);
    return el;
  }

  /**
   * Makes a pane a drop target for tabs. Dropping on the tab strip or the middle adds the tab
   * to the pane; dropping near an edge of the body splits the pane.
   */
  acceptTabs(el, head, body, dropZone, node) {
    const zoneAt = (event) => {
      if (event.clientY <= head.getBoundingClientRect().bottom) return 'center';
      const box = body.getBoundingClientRect();
      const x = (event.clientX - box.left) / box.width,
        y = (event.clientY - box.top) / box.height;
      if (x < 0.2) return 'left';
      if (x > 0.8) return 'right';
      if (y < 0.2) return 'top';
      if (y > 0.8) return 'bottom';
      return 'center';
    };
    el.addEventListener('dragover', (event) => {
      if (!this.dragging) return;
      event.preventDefault();
      dropZone.dataset.zone = zoneAt(event);
    });
    el.addEventListener('dragleave', (event) => {
      if (!el.contains(event.relatedTarget)) delete dropZone.dataset.zone;
    });
    el.addEventListener('drop', (event) => {
      if (!this.dragging) return;
      event.preventDefault();
      const zone = zoneAt(event);
      delete dropZone.dataset.zone;
      const from = this.dragging;
      this.dragging = null;
      document.body.classList.remove('dragging-tab');
      if (from.pane === node && (zone === 'center' || node.tabs.length === 1)) return;
      const [tab] = from.pane.tabs.splice(from.index, 1);
      if (zone === 'center') {
        node.tabs.push(tab);
        node.active = node.tabs.length - 1;
      } else {
        const fresh = pane(tab);
        const first = zone === 'left' || zone === 'top';
        const replacement = split(
          zone === 'left' || zone === 'right' ? 'row' : 'col',
          [1, 1],
          ...(first ? [fresh, { ...node }] : [{ ...node }, fresh]),
        );
        // The pane node becomes the split in place, so its parent is unchanged.
        for (const key of Object.keys(node)) delete node[key];
        Object.assign(node, replacement);
      }
      this.commit();
    });
  }
}

/**
 * Normalises a tree: removes empty panes, replaces single-child splits with the child, and
 * merges a split into a parent of the same direction.
 *
 * @returns {object|null} the normalised node, or null if it is empty
 */
function tidy(node) {
  if (node.type === 'pane') return node.tabs.length ? node : null;
  const children = [],
    sizes = [];
  node.children.forEach((child, index) => {
    const kept = tidy(child);
    if (!kept) return;
    if (kept.type === 'split' && kept.dir === node.dir) {
      const total = kept.sizes.reduce((a, b) => a + b, 0);
      kept.children.forEach((inner, i) => {
        children.push(inner);
        sizes.push((node.sizes[index] * kept.sizes[i]) / total);
      });
    } else {
      children.push(kept);
      sizes.push(node.sizes[index]);
    }
  });
  if (children.length === 0) return null;
  if (children.length === 1) return children[0];
  node.children = children;
  node.sizes = sizes;
  return node;
}
