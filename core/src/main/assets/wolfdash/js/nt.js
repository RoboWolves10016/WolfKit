/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

/**
 * @file The live data client.
 *
 * Connects to WolfKit's NetworkTables 4 server over WebSocket, decodes value updates and keeps
 * a bounded history of each topic for graphs and trails.
 */

const METERS_PER_INCH = 0.0254;
const HISTORY_SECONDS = 125;
const MAX_POINTS = 30000;
const TYPE_INDEX = { boolean: 0, double: 1, string: 4 };

/** A logged key, its latest value and its recent history. */
export class Topic {
  constructor(name, type) {
    this.name = name;
    this.type = type;
    this.value = undefined;
    this.time = 0;
    // History as parallel arrays: sample times in seconds, and the value at each.
    this.t = [];
    this.v = [];
  }

  /** The broad category of the topic's type: number, pose, path, pose3d, array or text. */
  get kind() {
    if (this.type === 'double' || this.type === 'boolean') return 'number';
    if (this.type === 'struct:Pose2d') return 'pose';
    if (this.type === 'struct:Pose2d[]') return 'path';
    if (this.type === 'struct:Pose3d') return 'pose3d';
    if (this.type === 'double[]') return 'array';
    return 'text';
  }

  /** Stores a new value and appends it to the history, discarding samples that are too old. */
  record(time, value) {
    this.value = value;
    this.time = time;
    if (this.kind === 'path' || this.kind === 'array') return; // No history is kept for these.
    this.t.push(time);
    this.v.push(this.type === 'boolean' ? (value ? 1 : 0) : value);
    if (this.t.length > MAX_POINTS || time - this.t[0] > HISTORY_SECONDS * 1.5) {
      let cut = 0;
      while (cut < this.t.length - 1 && time - this.t[cut] > HISTORY_SECONDS) cut++;
      cut = Math.max(cut, this.t.length - MAX_POINTS);
      this.t.splice(0, cut);
      this.v.splice(0, cut);
    }
  }

  /**
   * @param {number} time a time in seconds
   * @returns {number} the index of the last sample at or before that time, or -1
   */
  indexAt(time) {
    let low = 0,
      high = this.t.length - 1,
      found = -1;
    while (low <= high) {
      const mid = (low + high) >> 1;
      if (this.t[mid] <= time) {
        found = mid;
        low = mid + 1;
      } else high = mid - 1;
    }
    return found;
  }
}

/** The connection to the robot and the set of topics received from it. */
export class Store {
  constructor() {
    this.topics = new Map();
    this.connected = false;
    this.generation = 0; // Incremented whenever the set of topics changes.
    this.byId = new Map();
    this.publishIds = new Map();
    this.lastServerTime = 0;
    this.lastReceived = performance.now();
    this.socket = null;
    this.connect();
  }

  /** @returns {number} the robot's clock in seconds, extrapolated since the last message */
  now() {
    if (!this.connected) return this.lastServerTime;
    return this.lastServerTime + (performance.now() - this.lastReceived) / 1000;
  }

  /** @returns {Topic|undefined} the topic with a given name */
  get(name) {
    return this.topics.get(name);
  }

  /** Opens the connection, and reopens it whenever it closes. */
  connect() {
    const socket = new WebSocket(`ws://${location.hostname}:5810/nt/wolfdash`, [
      'networktables.first.wpi.edu',
    ]);
    socket.binaryType = 'arraybuffer';
    this.socket = socket;
    socket.onopen = () => {
      this.connected = true;
      this.byId.clear();
      this.publishIds.clear();
    };
    socket.onclose = () => {
      this.connected = false;
      setTimeout(() => this.connect(), 1000);
    };
    socket.onmessage = (event) => {
      if (typeof event.data === 'string') this.onControl(JSON.parse(event.data));
      else this.onValues(new DataView(event.data));
    };
  }

  onControl(messages) {
    for (const message of messages) {
      if (message.method !== 'announce') continue;
      const { name, id, type } = message.params;
      let topic = this.topics.get(name);
      if (!topic || topic.type !== type) {
        topic = new Topic(name, type);
        this.topics.set(name, topic);
        this.generation++;
      }
      this.byId.set(id, topic);
    }
  }

  onValues(view) {
    const reader = new Reader(view);
    while (reader.at < view.byteLength) {
      const [id, micros, , raw] = reader.value();
      const topic = this.byId.get(id);
      if (!topic || topic.type === 'structschema') continue;
      const time = micros / 1e6;
      if (time < this.lastServerTime - 2) this.resetHistory(); // The robot app restarted.
      if (time > this.lastServerTime) {
        this.lastServerTime = time;
        this.lastReceived = performance.now();
      }
      topic.record(time, decode(topic.type, raw));
    }
  }

  resetHistory() {
    this.lastServerTime = 0;
    for (const topic of this.topics.values()) {
      topic.t.length = 0;
      topic.v.length = 0;
    }
  }

