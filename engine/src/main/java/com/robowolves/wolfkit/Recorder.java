/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Consumes frames on a dedicated thread, writing changed values to the current log file and
 * publishing them to the live stream.
 *
 * <p>A value is written only when it differs from the previous value of its topic, as in
 * NetworkTables and WPILib data logging.
 *
 * <p>Frames and buffers are reused. In steady state this thread allocates only for text values that
 * change and for messages sent to connected clients.
 *
 * <p>{@link #obtain()}, {@link #submit(Frame)}, {@link #startFile(String)}, {@link #stopFile()} and
 * {@link #wantsFrames()} may be called from any thread. All other state is confined to the recorder
 * thread.
 */
final class Recorder implements Runnable {
    private static final long FLUSH_MICROS = 1_000_000;
    private static final int QUEUE_SIZE = 500;

    private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);

    /** Frames available for reuse. */
    private final ArrayBlockingQueue<Frame> spare = new ArrayBlockingQueue<>(QUEUE_SIZE);

    private Topic[] bySlot = new Topic[256];
    private final List<Topic> allTopics = new ArrayList<>();
    private final List<Topic> created = new ArrayList<>();
    private final Buf payload = new Buf();
    private final Buf live = new Buf();
    private final File logDir;
    private final Nt4Server server;
    private final int loopTimeSlot = Keys.slot("/WolfKit/LoopMs");

    private Wpilog file;
    private int fileEpoch;
    private long lastFlush;
    private long lastLoopMicros;
    private boolean sendingLive;

    /** Whether a log file is open or about to be opened. Set from OpMode init to stop. */
    private volatile boolean recording;

    Recorder(File logDir, Nt4Server server) {
        this.logDir = logDir;
        this.server = server;
    }

    /** Returns whether frames have a destination: an open log file or a connected client. */
    boolean wantsFrames() {
        return recording || server.hasClients();
    }

    /** Returns an empty frame, reusing a pooled one when available. */
    Frame obtain() {
        Frame frame = spare.poll();
        return frame != null ? frame : new Frame();
    }

    /**
     * Queues a frame for processing. If the queue is full, the oldest queued frame is dropped.
     *
     * @return an empty frame for the caller to fill next
     */
    Frame submit(Frame frame) {
        while (!queue.offer(frame)) {
            Frame oldest = queue.poll();
            if (oldest != null) recycle(oldest);
        }
        return obtain();
    }

    private void recycle(Frame frame) {
        frame.reset();
        spare.offer(frame);
    }

    /**
     * Requests a new log file for an OpMode run, if {@link WolfKitConfig#recordLogFiles} is set.
     */
    void startFile(String opModeName) {
        if (!WolfKitConfig.recordLogFiles) return;
        recording = true;
        Frame request = obtain();
        request.action = Frame.OPEN_FILE;
        request.micros = WolfKit.micros();
        request.opModeName = opModeName;
        submit(request);
    }

    /** Requests that the current log file be closed. */
    void stopFile() {
        recording = false;
        Frame request = obtain();
        request.action = Frame.CLOSE_FILE;
        submit(request);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Frame frame = queue.poll(50, TimeUnit.MILLISECONDS);
                if (frame == null) {
                    WolfKit.closeStaleFrame();
                } else {
                    try {
                        if (frame.action == Frame.VALUES) write(frame);
                        else if (frame.action == Frame.OPEN_FILE) openFile(frame.opModeName);
                        else closeFile();
                    } finally {
                        recycle(frame);
                    }
                }

                for (Nt4Server.Client client = server.nextPending();
                        client != null;
                        client = server.nextPending()) {
                    welcome(client);
                }
                if (file != null && WolfKit.micros() - lastFlush > FLUSH_MICROS) {
                    lastFlush = WolfKit.micros();
                    file.flush();
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                // Storage is full or unavailable. Stop recording; the live stream continues.
                closeFile();
            }
        }
        closeFile();
    }

    /** Writes the values of a frame to the log file and the live stream. */
    private void write(Frame frame) throws IOException {
        sendingLive = server.hasClients();
        live.clear();
        created.clear();
        long micros = frame.micros;

        for (int i = 0; i < frame.count; i++) {
            byte kind = frame.kinds[i];
            Topic topic = topic(frame.slots[i], kind, micros);
            if (topic.kind != kind) continue; // A key keeps the type it was first logged with.

            if (kind == Frame.TEXT) {
                String text = frame.texts[i];
                if (text == null) continue;
                boolean changed = !text.equals(topic.lastText);
                if (!changed && !fileNeeds(topic)) continue;
                topic.lastText = text;
                payload.clear().bytes(Buf.utf8(text));
                emit(topic, micros, changed);
                continue;
            }

            payload.clear();
            if (kind == Frame.NUMBER) payload.leDouble(frame.numbers[i]);
            else if (kind == Frame.BOOLEAN) payload.u8(frame.numbers[i] != 0 ? 1 : 0);
            else {
                int from = (int) frame.numbers[i];
                for (int j = 0; j < frame.lengths[i]; j++) payload.leDouble(frame.extra[from + j]);
            }
            emit(topic, micros, !topic.sameAsLast(payload));
        }

        if (frame.loop && WolfKitConfig.logLoopTime) {
            long period = micros - lastLoopMicros;
            lastLoopMicros = micros;
            if (period < 1_000_000) {
                payload.clear().leDouble(period / 1000.0);
                Topic topic = topic(loopTimeSlot, Frame.NUMBER, micros);
                emit(topic, micros, !topic.sameAsLast(payload));
            }
        }

        if (sendingLive && (!created.isEmpty() || live.size() > 0)) {
            server.broadcast(
                    created.isEmpty() ? null : Nt4Server.announce(created), live.toBytes());
        }
    }

    /**
     * Publishes the value held in {@link #payload}.
     *
     * @param changed whether the value differs from the topic's previous value. An unchanged value
     *     is written only to a log file that does not yet contain the topic.
     */
    private void emit(Topic topic, long micros, boolean changed) throws IOException {
        boolean toFile = fileNeeds(topic) || (changed && file != null);
        if (!changed && !toFile) return;
        if (changed) {
            topic.remember(payload);
            if (sendingLive) Nt4Server.value(live, topic, micros, payload.data(), payload.size());
        }
        if (toFile) {
            file.append(topic, micros, payload.data(), payload.size());
            topic.fileEpoch = fileEpoch;
        }
    }

    /**
     * Returns whether the open log file lacks a value for a topic. Each file is self-contained, so
     * every topic is written to it once even if its value is unchanged.
     */
    private boolean fileNeeds(Topic topic) {
        return file != null && topic.fileEpoch != fileEpoch;
    }

    /**
     * Returns the topic for a slot, creating it and publishing any schemas it needs on first use.
     */
    private Topic topic(int slot, byte kind, long micros) throws IOException {
        if (slot >= bySlot.length)
            bySlot = Arrays.copyOf(bySlot, Math.max(slot + 1, bySlot.length * 2));
        Topic topic = bySlot[slot];
        if (topic == null) {
            for (String[] schema : Schemas.of(kind)) publishSchema(schema[0], schema[1], micros);
            String key = Keys.name(slot);
            topic = create(slot, key.startsWith("/") ? key : "/" + key, kind);
        }
        return topic;
    }

    private Topic create(int slot, String name, byte kind) {
        Topic topic = new Topic(allTopics.size() + 1, name, kind);
        bySlot[slot] = topic;
        allTopics.add(topic);
        created.add(topic);
        return topic;
    }

    private void publishSchema(String type, String schema, long micros) throws IOException {
        int slot = Keys.slot(Topic.SCHEMA_PREFIX + type);
        if (slot >= bySlot.length)
            bySlot = Arrays.copyOf(bySlot, Math.max(slot + 1, bySlot.length * 2));
        if (bySlot[slot] != null) return;
        Topic topic = create(slot, Topic.SCHEMA_PREFIX + type, Frame.SCHEMA);
        payload.clear().bytes(Buf.utf8(schema));
        emit(topic, micros, true);
        payload.clear();
    }

    /** Sends a newly connected client every topic and its current value. */
    private void welcome(Nt4Server.Client client) {
        Buf values = new Buf();
        long now = WolfKit.micros();
        for (Topic topic : allTopics) {
            if (topic.lastSize >= 0)
                Nt4Server.value(values, topic, now, topic.last, topic.lastSize);
        }
        server.welcome(client, Nt4Server.announce(allTopics), values.toBytes());
    }

    private void openFile(String opModeName) throws IOException {
        closeFile();
        if (!logDir.isDirectory() && !logDir.mkdirs())
            throw new IOException("Cannot create " + logDir);
        deleteOldFiles();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String name = opModeName.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        file = new Wpilog(new File(logDir, stamp + "_" + name + ".wpilog"));
        fileEpoch++;
        lastFlush = WolfKit.micros();
        // Schemas are published once, so they are written now. Other topics follow when next
        // logged.
        for (Topic topic : allTopics) {
            if (topic.kind != Frame.SCHEMA) continue;
            file.append(topic, lastFlush, topic.last, topic.lastSize);
            topic.fileEpoch = fileEpoch;
        }
    }

    private void closeFile() {
        if (file != null) file.close();
        file = null;
    }

    /** Deletes the oldest log files so that at most {@link WolfKitConfig#keepLogFiles} remain. */
    private void deleteOldFiles() {
        File[] logs = logDir.listFiles((dir, name) -> name.endsWith(".wpilog"));
        if (logs == null) return;
        Arrays.sort(logs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i <= logs.length - Math.max(1, WolfKitConfig.keepLogFiles); i++)
            logs[i].delete();
    }
}
