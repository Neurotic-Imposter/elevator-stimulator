import java.io.*;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Random;
import java.util.Scanner;

// Console front end: menu, input, config, display, simulation.log and report.txt.
public class Main {
    private static final int MAX_TICKS = 500;

    private static int floors = 10;
    private static int elevatorCount = 3;
    private static int capacity = 600;
    private static int tickMillis = 300;
    private static int seed = 42;
    private static String schedulerName = "NEAREST";
    private static boolean animation = true;

    private static Scanner in = new Scanner(System.in);
    private static PrintWriter log;
    private static Building building;
    private static Random random;

    public static void main(String[] args) {
        loadConfig();
        building = newBuilding(schedulerName);
        random = new Random(seed);
        try {
            log = new PrintWriter(new FileWriter("simulation.log"));
            menuLoop();
        } catch (IOException e) {
            System.out.println("Cannot write simulation.log: " + e.getMessage());
        } catch (NoSuchElementException e) {
            System.out.println("Input ended.");
        } finally {
            writeReport();
            if (log != null) log.close();
        }
    }

    // ---------- config ----------

    private static void loadConfig() {
        File file = new File("config.txt");
        if (!file.exists()) writeDefaultConfig(file);
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
        schedulerName = p.getProperty("scheduler", schedulerName).trim().toUpperCase();
        animation = !p.getProperty("animation", "on").trim().equalsIgnoreCase("off");
    }

    private static int readNumber(Properties p, String key, int fallback) {
        try {
            return Integer.parseInt(p.getProperty(key, "" + fallback).trim());
        } catch (NumberFormatException e) {
            System.out.println("Bad value for " + key + " in config.txt, using " + fallback);
            return fallback;
        }
    }

