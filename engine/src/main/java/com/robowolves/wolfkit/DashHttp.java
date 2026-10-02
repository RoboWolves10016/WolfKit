/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles HTTP requests for the WolfDash page and its API, all under {@link #ROOT}.
 *
 * <p>The host supplies the page's files through {@link Assets} and robot control through {@link
 * Robot}, which lets one implementation serve both a robot and the simulator.
 *
 * <table>
 *   <caption>API endpoints, relative to {@code ROOT}</caption>
 *   <tr><th>Path</th><th>Method</th><th>Purpose</th></tr>
 *   <tr><td>{@code /api/opmodes}</td><td>GET</td><td>Lists the available OpModes.</td></tr>
 *   <tr><td>{@code /api/opmode}</td><td>POST</td><td>Inits, starts or stops an OpMode.</td></tr>
 *   <tr><td>{@code /api/layouts}</td><td>GET, POST</td>
 *       <td>Loads or saves dashboard layouts.</td></tr>
 *   <tr><td>{@code /api/logs}</td><td>GET</td><td>Lists recorded log files.</td></tr>
 *   <tr><td>{@code /api/log}</td><td>GET</td><td>Downloads a log file.</td></tr>
 * </table>
 */
public final class DashHttp {
    /** The URL path under which the dashboard is served. */
    public static final String ROOT = "/dash";

    /**
     * The API paths, relative to {@link #ROOT}. The FTC SDK's web server matches whole paths, so a
     * host on a robot registers each of these.
     */
    public static final List<String> API_PATHS =
            Arrays.asList("/api/opmodes", "/api/opmode", "/api/layouts", "/api/logs", "/api/log");

    /** The source of the dashboard page's files. */
    public interface Assets {
        /**
         * Opens a file of the dashboard page.
         *
         * @param path the path relative to the page root, such as {@code js/main.js}
         * @return a stream of the file's contents
         * @throws IOException if there is no such file
         */
        InputStream open(String path) throws IOException;
    }

    /** The robot operations available to the dashboard. */
    public interface Robot {
        /**
         * Returns the available OpModes.
         *
         * @return one {@code {name, group, kind}} array per OpMode, where kind is {@code "TELEOP"}
         *     or {@code "AUTONOMOUS"}
         */
        List<String[]> opModes();

        /**
         * Initialises an OpMode.
         *
         * @param opModeName the name of the OpMode, as returned by {@link #opModes()}
         */
        void init(String opModeName);

        /** Starts the initialised OpMode. */
        void start();

        /** Stops the active OpMode. */
        void stop();
    }

    private static final Map<String, String> MIME = new HashMap<>();

    static {
        MIME.put("html", "text/html");
        MIME.put("css", "text/css");
        MIME.put("js", "text/javascript");
        MIME.put("json", "application/json");
        MIME.put("svg", "image/svg+xml");
        MIME.put("png", "image/png");
        MIME.put("glb", "model/gltf-binary");
    }

    private final Assets assets;
    private final Robot robot;

    /**
     * Creates a handler.
     *
     * @param assets the source of the page's files
     * @param robot the robot to control
     */
    public DashHttp(Assets assets, Robot robot) {
        this.assets = assets;
        this.robot = robot;
    }

    /**
     * Responds to a request whose path starts with {@link #ROOT}.
     *
     * @param session the request
     * @return the response; errors are reported as HTTP error responses, never thrown
     */
    public Response respond(IHTTPSession session) {
        String path = session.getUri().substring(ROOT.length());
        try {
            if (path.startsWith("/api/")) return api(path, session);
            if (path.isEmpty()) return redirect(ROOT + "/");
            return asset(path.equals("/") ? "index.html" : path.substring(1));
        } catch (IOException | NanoHTTPD.ResponseException | RuntimeException e) {
            return text(Response.Status.INTERNAL_ERROR, String.valueOf(e.getMessage()));
        }
    }