  /**
   * Writes a value to a topic. The robot accepts writes only to tunables and buttons.
   *
   * @param {string} name the topic name
   * @param {number|boolean|string} value the value, of the topic's type
   */
  publish(name, value) {
    const topic = this.topics.get(name);
    if (!topic || !this.connected || !(topic.type in TYPE_INDEX)) return;
    let id = this.publishIds.get(name);
    if (id === undefined) {
      id = this.publishIds.size + 1;
      this.publishIds.set(name, id);
      this.socket.send(
        JSON.stringify([
          { method: 'publish', params: { name, pubuid: id, type: topic.type, properties: {} } },
        ]),
      );
    }
    this.socket.send(encodeUpdate(id, TYPE_INDEX[topic.type], topic.type, value));
  }
}

function decode(type, raw) {
  if (type === 'struct:Pose2d') return pose2d(raw, 0);
  if (type === 'struct:Pose2d[]') {
    const poses = [];
    for (let offset = 0; offset + 24 <= raw.byteLength; offset += 24)
      poses.push(pose2d(raw, offset));
    return poses;
  }
  if (type === 'struct:Pose3d') {
    const d = (i) => raw.getFloat64(i * 8, true);
    return {
      x: d(0) / METERS_PER_INCH,
      y: d(1) / METERS_PER_INCH,
      z: d(2) / METERS_PER_INCH,
      q: [d(3), d(4), d(5), d(6)],
    };
  }
  return raw;
}

function pose2d(raw, offset) {
  return {
    x: raw.getFloat64(offset, true) / METERS_PER_INCH,
    y: raw.getFloat64(offset + 8, true) / METERS_PER_INCH,
    h: raw.getFloat64(offset + 16, true),
  };
}

function encodeUpdate(id, typeIndex, type, value) {
  const text = type === 'string' ? new TextEncoder().encode(String(value)) : null;
  const bytes = new Uint8Array(16 + (text ? text.length + 5 : 9));
  const view = new DataView(bytes.buffer);
  let at = 0;
  bytes[at++] = 0x94;
  bytes[at++] = 0xcd;
  view.setUint16(at, id);
  at += 2;
  bytes[at++] = 0; // Timestamp. The server assigns its own.
  bytes[at++] = typeIndex;
  if (type === 'double') {
    bytes[at++] = 0xcb;
    view.setFloat64(at, Number(value));
    at += 8;
  } else if (type === 'boolean') {
    bytes[at++] = value ? 0xc3 : 0xc2;
  } else {
    bytes[at++] = 0xdb;
    view.setUint32(at, text.length);
    at += 4;
    bytes.set(text, at);
    at += text.length;
  }
  return bytes.subarray(0, at);
}

/** A MessagePack reader. Binary values are returned as a DataView over the source buffer. */
class Reader {
  constructor(view) {
    this.view = view;
    this.at = 0;
  }

  value() {
    const view = this.view;
    const b = view.getUint8(this.at++);
    if (b < 0x80) return b;
    if (b >= 0xe0) return b - 0x100;
    if (b >= 0xa0 && b < 0xc0) return this.text(b & 0x1f);
    if (b >= 0x90 && b < 0xa0) return this.list(b & 0x0f);
    switch (b) {
      case 0xc0:
        return null;
      case 0xc2:
        return false;
      case 0xc3:
        return true;
      case 0xc4:
        return this.bin(this.uint(1));
      case 0xc5:
        return this.bin(this.uint(2));
      case 0xc6:
        return this.bin(this.uint(4));
      case 0xca: {
        const v = view.getFloat32(this.at);
        this.at += 4;
        return v;
      }
      case 0xcb: {
        const v = view.getFloat64(this.at);
        this.at += 8;
        return v;
      }
      case 0xcc:
        return this.uint(1);
      case 0xcd:
        return this.uint(2);
      case 0xce:
        return this.uint(4);
      case 0xcf: {
        const v = Number(view.getBigUint64(this.at));
        this.at += 8;
        return v;
      }
      case 0xd0: {
        const v = view.getInt8(this.at);
        this.at += 1;
        return v;
      }
      case 0xd1: {
        const v = view.getInt16(this.at);
        this.at += 2;
        return v;
      }
      case 0xd2: {
        const v = view.getInt32(this.at);
        this.at += 4;
        return v;
      }
      case 0xd3: {
        const v = Number(view.getBigInt64(this.at));
        this.at += 8;
        return v;
      }
      case 0xd9:
        return this.text(this.uint(1));
      case 0xda:
        return this.text(this.uint(2));
      case 0xdb:
        return this.text(this.uint(4));
      case 0xdc:
        return this.list(this.uint(2));
      case 0xdd:
        return this.list(this.uint(4));
      default:
        throw new Error('Unsupported MessagePack type ' + b);
    }
  }

  uint(bytes) {
    const view = this.view;
    const v =
      bytes === 1
        ? view.getUint8(this.at)
        : bytes === 2
          ? view.getUint16(this.at)
          : view.getUint32(this.at);
    this.at += bytes;
    return v;
  }

  text(length) {
    const bytes = new Uint8Array(this.view.buffer, this.view.byteOffset + this.at, length);
    this.at += length;
    return new TextDecoder().decode(bytes);
  }

  bin(length) {
    const view = new DataView(this.view.buffer, this.view.byteOffset + this.at, length);
    this.at += length;
    return view;
  }

  list(length) {
    const out = new Array(length);
    for (let i = 0; i < length; i++) out[i] = this.value();
    return out;
  }
}