    private static void writeDefaultConfig(File file) {
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.println("floors=10\nelevators=3\ncapacity=600\ntickMillis=300");
            w.println("# scheduler: NEAREST or SCAN");
            w.println("scheduler=NEAREST\nanimation=on\nseed=42");
        } catch (IOException e) {
            System.out.println("Cannot create config.txt, using defaults.");
        }
    }

    private static Building newBuilding(String name) {
        Scheduler s = name.equals("SCAN") ? new ScanScheduler() : new NearestCarScheduler();
        return new Building(floors, elevatorCount, capacity, s);
    }

    // ---------- menu and input ----------

    private static void menuLoop() {
        while (true) {
            drawBuilding();
            System.out.println("\n1 Add passenger\n2 Break / repair an elevator\n3 Run 10 ticks");
            System.out.println("4 Run until all done (max " + MAX_TICKS + " ticks)\n5 Add N random passengers");
            System.out.println("6 Compare Nearest-Car vs SCAN\n0 Exit and write the report");
            int choice = readInt("Choice: ");
            if (choice == 0) return;
            switch (choice) {
                case 1: addPassenger(); break;
                case 2: breakOrRepair(); break;
                case 3: runTicks(10); break;
                case 4: runUntilDone(); break;
                case 5: addRandom(building, random, readInt("How many passengers: ")); break;
                case 6: compare(); break;
                default: System.out.println("Unknown choice.");
            }
            printLines();
        }
    }

    private static int readInt(String prompt) {
        while (true) {
            System.out.print(prompt);
            try {
                return Integer.parseInt(in.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println("Please type a whole number.");
            }
        }
    }

    private static void addPassenger() {
        while (true) {
            int source = readInt("Source floor: ");
            int destination = readInt("Destination floor: ");
            int weight = readInt("Weight in kg: ");
            try {
                building.addPassenger(source, destination, weight);
                return;
            } catch (InvalidFloorException e) {
                System.out.println("Invalid floor: " + e.getMessage() + ". Try again.");
            } catch (OverloadException e) {
                System.out.println("Rejected: " + e.getMessage());
                return;
            }
        }
    }

    private static void breakOrRepair() {
        int id = readInt("Elevator id (0 to " + (elevatorCount - 1) + "): ");
        if (id < 0 || id >= elevatorCount) {
            System.out.println("No such elevator.");
            return;
        }
        System.out.print("b = break, r = repair: ");
        String answer = in.nextLine().trim();
        try {
            if (answer.equalsIgnoreCase("b")) building.breakElevator(id);
            else if (answer.equalsIgnoreCase("r")) building.repairElevator(id);
            else System.out.println("Type b or r.");
        } catch (ElevatorFaultException e) {
            System.out.println("Refused: " + e.getMessage());
        }
    }

    // Same seed gives the same passengers, so the two schedulers get the same work.
    private static void addRandom(Building b, Random r, int count) {
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

    // ---------- running ----------

    private static void runTicks(int count) {
        for (int i = 0; i < count; i++) {
            tickAndShow();
        }
    }

    private static void runUntilDone() {
        int ticks = 0;
        while (!building.isAllDone() && ticks < MAX_TICKS) {
            tickAndShow();
            ticks++;
        }
        if (building.isAllDone()) System.out.println("All passengers done at t=" + building.getClock() + "s.");
        else System.out.println("Stopped after " + MAX_TICKS + " ticks, some passengers are not done.");
    }

    private static void tickAndShow() {
        building.tick();
        if (animation) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            drawBuilding();
        }
        printLines();
        if (animation) {
            try {
                Thread.sleep(tickMillis); // only for animation speed
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // Prints the building's new trace lines, copies them to simulation.log, then clears them.
    private static void printLines() {
        for (String line : building.getLines()) {
            System.out.println(line);
            log.println(line);
        }
        log.flush();
        building.clearLines();
    }

    private static void compare() {
        int count = readInt("Passengers per run: ");
        System.out.println(String.format("%-10s %10s %9s %7s", "Scheduler", "Avg wait", "Max wait", "Trips"));
        String[] names = { "NEAREST", "SCAN" };
        for (String name : names) {
            Building b = newBuilding(name);
            addRandom(b, new Random(seed), count);
            int ticks = 0;
            while (!b.isAllDone() && ticks < MAX_TICKS) {
                b.tick();
                ticks++;
            }
            System.out.println(String.format("%-10s %9.1fs %8ds %7d", name, b.getAverageWait(), b.getMaxWait(),
                    b.getCompletedCount()));
        }
    }

    // ---------- display ----------

    private static void drawBuilding() {
        System.out.println("=== Elevator Simulator ===");
        String header = "Floor ";
        for (Elevator e : building.getElevators()) {
            header += "  E" + e.getId() + "  ";
        }
        System.out.println(header + " Waiting");
        for (int f = building.getFloors() - 1; f >= 0; f--) {
            String row = String.format("%4d  ", f);
            for (Elevator e : building.getElevators()) {
                row += (e.getFloor() == f ? symbol(e) : " .. ") + "  ";
            }
            System.out.println(row + " " + building.getWaitingCount(f));
        }
        System.out.println("t=" + building.getClock() + "s  " + building.getSchedulerName()
                + "  last choice: " + building.getLastReason());
    }

    private static String symbol(Elevator e) {
        String mark = "-";
        if (e.getState() == State.FAULT) mark = "X";
        else if (e.getState() == State.DOORS_OPEN) mark = "O";
        else if (e.getDirection() == Direction.UP) mark = "^";
        else if (e.getDirection() == Direction.DOWN) mark = "v";
        return "[" + e.getId() + mark + "]";
    }

    // ---------- report ----------

    private static void writeReport() {
        try (PrintWriter w = new PrintWriter(new FileWriter("report.txt"))) {
            w.println("Elevator Simulator report");
            w.println("Scheduler: " + building.getSchedulerName());
            w.println("Total time: " + building.getClock() + " s");
            w.println("Completed trips: " + building.getCompletedCount());
            w.println("Not finished: " + building.getUnfinishedCount());
            w.println(String.format("Average wait: %.1f s", building.getAverageWait()));
            w.println("Max wait: " + building.getMaxWait() + " s");
            w.println(String.format("Average trip time: %.1f s", building.getAverageTrip()));
            w.println("Utilisation (busy ticks / total ticks):");
            for (Elevator e : building.getElevators()) {
                w.println(String.format("  E%d: %.1f%% (%d / %d)", e.getId(), building.getUtilisation(e),
                        e.getBusyTicks(), building.getClock()));
            }
            System.out.println("Report written to report.txt");
        } catch (IOException e) {
            System.out.println("Cannot write report.txt: " + e.getMessage());
        }
    }
}
