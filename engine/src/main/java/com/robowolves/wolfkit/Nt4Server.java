/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A minimal NetworkTables 4 server over WebSocket.
 *
 * <p>Implements the subset of the protocol that WolfDash and AdvantageScope use: topic
 * announcements, value updates, clock synchronisation and client writes to {@linkplain Tunables
 * tunable} topics. Subscriptions are not tracked; every topic is sent to every client.
 *
 * <p>Network writes are performed by a dedicated sender thread, so a slow client cannot block the
 * {@link Recorder}.
 */
final class Nt4Server extends NanoWSD {
    /** The standard NetworkTables 4 port. */
    static final int PORT = 5810;

    private static final String PROTOCOL = "networktables.first.wpi.edu";

    private final Set<Client> open = ConcurrentHashMap.newKeySet();
    private final Queue<Client> pending = new ConcurrentLinkedQueue<>();
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private final ArrayBlockingQueue<Message> outbox = new ArrayBlockingQueue<>(1000);
    private volatile boolean overflowed;
    private Thread sender;

    Nt4Server() {
        super(PORT);
    }

    /**
     * Starts listening and starts the sender thread.
     *
     * @throws IOException if the port is in use
     */
    void open() throws IOException {
        start(0, true);
        sender = new Thread(this::sendLoop, "WolfKit NT4 sender");
        sender.setDaemon(true);
        sender.start();
    }

    /** Stops the sender thread and closes all connections. */
    void close() {
        sender.interrupt();
        stop();
    }

    /** Returns whether any client is connected. */
    boolean hasClients() {
        return !open.isEmpty();
    }

    /** Returns a client that has connected but has not yet been welcomed, or {@code null}. */
    Client nextPending() {
        return pending.poll();
    }

    /**
     * Queues a message for every welcomed client.
     *
     * @param text a JSON control message, or {@code null}
     * @param binary MessagePack value updates, or {@code null}
     */
    void broadcast(String text, byte[] binary) {
        post(new Message(null, text, binary));
    }

    /**
     * Queues the existing topics and values for a new client, after which it receives broadcasts.
     */
    void welcome(Client client, String text, byte[] binary) {
        post(new Message(client, text, binary));
    }

    private void post(Message message) {
        if (!outbox.offer(message)) overflowed = true;
    }

