import java.awt.Desktop;
import java.io.*;
import java.net.URI;
import java.util.Properties;
import java.util.Random;

// Entry point. Reads config.txt, owns the one Building, starts WebServer and writes
// simulation.log and report.txt. There is no terminal menu: the browser is the interface.
public class Main {
    static final int MAX_TICKS = 500;

    static int floors = 10;
    static int elevatorCount = 3;
    static int capacity = 600;
    static int tickMillis = 700;
    static int seed = 42;
    static int port = 8080;
    static String schedulerName = "NEAREST";
    static int epoch = 1; // changes on every reset so the browser knows to clear its timeline

    static Building building;
    static Random random;
    private static PrintWriter log;

    public static void main(String[] args) {
        loadConfig();
        try {
            log = new PrintWriter(new FileWriter("simulation.log"));
        } catch (IOException e) {
            System.out.println("Cannot write simulation.log: " + e.getMessage());
        }
        reset();
        try {
            WebServer.start(port);
        } catch (IOException e) {
            System.out.println("Cannot start the web server on port " + port + ": " + e.getMessage());
            System.out.println("Change port in config.txt or close the program that uses it.");
            return;
        }
        String url = "http://localhost:" + port + "/";
        System.out.println("Elevator Simulator running at " + url + "  (Ctrl+C to stop)");
        openBrowser(url);
    }

    // ---------- config ----------

    static void loadConfig() {
        File file = new File("config.txt");
        if (!file.exists()) writeConfig(floors, elevatorCount, capacity, tickMillis, schedulerName, seed, port);
        Properties p = new Properties();
        try (FileReader reader = new FileReader(file)) {
            p.load(reader);
        } catch (IOException e) {
            System.out.println("Cannot read config.txt, using defaults.");
        }
        floors = Math.max(2, readNumber(p, "floors", floors));
        elevatorCount = Math.max(1, readNumber(p, "elevators", elevatorCount));
        capacity = Math.max(1, readNumber(p, "capacity", capacity));
        tickMillis = Math.max(0, readNumber(p, "tickMillis", tickMillis));
        seed = readNumber(p, "seed", seed);
        port = readNumber(p, "port", port);
        schedulerName = p.getProperty("scheduler", schedulerName).trim().toUpperCase();
    }

    private static int readNumber(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "" + fallback).trim());
        } catch (NumberFormatException e) {
            System.out.println("Bad value for " + key + " in config.txt, using " + fallback);
            return fallback;
        }
    }

    static void writeConfig(int f, int e, int c, int t, String s, int sd, int po) {
        try (PrintWriter w = new PrintWriter(new FileWriter("config.txt"))) {
            w.println("floors=" + f + "\nelevators=" + e + "\ncapacity=" + c + "\ntickMillis=" + t);
            w.println("# scheduler: NEAREST or SCAN");
            w.println("scheduler=" + s + "\nseed=" + sd + "\nport=" + po);
        } catch (IOException ex) {
            System.out.println("Cannot write config.txt: " + ex.getMessage());
        }
    }

    // ---------- simulation ----------

    static Building newBuilding(String name) {
        Scheduler s = name.equals("SCAN") ? new ScanScheduler() : new NearestCarScheduler();
        return new Building(floors, elevatorCount, capacity, s);
    }

    static void reset() {
        building = newBuilding(schedulerName);
        random = new Random(seed);
        epoch++;
        Trace.clear();
        Trace.tick = 0;
        logLine("--- simulation reset: " + floors + " floors, " + elevatorCount + " elevators, capacity " + capacity
                + ", " + building.getSchedulerName() + ", seed " + seed + " ---");
    }

    // Same seed gives the same passengers, so two schedulers get the same work.
    static void addRandom(Building b, Random r, int count) {
        for (int i = 0; i < count; i++) {
            int source = r.nextInt(floors);
            int destination = r.nextInt(floors);
            if (destination == source) destination = (destination + 1) % floors;
            try {
                int weight = Math.min(capacity, 50 + r.nextInt(51)); // never above the capacity
                b.addPassenger(source, destination, weight);
            } catch (InvalidFloorException | OverloadException e) {
                System.out.println("Skipped passenger: " + e.getMessage());
            }
        }
    }

    // ---------- log and report ----------

    private static void logLine(String line) {
        if (log == null) return;
        log.println(line);
        log.flush();
    }

    // Copies the building's new text lines to simulation.log, then clears them.
    static void flushLog() {
        for (String line : building.getLines()) logLine(line);
        building.clearLines();
    }

    static String reportText() {
        Building b = building;
        StringBuilder w = new StringBuilder();
        w.append("Elevator Simulator report\n");
        w.append("Scheduler: ").append(b.getSchedulerName()).append("\n");
        w.append("Configuration: floors=").append(floors).append(", elevators=").append(elevatorCount)
                .append(", capacity=").append(capacity).append(", seed=").append(seed).append("\n");
        w.append("Total time: ").append(b.getClock()).append(" s\n");
        w.append("Total passengers: ").append(b.getTotalPassengers()).append("\n");
        w.append("Completed trips: ").append(b.getCompletedCount()).append("\n");
        w.append("Not finished: ").append(b.getUnfinishedCount()).append("\n");
        w.append(String.format("Average wait: %.1f s%n", b.getAverageWait()));
        w.append("Max wait: ").append(b.getMaxWait()).append(" s\n");
        w.append(String.format("Average trip time: %.1f s%n", b.getAverageTrip()));
        w.append("Utilisation (busy ticks / total ticks):\n");
        for (Elevator e : b.getElevators()) {
            w.append(String.format("  E%d: %.1f%% (%d / %d), floors travelled %d, passengers served %d%n", e.getId(),
                    b.getUtilisation(e), e.getBusyTicks(), b.getClock(), e.getFloorsTravelled(), e.getServed()));
        }
        return w.toString();
    }

    static void writeReport() {
        try (PrintWriter w = new PrintWriter(new FileWriter("report.txt"))) {
            w.print(reportText());
        } catch (IOException e) {
            System.out.println("Cannot write report.txt: " + e.getMessage());
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                System.out.println("Open this address in your browser: " + url);
            }
        } catch (Exception | Error e) {
            System.out.println("Open this address in your browser: " + url);
        }
    }
}
