/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit.sim;

import com.robowolves.wolfkit.DashHttp;
import com.robowolves.wolfkit.WolfKitRuntime;

import fi.iki.elonen.NanoHTTPD;

import java.io.File;
import java.io.FileInputStream;

/**
 * Runs WolfKit on a desktop with a simulated robot, for developing WolfDash without a Control Hub.
 *
 * <p>Start it with {@code ./gradlew :sim:run}, then open {@code http://localhost:8080/dash/}.
 */
public final class Sim {
    /**
     * Starts the simulator.
     *
     * @param args the folder containing the dashboard page, the folder for logs and layouts, and
     *     optionally the HTTP port, which defaults to 8080
     */
    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]);
        File data = new File(args[1]);
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8080;

        WolfKitRuntime.start(data);
        SimRobot robot = new SimRobot();
        DashHttp dash = new DashHttp(path -> new FileInputStream(new File(assets, path)), robot);

        NanoHTTPD http =
                new NanoHTTPD(port) {
                    @Override
                    public Response serve(IHTTPSession session) {
                        if (session.getUri().startsWith(DashHttp.ROOT))
                            return dash.respond(session);
                        Response redirect =
                                newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                        redirect.addHeader("Location", DashHttp.ROOT + "/");
                        return redirect;
                    }
                };
        http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        System.out.println("WolfDash: http://localhost:" + port + DashHttp.ROOT + "/");
        System.out.println(
                "Live stream on port " + WolfKitRuntime.livePort() + ", data in " + data);
    }
}