    /** Delivers queued messages. Runs on the sender thread, which performs all network writes. */
    private void sendLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Message message = outbox.take();
                if (overflowed) {
                    // The queue overflowed, so messages were lost. Disconnect all clients so that
                    // they reconnect and receive a consistent snapshot.
                    outbox.clear();
                    overflowed = false;
                    clients.clear();
                    for (Client client : open) client.drop();
                    continue;
                }
                if (message.target != null) {
                    message.target.deliver(message);
                    if (open.contains(message.target)) clients.add(message.target);
                } else {
                    for (Client client : clients) client.deliver(message);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        Response response = super.serve(session);
        String offered = session.getHeaders().get(HEADER_WEBSOCKET_PROTOCOL);
        // NanoWSD selects the first protocol offered, which is 4.1. This server implements 4.0.
        if (offered != null && offered.contains(PROTOCOL))
            response.addHeader(HEADER_WEBSOCKET_PROTOCOL, PROTOCOL);
        return response;
    }

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new Client(handshake);
    }

    /** Returns the JSON message announcing a list of topics. */
    static String announce(List<Topic> topics) {
        StringBuilder out = new StringBuilder("[");
        for (Topic topic : topics) {
            if (out.length() > 1) out.append(',');
            out.append("{\"method\":\"announce\",\"params\":{\"name\":");
            quote(out, topic.name);
            out.append(",\"id\":").append(topic.id).append(",\"type\":");
            quote(out, topic.type);
            out.append(",\"properties\":{}}}");
        }
        return out.append(']').toString();
    }

    /** Appends a string as a JSON string literal. */
    static void quote(StringBuilder out, String text) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
            else out.append(c);
        }
        out.append('"');
    }

    /**
     * Appends a value update, the MessagePack array {@code [topic id, timestamp, type, value]}.
     *
     * @param micros the timestamp in microseconds
     * @param payload the value in WPILOG encoding
     * @param length the number of bytes of {@code payload} that are valid
     */
    static void value(Buf out, Topic topic, long micros, byte[] payload, int length) {
        out.u8(0x94);
        uint(out, topic.id);
        uint(out, micros);
        switch (topic.type) {
            case "boolean":
                out.u8(0).u8(payload[0] != 0 ? 0xc3 : 0xc2);
                break;
            case "double":
                out.u8(1);
                float64(out, payload, 0);
                break;
            case "string":
                out.u8(4);
                if (length < 32) out.u8(0xa0 | length);
                else if (length < 0x100) out.u8(0xd9).u8(length);
                else if (length < 0x10000) out.u8(0xda).be(length, 2);
                else out.u8(0xdb).be(length, 4);
                out.bytes(payload, length);
                break;
            case "double[]":
                out.u8(17);
                int count = length / 8;
                if (count < 16) out.u8(0x90 | count);
                else if (count < 0x10000) out.u8(0xdc).be(count, 2);
                else out.u8(0xdd).be(count, 4);
                for (int i = 0; i < count; i++) float64(out, payload, i * 8);
                break;
            default: // Structs and schemas are sent as raw bytes.
                out.u8(5);
                if (length < 0x100) out.u8(0xc4).u8(length);
                else if (length < 0x10000) out.u8(0xc5).be(length, 2);
                else out.u8(0xc6).be(length, 4);
                out.bytes(payload, length);
        }
    }

    /** Appends a MessagePack float64 from eight little-endian bytes of a payload. */
    private static void float64(Buf out, byte[] payload, int offset) {
        out.u8(0xcb);
        for (int i = 7; i >= 0; i--) out.u8(payload[offset + i]);
    }

    /** Appends a non-negative integer in the shortest MessagePack encoding. */
    private static void uint(Buf out, long value) {
        if (value < 0x80) out.u8((int) value);
        else if (value < 0x100) out.u8(0xcc).u8((int) value);
        else if (value < 0x10000) out.u8(0xcd).be(value, 2);
        else if (value < 0x100000000L) out.u8(0xce).be(value, 4);
        else out.u8(0xcf).be(value, 8);
    }

    /** A queued message for one client, or for all clients when {@link #target} is {@code null}. */
    private static final class Message {
        final Client target;
        final String text;
        final byte[] binary;

        Message(Client target, String text, byte[] binary) {
            this.target = target;
            this.text = text;
            this.binary = binary;
        }
    }

    /** A connected client. */
    final class Client extends WebSocket {
        /**
         * The topics this client has declared it will write, keyed by the client's publisher id.
         */
        private final Map<Long, String> publishing = new ConcurrentHashMap<>();

        Client(IHTTPSession handshake) {
            super(handshake);
        }

        void deliver(Message message) {
            try {
                if (message.text != null) send(message.text);
                if (message.binary != null && message.binary.length > 0) send(message.binary);
            } catch (IOException e) {
                forget();
            }
        }

        void drop() {
            forget();
            try {
                close(WebSocketFrame.CloseCode.GoingAway, "fell behind", false);
            } catch (IOException ignored) {
            }
        }

        private void forget() {
            open.remove(this);
            clients.remove(this);
        }

        @Override
        protected void onOpen() {
            open.add(this);
            pending.add(this);
        }

        @Override
        protected void onClose(
                WebSocketFrame.CloseCode code, String reason, boolean initiatedByRemote) {
            forget();
        }

        @Override
        protected void onMessage(WebSocketFrame message) {
            try {
                if (message.getOpCode() == WebSocketFrame.OpCode.Binary)
                    onValues(message.getBinaryPayload());
                else onControl(message.getTextPayload());
            } catch (RuntimeException malformed) {
                // Malformed or unsupported messages are ignored.
            }
        }

        /** Handles a JSON control message. Only {@code publish} requests are acted on. */
        private void onControl(String text) {
            for (JsonElement element : new JsonParser().parse(text).getAsJsonArray()) {
                JsonObject request = element.getAsJsonObject();
                if (!"publish".equals(request.get("method").getAsString())) continue;
                JsonObject params = request.getAsJsonObject("params");
                publishing.put(params.get("pubuid").getAsLong(), params.get("name").getAsString());
            }
        }

        /**
         * Handles binary value updates. An update with id -1 is a clock synchronisation request;
         * any other is a write to a topic the client has declared.
         */
        private void onValues(byte[] data) {
            MsgPack in = new MsgPack(data);
            while (in.hasMore()) {
                int start = in.position();
                List<Object> update = in.array();
                long id = ((Number) update.get(0)).longValue();
                if (id == -1) {
                    answerClockSync(data, start, in.position());
                } else {
                    String topic = publishing.get(id);
                    Object value = update.get(3);
                    if (value instanceof Number) value = ((Number) value).doubleValue();
                    if (topic != null && Tunables.accept(topic, value) != null) {
                        // Publish the new value at once, whether or not an OpMode is running.
                        WolfKitRuntime.Batch echo = new WolfKitRuntime.Batch();
                        if (echo.begin()) {
                            if (value instanceof Double) echo.put(topic, (Double) value);
                            else if (value instanceof Boolean) echo.put(topic, (Boolean) value);
                            else echo.put(topic, (String) value);
                            echo.send();
                        }
                    }
                }
            }
        }

        /**
         * Replies to a clock synchronisation request. The request is {@code [-1, 0, type, client
         * time]}; the reply replaces the zero with the server time.
         */
        private void answerClockSync(byte[] data, int start, int end) {
            MsgPack in = new MsgPack(data);
            in.seek(start + 2); // Skip the array header and the id.
            in.value(); // Skip the zero timestamp.
            Buf reply = new Buf().u8(0x94).u8(0xff);
            uint(reply, WolfKit.micros());
            for (int i = in.position(); i < end; i++) reply.u8(data[i]);
            try {
                send(reply.toBytes());
            } catch (IOException e) {
                forget();
            }
        }

        @Override
        protected void onPong(WebSocketFrame pong) {}

        @Override
        protected void onException(IOException exception) {
            forget();
        }
    }

    /** A reader for the MessagePack values a NetworkTables client sends. */
    private static final class MsgPack {
        private final byte[] data;
        private int at;

        MsgPack(byte[] data) {
            this.data = data;
        }

        boolean hasMore() {
            return at < data.length;
        }

        int position() {
            return at;
        }

        void seek(int position) {
            at = position;
        }

        @SuppressWarnings("unchecked")
        List<Object> array() {
            return (List<Object>) value();
        }

        Object value() {
            int b = data[at++] & 0xff;
            if (b < 0x80) return (long) b;
            if (b >= 0xe0) return (long) (b - 0x100);
            if (b >= 0xa0 && b < 0xc0) return text(b & 0x1f);
            if (b >= 0x90 && b < 0xa0) return list(b & 0x0f);
            switch (b) {
                case 0xc0:
                    return null;
                case 0xc2:
                    return false;
                case 0xc3:
                    return true;
                case 0xc4:
                    return bin((int) unsigned(1));
                case 0xc5:
                    return bin((int) unsigned(2));
                case 0xc6:
                    return bin((int) unsigned(4));
                case 0xca:
                    return (double) Float.intBitsToFloat((int) unsigned(4));
                case 0xcb:
                    return Double.longBitsToDouble(unsigned(8));
                case 0xcc:
                    return unsigned(1);
                case 0xcd:
                    return unsigned(2);
                case 0xce:
                    return unsigned(4);
                case 0xcf:
                    return unsigned(8);
                case 0xd0:
                    return (long) (byte) unsigned(1);
                case 0xd1:
                    return (long) (short) unsigned(2);
                case 0xd2:
                    return (long) (int) unsigned(4);
                case 0xd3:
                    return unsigned(8);
                case 0xd9:
                    return text((int) unsigned(1));
                case 0xda:
                    return text((int) unsigned(2));
                case 0xdb:
                    return text((int) unsigned(4));
                case 0xdc:
                    return list((int) unsigned(2));
                case 0xdd:
                    return list((int) unsigned(4));
                default:
                    throw new IllegalArgumentException("Unsupported MessagePack type " + b);
            }
        }

        private long unsigned(int bytes) {
            long result = 0;
            for (int i = 0; i < bytes; i++) result = (result << 8) | (data[at++] & 0xff);
            return result;
        }

        private String text(int length) {
            String result = new String(data, at, length, StandardCharsets.UTF_8);
            at += length;
            return result;
        }

        private byte[] bin(int length) {
            byte[] result = java.util.Arrays.copyOfRange(data, at, at + length);
            at += length;
            return result;
        }

        private List<Object> list(int length) {
            List<Object> result = new java.util.ArrayList<>(length);
            for (int i = 0; i < length; i++) result.add(value());
            return result;
        }
    }
}