    private Response api(String path, IHTTPSession session)
            throws IOException, NanoHTTPD.ResponseException {
        boolean post = session.getMethod() == NanoHTTPD.Method.POST;
        Map<String, String> params = session.getParms();
        switch (path) {
            case "/api/opmodes":
                return json(opModesJson());
            case "/api/opmode":
                if (!post) return text(Response.Status.METHOD_NOT_ALLOWED, "POST only");
                String action = String.valueOf(params.get("action"));
                if (action.equals("init")) robot.init(params.get("name"));
                else if (action.equals("start")) robot.start();
                else if (action.equals("stop")) robot.stop();
                else return text(Response.Status.BAD_REQUEST, "Unknown action");
                return json("{}");
            case "/api/layouts":
                if (post) {
                    Map<String, String> body = new HashMap<>();
                    session.parseBody(body);
                    String layouts = body.get("postData");
                    if (layouts == null) return text(Response.Status.BAD_REQUEST, "Empty body");
                    write(WolfKitRuntime.layoutsFile(), layouts);
                    return json("{}");
                }
                File saved = WolfKitRuntime.layoutsFile();
                return json(
                        saved.isFile()
                                ? new String(
                                        readAll(new FileInputStream(saved)), StandardCharsets.UTF_8)
                                : "{}");
            case "/api/logs":
                return json(logsJson());
            case "/api/log":
                File log =
                        new File(
                                WolfKitRuntime.logDir(),
                                new File(String.valueOf(params.get("name"))).getName());
                if (!log.isFile()) return text(Response.Status.NOT_FOUND, "No such log");
                Response download =
                        NanoHTTPD.newFixedLengthResponse(
                                Response.Status.OK,
                                "application/octet-stream",
                                new FileInputStream(log),
                                log.length());
                download.addHeader(
                        "Content-Disposition", "attachment; filename=\"" + log.getName() + "\"");
                return download;
            default:
                return text(Response.Status.NOT_FOUND, "Not found");
        }
    }

    private String opModesJson() {
        StringBuilder out = new StringBuilder("[");
        for (String[] opMode : robot.opModes()) {
            if (out.length() > 1) out.append(',');
            out.append("{\"name\":");
            Nt4Server.quote(out, opMode[0]);
            out.append(",\"group\":");
            Nt4Server.quote(out, opMode[1]);
            out.append(",\"kind\":");
            Nt4Server.quote(out, opMode[2]);
            out.append('}');
        }
        return out.append(']').toString();
    }

    private static String logsJson() {
        File[] logs = WolfKitRuntime.logDir().listFiles((dir, name) -> name.endsWith(".wpilog"));
        StringBuilder out = new StringBuilder("[");
        if (logs != null) {
            Arrays.sort(logs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File log : logs) {
                if (out.length() > 1) out.append(',');
                out.append("{\"name\":");
                Nt4Server.quote(out, log.getName());
                out.append(",\"bytes\":")
                        .append(log.length())
                        .append(",\"modified\":")
                        .append(log.lastModified())
                        .append('}');
            }
        }
        return out.append(']').toString();
    }

    private Response asset(String path) throws IOException {
        if (path.contains("..")) return text(Response.Status.FORBIDDEN, "Forbidden");
        byte[] content;
        try {
            content = readAll(assets.open(path));
        } catch (IOException missing) {
            return text(Response.Status.NOT_FOUND, "Not found: " + path);
        }
        String mime = MIME.get(path.substring(path.lastIndexOf('.') + 1));
        Response response =
                NanoHTTPD.newFixedLengthResponse(
                        Response.Status.OK,
                        mime == null ? "application/octet-stream" : mime,
                        new ByteArrayInputStream(content),
                        content.length);
        // The page is replaced on every deploy of the robot app, so it must not be cached.
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response redirect(String location) {
        Response response = text(Response.Status.REDIRECT, "");
        response.addHeader("Location", location);
        return response;
    }

    private static Response json(String body) {
        Response response =
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", body);
        response.addHeader("Cache-Control", "no-store");
        return response;
    }

    private static Response text(Response.IStatus status, String body) {
        return NanoHTTPD.newFixedLengthResponse(status, "text/plain", body);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            Buf out = new Buf();
            byte[] chunk = new byte[8192];
            for (int read = stream.read(chunk); read >= 0; read = stream.read(chunk)) {
                out.bytes(Arrays.copyOf(chunk, read));
            }
            return out.toBytes();
        }
    }

    private static void write(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create " + parent);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
