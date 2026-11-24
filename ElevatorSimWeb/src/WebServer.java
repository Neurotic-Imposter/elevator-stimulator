import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

// The bridge between browser and engine. It uses Java's built-in HttpServer, serves web/ files and
// the JSON API under /api/. All requests run on ONE thread (single-thread executor), so the
// simulation is never touched by two requests at once and needs no locks.
class WebServer {
    private static File webDir = new File("web");
    private static HttpServer server;

    static void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0); // local machine only
        server.createContext("/api/", ex -> handle(ex));
        server.createContext("/", ex -> serveFile(ex));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    // ---------- small JSON helpers ----------

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 32) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    static String arr(Collection<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (String s : items) sb.append(sb.length() > 1 ? "," : "").append(q(s));
        return sb.append("]").toString();
    }

    // ---------- routing ----------

    private static Map<String, String> params(HttpExchange ex) throws IOException {
        Map<String, String> m = new HashMap<String, String>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
        }
        return m;
    }

    private static int intParam(Map<String, String> p, String key) {
        String v = p.get(key);
        if (v == null) throw new IllegalArgumentException("Missing number: " + key);
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + v + "' is not a whole number (" + key + ")");
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        int status = 200;
        String body;
        String type = "application/json; charset=utf-8";
        try {
            Map<String, String> p = params(ex);
            boolean post = method.equals("POST");
            int before = Trace.lastId();
            if (path.equals("/api/ping") && !post) body = "{\"ok\":true,\"epoch\":" + Main.epoch + ",\"lastEventId\":" + Trace.lastId() + ",\"clock\":" + Main.building.getClock() + "}";
            else if (path.equals("/api/state") && !post) body = stateJson(true);
            else if (path.equals("/api/passenger") && post) body = handlePassenger(p, before);
            else if (path.equals("/api/step") && post) body = handleStep(p, before);
            else if (path.equals("/api/fault") && post) body = handleFault(p, before);
            else if (path.equals("/api/stop") && post) body = handleStop(p, before);
            else if (path.equals("/api/scheduler") && post) body = handleScheduler(p, before);
            else if (path.equals("/api/random") && post) body = handleRandom(p, before);
            else if (path.equals("/api/reset") && post) body = handleReset(before);
            else if (path.equals("/api/config") && !post) body = configJson();
            else if (path.equals("/api/config") && post) body = handleConfig(p);
            else if (path.equals("/api/compare") && !post) body = compareJson(intParam(p, "count"));
            else if (path.equals("/api/whatif") && !post) body = whatIfJson(intParam(p, "floor"), p.get("dir"));
            else if (path.equals("/api/classes") && !post) body = "{\"classes\":" + Explain.classesJson() + ",\"concepts\":" + Explain.oopJson() + "}";
            else if (path.equals("/api/source") && !post) body = sourceJson(p.get("cls"), p.get("method"));
            else if (path.equals("/api/report") && !post) body = reportJson();
            else if (path.equals("/api/report.txt") && !post) { body = Main.reportText(); Main.writeReport(); type = "text/plain; charset=utf-8"; }
            else if (path.equals("/api/log.txt") && !post) { body = fileText("simulation.log"); type = "text/plain; charset=utf-8"; }
            else if (path.equals("/api/shutdown") && post) { send(ex, 200, type, "{\"ok\":true}"); System.out.println("Shutdown requested."); System.exit(0); return; }
            else { status = 404; body = "{\"ok\":false,\"error\":\"No such endpoint: " + method + " " + path + "\"}"; }
        } catch (IllegalArgumentException e) {
            status = 400;
            body = "{\"ok\":false,\"error\":" + q(e.getMessage()) + "}";
        } catch (Throwable t) {
            status = 500;
            t.printStackTrace();
            body = "{\"ok\":false,\"error\":" + q("Server error: " + t) + "}";
        }
        send(ex, status, type, body);
    }

    private static String fileText(String name) throws IOException {
        File f = new File(name);
        return f.exists() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : "";
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private static void serveFile(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";
        File f = new File(webDir, path.substring(1)).getCanonicalFile();
        if (!f.getPath().startsWith(webDir.getCanonicalPath()) || !f.isFile()) {
            send(ex, 404, "text/plain; charset=utf-8", "Not found: " + path);
            return;
        }
        String n = f.getName();
        String type = n.endsWith(".html") ? "text/html; charset=utf-8" : n.endsWith(".css") ? "text/css; charset=utf-8"
                : n.endsWith(".js") ? "application/javascript; charset=utf-8" : "application/octet-stream";
        byte[] bytes = Files.readAllBytes(f.toPath());
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    // ---------- action handlers: each one is a real entry point shown in the Execution Flow ----------

    // Wraps the common pattern: start an action, run it, record any exception, answer with new events.
    private interface Job { void run() throws Exception; }

    private static String act(String handler, String args, String why, int before, Job job) {
        Trace.beginAction();
        TraceEvent ev = Trace.in("SYSTEM", "WebServer", handler, args, why);
        boolean ok = true;
        String error = "";
        try {
            try {
                job.run();
                ev.result = "ok";
            } catch (InvalidFloorException | OverloadException | ElevatorFaultException e) {
                ok = false;
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
                ev.result = "refused: " + e.getClass().getSimpleName();
                Trace.exception(e, "WebServer." + handler, "The engine refused the request. WebServer caught the exception, so the simulation state did not change and the browser gets this message.");
            } catch (IndexOutOfBoundsException e) {
                throw new IllegalArgumentException("No such elevator");
            } catch (Exception e) {
                if (e instanceof RuntimeException) throw (RuntimeException) e;
                throw new RuntimeException(e);
            }
        } finally {
            Trace.out();
        }
        Main.flushLog();
        return "{\"ok\":" + ok + ",\"error\":" + q(error) + ",\"state\":" + stateJson(false) + ",\"events\":" + eventsJson(Trace.after(before)) + "}";
    }

    private static String handlePassenger(Map<String, String> p, int before) {
        final int s = intParam(p, "source"), d = intParam(p, "dest"), w = intParam(p, "weight");
        return act("handlePassenger", "source=" + s + ", dest=" + d + ", weight=" + w,
                "The browser clicked Add Passenger. This handler calls Building.addPassenger().", before,
                () -> Main.building.addPassenger(s, d, w));
    }

    private static String handleStep(Map<String, String> p, int before) {
        final int n = Math.max(1, Math.min(100, intParam(p, "n")));
        return act("handleStep", "n=" + n, "The browser asked for " + n + " tick(s). This handler calls Building.tick() that many times.", before,
                () -> { for (int i = 0; i < n; i++) Main.building.tick(); });
    }

    private static String handleFault(Map<String, String> p, int before) {
        final int id = intParam(p, "id");
        final String what = String.valueOf(p.get("action"));
        if (!what.equals("break") && !what.equals("repair")) throw new IllegalArgumentException("action must be break or repair");
        return act("handleFault", "id=" + id + ", action=" + what, "The browser asked to " + what + " elevator E" + id + ".", before,
                () -> { if (what.equals("break")) Main.building.breakElevator(id); else Main.building.repairElevator(id); });
    }

    private static String handleStop(Map<String, String> p, int before) {
        final int id = intParam(p, "id"), floor = intParam(p, "floor");
        return act("handleStop", "id=" + id + ", floor=" + floor, "The browser sent a manual stop command to E" + id + ".", before,
                () -> Main.building.commandStop(id, floor));
    }

    private static String handleScheduler(Map<String, String> p, int before) {
        final String name = String.valueOf(p.get("name")).toUpperCase();
        if (!name.equals("NEAREST") && !name.equals("SCAN")) throw new IllegalArgumentException("scheduler must be NEAREST or SCAN");
        return act("handleScheduler", "name=" + name, "The browser switched the scheduling algorithm.", before, () -> {
            Main.schedulerName = name;
            Main.building.setScheduler(name.equals("SCAN") ? new ScanScheduler() : new NearestCarScheduler());
        });
    }

    private static String handleRandom(Map<String, String> p, int before) {
        final int n = Math.max(1, Math.min(50, intParam(p, "count")));
        return act("handleRandom", "count=" + n, "The browser asked for " + n + " random passengers from the seeded generator.", before,
                () -> Main.addRandom(Main.building, Main.random, n));
    }

    private static String handleReset(int before) {
        Trace.beginAction();
        Main.reset();
        Trace.beginAction();
        Trace.rec("SYSTEM", "Main", "reset", "", "new Building", "A fresh Building and a fresh Random(seed) were created from the current configuration.");
        Main.flushLog();
        return "{\"ok\":true,\"error\":\"\",\"state\":" + stateJson(false) + ",\"events\":" + eventsJson(Trace.after(0)) + "}";
    }

    // ---------- state ----------

    private static String eventJson(TraceEvent e) {
        String[] snip = null;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"id\":").append(e.id).append(",\"action\":").append(e.action).append(",\"tick\":").append(e.tick)
                .append(",\"depth\":").append(e.depth).append(",\"category\":").append(q(e.category))
                .append(",\"cls\":").append(q(e.cls)).append(",\"method\":").append(q(e.method))
                .append(",\"file\":").append(q(Explain.fileOf(e.cls))).append(",\"args\":").append(q(e.args))
                .append(",\"result\":").append(q(e.result)).append(",\"explain\":").append(q(e.explain))
                .append(",\"change\":").append(q(e.change)).append(",\"pid\":").append(e.pid).append(",\"eid\":").append(e.eid)
                .append(",\"floor\":").append(e.floor).append(",\"chosen\":").append(e.chosen);
        if (e.exType != null) {
            sb.append(",\"exType\":").append(q(e.exType)).append(",\"thrownAt\":").append(q(e.thrownAt)).append(",\"caughtIn\":").append(q(e.caughtIn))
                    .append(",\"exCls\":").append(q(e.exCls)).append(",\"exMethod\":").append(q(e.exMethod)).append(",\"exLine\":").append(e.exLine);
        }
        if (e.candidates != null) sb.append(",\"candidates\":").append(e.candidates);
        return sb.append("}").toString();
    }

    static String eventsJson(List<TraceEvent> list) {
        StringBuilder sb = new StringBuilder("[");
        for (TraceEvent e : list) sb.append(sb.length() > 1 ? "," : "").append(eventJson(e));
        return sb.append("]").toString();
    }

    private static String stateJson(boolean withEvents) {
        Building b = Main.building;
        int faults = 0;
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"epoch\":" + Main.epoch + ",\"clock\":" + b.getClock());
        sb.append(",\"floors\":").append(b.getFloors()).append(",\"capacity\":").append(Main.capacity)
                .append(",\"tickMillis\":").append(Main.tickMillis).append(",\"maxTicks\":").append(Main.MAX_TICKS)
                .append(",\"scheduler\":").append(q(b.getSchedulerName())).append(",\"schedulerKey\":").append(q(Main.schedulerName))
                .append(",\"lastReason\":").append(q(b.getLastReason())).append(",\"pending\":").append(b.getPendingCount())
                .append(",\"done\":").append(b.isAllDone()).append(",\"lastEventId\":").append(Trace.lastId()).append(",\"elevators\":[");
        for (Elevator e : b.getElevators()) {
            if (e.getState() == State.FAULT) faults++;
            if (e.getId() > 0) sb.append(",");
            sb.append("{\"id\":").append(e.getId()).append(",\"floor\":").append(e.getFloor()).append(",\"direction\":").append(q("" + e.getDirection()))
                    .append(",\"state\":").append(q("" + e.getState())).append(",\"load\":").append(e.getLoad()).append(",\"capacity\":").append(e.getCapacity())
                    .append(",\"stops\":").append(e.getStops()).append(",\"passengers\":[");
            boolean first = true;
            for (Passenger p : e.getPassengers()) {
                sb.append(first ? "" : ",").append("{\"id\":").append(p.getId()).append(",\"dest\":").append(p.getDestination()).append(",\"weight\":").append(p.getWeight()).append("}");
                first = false;
            }
            sb.append("]}");
        }
        sb.append("],\"faults\":").append(faults).append(",\"waiting\":[");
        for (int f = 0; f < b.getFloors(); f++) {
            if (f > 0) sb.append(",");
            sb.append("[");
            boolean first = true;
            for (Passenger p : b.getWaiting(f)) {
                sb.append(first ? "" : ",").append("{\"id\":").append(p.getId()).append(",\"dest\":").append(p.getDestination()).append(",\"weight\":").append(p.getWeight())
                        .append(",\"elevator\":").append(p.getElevatorId()).append("}");
                first = false;
            }
            sb.append("]");
        }
        sb.append("],\"stats\":").append(statsJson());
        if (withEvents) sb.append(",\"events\":").append(eventsJson(Trace.after(0)));
        return sb.append("}").toString();
    }

    private static String statsJson() {
        Building b = Main.building;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"total\":").append(b.getTotalPassengers()).append(",\"completed\":").append(b.getCompletedCount())
                .append(",\"unfinished\":").append(b.getUnfinishedCount())
                .append(",\"avgWait\":").append(String.format(Locale.ROOT, "%.2f", b.getAverageWait())).append(",\"maxWait\":").append(b.getMaxWait())
                .append(",\"avgTrip\":").append(String.format(Locale.ROOT, "%.2f", b.getAverageTrip())).append(",\"perElevator\":[");
        for (Elevator e : b.getElevators()) {
            if (e.getId() > 0) sb.append(",");
            sb.append("{\"id\":").append(e.getId()).append(",\"utilisation\":").append(String.format(Locale.ROOT, "%.1f", b.getUtilisation(e)))
                    .append(",\"busyTicks\":").append(e.getBusyTicks()).append(",\"floorsTravelled\":").append(e.getFloorsTravelled())
                    .append(",\"served\":").append(e.getServed()).append("}");
        }
        return sb.append("]}").toString();
    }

    // ---------- reports ----------

    private static String reportJson() {
        Main.writeReport();
        return "{\"ok\":true,\"scheduler\":" + q(Main.building.getSchedulerName()) + ",\"clock\":" + Main.building.getClock()
                + ",\"config\":{\"floors\":" + Main.floors + ",\"elevators\":" + Main.elevatorCount + ",\"capacity\":" + Main.capacity
                + ",\"seed\":" + Main.seed + ",\"tickMillis\":" + Main.tickMillis + ",\"port\":" + Main.port + ",\"scheduler\":" + q(Main.schedulerName)
                + "},\"stats\":" + statsJson() + ",\"text\":" + q(Main.reportText()) + "}";
    }

    // ---------- scheduler views ----------

    // Runs both algorithms on the SAME seeded passengers and returns each one's statistics.
    private static String compareJson(int count) {
        if (count < 1 || count > 200) throw new IllegalArgumentException("count must be 1 to 200");
        Trace.setEnabled(false); // these are throw-away buildings, they must not pollute the real trace
        try {
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"count\":" + count + ",\"seed\":" + Main.seed + ",\"runs\":[");
            String[] names = { "NEAREST", "SCAN" };
            for (int i = 0; i < names.length; i++) {
                Building b = Main.newBuilding(names[i]);
                Main.addRandom(b, new Random(Main.seed), count);
                int ticks = 0;
                while (!b.isAllDone() && ticks < Main.MAX_TICKS) { b.tick(); ticks++; }
                sb.append(i > 0 ? "," : "").append("{\"name\":").append(q(names[i])).append(",\"scheduler\":").append(q(b.getSchedulerName()))
                        .append(",\"ticks\":").append(ticks).append(",\"completed\":").append(b.getCompletedCount())
                        .append(",\"unfinished\":").append(b.getUnfinishedCount())
                        .append(",\"avgWait\":").append(String.format(Locale.ROOT, "%.2f", b.getAverageWait()))
                        .append(",\"maxWait\":").append(b.getMaxWait())
                        .append(",\"avgTrip\":").append(String.format(Locale.ROOT, "%.2f", b.getAverageTrip())).append("}");
            }
            return sb.append("]}").toString();
        } finally {
            Trace.setEnabled(true);
        }
    }

    // Asks BOTH schedulers about one hypothetical hall call on the CURRENT elevators. Nothing changes.
    private static String whatIfJson(int floor, String dir) {
        Building b = Main.building;
        if (floor < 0 || floor >= b.getFloors()) throw new IllegalArgumentException("floor must be 0 to " + (b.getFloors() - 1));
        Direction d = "DOWN".equalsIgnoreCase(dir) ? Direction.DOWN : Direction.UP;
        Trace.setEnabled(false);
        try {
            ExternalRequest r = new ExternalRequest(0, floor, b.getClock(), d);
            Scheduler[] all = { new NearestCarScheduler(), new ScanScheduler() };
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"floor\":" + floor + ",\"direction\":" + q("" + d) + ",\"results\":[");
            for (int i = 0; i < all.length; i++) {
                Elevator e = all[i].selectElevator(r, b.getElevators());
                sb.append(i > 0 ? "," : "").append("{\"scheduler\":").append(q(all[i].getClass().getSimpleName()))
                        .append(",\"chosen\":").append(e == null ? -1 : e.getId()).append(",\"reason\":").append(q(all[i].getReason()))
                        .append(",\"candidates\":").append(all[i].explain(r, b.getElevators())).append("}");
            }
            return sb.append("]}").toString();
        } finally {
            Trace.setEnabled(true);
        }
    }

    // ---------- configuration ----------

    private static String configJson() throws IOException {
        Properties p = new Properties();
        try (FileReader r = new FileReader("config.txt")) { p.load(r); }
        List<String> keys = new ArrayList<String>(new TreeSet<String>(p.stringPropertyNames()));
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"props\":[");
        boolean first = true;
        for (String k : keys) {
            String[] m = null;
            for (String[] row : Explain.CONFIG) if (row[0].equals(k)) m = row;
            sb.append(first ? "" : ",").append("{\"key\":").append(q(k)).append(",\"value\":").append(q(p.getProperty(k).trim()));
            if (m != null) {
                sb.append(",\"meaning\":").append(q(m[1])).append(",\"readerClass\":").append(q(m[2])).append(",\"readerMethod\":").append(q(m[3]))
                        .append(",\"usedClass\":").append(q(m[4])).append(",\"usedMethod\":").append(q(m[5])).append(",\"effect\":").append(q(m[6]));
            } else {
                sb.append(",\"meaning\":").append(q("Not used by the Java code (ignored).")).append(",\"effect\":").append(q("None."));
            }
            sb.append("}");
            first = false;
        }
        return sb.append("],\"running\":{\"floors\":" + Main.floors + ",\"elevators\":" + Main.elevatorCount + ",\"capacity\":" + Main.capacity
                + ",\"tickMillis\":" + Main.tickMillis + ",\"scheduler\":" + q(Main.schedulerName) + ",\"seed\":" + Main.seed + ",\"port\":" + Main.port + "}}").toString();
    }

    private static int ranged(Map<String, String> p, String key, int lo, int hi) {
        int v = intParam(p, key);
        if (v < lo || v > hi) throw new IllegalArgumentException(key + " must be between " + lo + " and " + hi);
        return v;
    }

    private static String handleConfig(Map<String, String> p) {
        int f = ranged(p, "floors", 2, 30), e = ranged(p, "elevators", 1, 6), c = ranged(p, "capacity", 1, 5000);
        int t = ranged(p, "tickMillis", 50, 5000), po = ranged(p, "port", 1024, 65535), sd = intParam(p, "seed");
        String s = String.valueOf(p.get("scheduler")).toUpperCase();
        if (!s.equals("NEAREST") && !s.equals("SCAN")) throw new IllegalArgumentException("scheduler must be NEAREST or SCAN");
        List<String> changed = new ArrayList<String>();
        if (f != Main.floors) changed.add("floors " + Main.floors + " -> " + f);
        if (e != Main.elevatorCount) changed.add("elevators " + Main.elevatorCount + " -> " + e);
        if (c != Main.capacity) changed.add("capacity " + Main.capacity + " -> " + c);
        if (t != Main.tickMillis) changed.add("tickMillis " + Main.tickMillis + " -> " + t);
        if (!s.equals(Main.schedulerName)) changed.add("scheduler " + Main.schedulerName + " -> " + s);
        if (sd != Main.seed) changed.add("seed " + Main.seed + " -> " + sd);
        boolean portChanged = po != Main.port;
        if (portChanged) changed.add("port " + Main.port + " -> " + po + " (takes effect after restart)");
        Trace.beginAction();
        Main.writeConfig(f, e, c, t, s, sd, po);
        int keepPort = Main.port;
        Main.loadConfig();
        Main.port = keepPort; // the running server keeps its port until restart
        Main.reset();
        Trace.beginAction();
        TraceEvent ev = Trace.in("SYSTEM", "WebServer", "handleConfig", "floors=" + f + ", elevators=" + e + ", capacity=" + c, "The browser applied new settings. They are validated here, saved to config.txt, then Main re-reads the file.");
        Trace.rec("SYSTEM", "Main", "writeConfig", "", "config.txt saved", "The values were written to config.txt.");
        Trace.rec("SYSTEM", "Main", "loadConfig", "", "fields updated", "Main read config.txt again and updated its fields.");
        Trace.rec("SYSTEM", "Main", "reset", "", "new Building", "A fresh Building was created with the new settings.");
        ev.result = changed.isEmpty() ? "nothing changed" : "saved";
        Trace.out();
        Main.flushLog();
        return "{\"ok\":true,\"changed\":" + arr(changed) + ",\"portChanged\":" + portChanged + ",\"state\":" + stateJson(false)
                + ",\"events\":" + eventsJson(Trace.after(0)) + "}";
    }

    // ---------- source ----------

    private static String sourceJson(String cls, String method) {
        if (cls == null || !cls.matches("\\w+")) throw new IllegalArgumentException("cls is required");
        String[] s = Explain.snippet(cls, method);
        if (s == null) return "{\"ok\":false,\"error\":" + q("No source found for " + cls + (method == null ? "" : "." + method)) + "}";
        return "{\"ok\":true,\"cls\":" + q(cls) + ",\"method\":" + q(method) + ",\"file\":" + q(s[0]) + ",\"start\":" + s[1] + ",\"end\":" + s[2] + ",\"code\":" + q(s[3]) + "}";
    }
}
