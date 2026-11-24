import java.util.ArrayList;

// The model of the whole building. It has no file I/O and prints nothing:
// events are stored in "lines" and Main prints and clears them.
class Building {
    private int floors;
    private int capacity;
    private int clock = 0;
    private int nextId = 1;
    private Scheduler scheduler;
    private String lastReason = "no assignment yet";
    private ArrayList<Elevator> elevators = new ArrayList<Elevator>();
    private ArrayList<ArrayList<Passenger>> waiting = new ArrayList<ArrayList<Passenger>>();
    private ArrayList<ExternalRequest> pending = new ArrayList<ExternalRequest>();
    private ArrayList<Passenger> finished = new ArrayList<Passenger>();
    private ArrayList<String> lines = new ArrayList<String>();

    public Building(int floors, int elevatorCount, int capacity, Scheduler scheduler) {
        this.floors = floors;
        this.capacity = capacity;
        this.scheduler = scheduler;
        for (int i = 0; i < elevatorCount; i++) {
            elevators.add(new Elevator(i, capacity));
        }
        for (int f = 0; f < floors; f++) {
            waiting.add(new ArrayList<Passenger>());
        }
    }

    public int getFloors() { return floors; }
    public int getClock() { return clock; }
    public String getLastReason() { return lastReason; }
    public String getSchedulerName() { return scheduler.getClass().getSimpleName(); }
    public ArrayList<Elevator> getElevators() { return elevators; }
    public int getWaitingCount(int floor) { return waiting.get(floor).size(); }
    public ArrayList<String> getLines() { return lines; }
    public void clearLines() { lines.clear(); }
    public int getPendingCount() { return pending.size(); }
    public int getTotalPassengers() { return nextId - 1; }
    public ArrayList<Passenger> getWaiting(int floor) { return waiting.get(floor); }

    // Switch the algorithm while the simulation keeps its state.
    public void setScheduler(Scheduler s) {
        Trace.tick = clock;
        scheduler = s;
        lastReason = "no assignment yet";
        Trace.rec("SCHEDULER", "Building", "setScheduler", s.getClass().getSimpleName(), "switched",
                "From now on hall calls are answered by " + s.getClass().getSimpleName() + ". Polymorphism: Building only knows the Scheduler interface.");
        note("scheduler is now " + s.getClass().getSimpleName());
    }

    private void note(String text) {
        lines.add("[t=" + clock + "s] " + text);
    }

    public void validateFloor(int floor) throws InvalidFloorException {
        TraceEvent ev = Trace.rec("SYSTEM", "Building", "validateFloor", "floor=" + floor, Trace.THREW,
                "Checks that the floor number is inside the building (0 to " + (floors - 1) + ").").who(-1, -1, floor);
        if (floor < 0 || floor >= floors) {
            throw new InvalidFloorException("Floor " + floor + " does not exist (valid: 0 to " + (floors - 1) + ")");
        }
        ev.result = "valid";
    }

    public Passenger addPassenger(int source, int destination, int weight)
            throws InvalidFloorException, OverloadException {
        Trace.tick = clock;
        TraceEvent ev = Trace.in("PASSENGER", "Building", "addPassenger", "source=" + source + ", destination=" + destination + ", weight=" + weight,
                "Entry point for a new passenger: validate, create the Passenger, create the hall call.");
        try {
            validateFloor(source);
            validateFloor(destination);
            if (source == destination) {
                throw new InvalidFloorException("Source and destination must be different floors");
            }
            if (weight <= 0 || weight > capacity) {
                throw new OverloadException("Weight " + weight + " kg rejected (allowed: 1 to " + capacity + ")");
            }
            Passenger p = new Passenger(nextId, weight, source, destination, clock);
            nextId++;
            waiting.get(source).add(p);
            pending.add(new ExternalRequest(p.getId(), source, clock, p.getDirection()));
            note("P" + p.getId() + " (" + weight + " kg) waits at floor " + source + " for floor " + destination);
            ev.result = "P" + p.getId() + " waiting";
            ev.who(p.getId(), -1, source).change("floor " + source + " waiting +1, pending requests " + pending.size());
            return p;
        } finally {
            Trace.out();
        }
    }

    public void breakElevator(int id) {
        Trace.tick = clock;
        TraceEvent ev = Trace.in("FAULT", "Building", "breakElevator", "id=" + id, "Breaks one elevator, then re-queues the passengers who were waiting for it.");
        try {
            Elevator e = elevators.get(id);
            e.breakDown(); // throws ElevatorFaultException if it is already faulty
            note("E" + id + " fault at floor " + e.getFloor());
            for (ArrayList<Passenger> list : waiting) {
                for (Passenger p : list) {
                    if (p.getElevatorId() == id) {
                        p.setElevatorId(-1);
                        pending.add(new ExternalRequest(p.getId(), p.getSource(), clock, p.getDirection()));
                    }
                }
            }
            ev.result = "E" + id + " faulty";
            ev.who(-1, id, e.getFloor());
        } finally {
            Trace.out();
        }
    }

    public void repairElevator(int id) {
        Trace.tick = clock;
        TraceEvent ev = Trace.in("FAULT", "Building", "repairElevator", "id=" + id, "Asks one elevator to repair itself.");
        try {
            elevators.get(id).repair();
            ev.result = "E" + id + " repaired";
            ev.who(-1, id, elevators.get(id).getFloor());
            note("E" + id + " repaired");
        } finally {
            Trace.out();
        }
    }

    // Manual command to one elevator. A faulty car refuses it (ElevatorFaultException).
    public void commandStop(int id, int floor) throws InvalidFloorException {
        Trace.tick = clock;
        TraceEvent ev = Trace.in("REQUEST", "Building", "commandStop", "id=" + id + ", floor=" + floor, "Sends a stop command straight to one elevator.");
        try {
            validateFloor(floor);
            elevators.get(id).addStop(floor);
            ev.result = "E" + id + " will stop at " + floor;
            ev.who(-1, id, floor);
            note("command: E" + id + " stop at floor " + floor);
        } finally {
            Trace.out();
        }
    }

    // One simulated second.
    public void tick() {
        Trace.tick = clock;
        TraceEvent ev = Trace.in("SYSTEM", "Building", "tick", "clock=" + clock,
                "One simulated second: answer waiting hall calls, then let every elevator act once.");
        try {
            assignPending();
            for (Elevator e : elevators) {
                updateElevator(e);
            }
            for (Elevator e : elevators) {
                if (e.getState() == State.MOVING || e.getState() == State.DOORS_OPEN) e.addBusyTick();
            }
            clock++;
            ev.result = "clock is now " + clock;
        } finally {
            Trace.out();
        }
    }

    private void assignPending() {
        for (ExternalRequest r : new ArrayList<ExternalRequest>(pending)) {
            if (r.getTime() > clock) continue; // not eligible yet
            Elevator e = scheduler.selectElevator(r, elevators);
            lastReason = scheduler.getReason();
            String args = "request " + r.getId() + ", floor " + r.getFloor() + " " + r.getDirection();
            if (e != null || r.getTime() == clock) {
                TraceEvent ev = Trace.rec("SCHEDULER", scheduler.getClass().getSimpleName(), "selectElevator", args,
                        e == null ? "none" : "E" + e.getId(), lastReason).who(r.getId(), e == null ? -1 : e.getId(), r.getFloor());
                ev.candidates = scheduler.explain(r, elevators);
                ev.chosen = e == null ? -1 : e.getId();
            }
            if (e == null) {
                // Retried every tick, so only the first failed try is traced.
                if (r.getTime() == clock) note("selectElevator(" + args + ") -> none, " + lastReason);
                continue;
            }
            pending.remove(r);
            findWaiting(r).setElevatorId(e.getId());
            e.addStop(r.getFloor());
            note("selectElevator(" + args + ") -> E" + e.getId() + ", " + lastReason);
        }
    }

    private Passenger findWaiting(ExternalRequest r) {
        for (Passenger p : waiting.get(r.getFloor())) {
            if (p.getId() == r.getId()) return p;
        }
        return null;
    }

    private void updateElevator(Elevator e) {
        State s = e.getState();
        if (s == State.IDLE && !e.getStops().isEmpty()) {
            e.startMoving();
        } else if (s == State.MOVING) {
            moveElevator(e);
        } else if (s == State.DOORS_OPEN) {
            serveDoors(e);
        }
    }

    private void moveElevator(Elevator e) {
        TraceEvent ev = Trace.in("MOVEMENT", "Building", "moveElevator", "E" + e.getId(), "A moving car goes one floor, then opens its doors if the floor is a stop.");
        try {
            if (!e.hasStop(e.getFloor())) { // a stop at this very floor needs no move
                e.chooseDirection();
                e.move();
                note("move(E" + e.getId() + ") -> floor " + e.getFloor() + " " + e.getDirection());
            }
            if (e.hasStop(e.getFloor())) {
                e.openDoors();
                note("openDoors(E" + e.getId() + ") -> doors open at floor " + e.getFloor());
            }
            ev.result = "E" + e.getId() + " at floor " + e.getFloor() + ", " + e.getState();
            ev.who(-1, e.getId(), e.getFloor());
        } finally {
            Trace.out();
        }
    }

    // The doors are open for exactly one tick: exit first, then board, then close.
    private void serveDoors(Elevator e) {
        TraceEvent ev = Trace.in("DOORS", "Building", "serveDoors", "E" + e.getId() + " at floor " + e.getFloor(),
                "Doors are open for one tick: passengers exit first, then waiting passengers board, then the doors close.");
        try {
            serveDoorsBody(e);
            ev.result = "E" + e.getId() + " " + e.getState();
            ev.who(-1, e.getId(), e.getFloor());
        } finally {
            Trace.out();
        }
    }

    private void serveDoorsBody(Elevator e) {
        int floor = e.getFloor();
        for (Passenger p : new ArrayList<Passenger>(e.getPassengers())) {
            if (p.getDestination() == floor) {
                e.exit(p);
                p.setFinishTime(clock);
                finished.add(p);
                note("exit(P" + p.getId() + ") -> left E" + e.getId() + " at floor " + floor
                        + ", trip " + p.getTripTime() + "s");
            }
        }
        for (Passenger p : new ArrayList<Passenger>(waiting.get(floor))) {
            if (p.getElevatorId() == e.getId()) boardPassenger(e, p);
        }
        e.closeDoors();
        note("E" + e.getId() + " doors close at floor " + floor);
    }

    private void boardPassenger(Elevator e, Passenger p) {
        TraceEvent ev = Trace.in("PASSENGER", "Building", "boardPassenger", "P" + p.getId() + " into E" + e.getId(),
                "Tries to put the waiting passenger into the car and creates the cabin request.");
        try {
            e.board(p);
            p.setBoardTime(clock);
            waiting.get(p.getSource()).remove(p);
            InternalRequest ir = new InternalRequest(p.getId(), e.getFloor(), clock, p.getDestination());
            e.addStop(ir.getDestination());
            note("board(P" + p.getId() + ") -> E" + e.getId() + ", load " + e.getLoad() + "/" + e.getCapacity()
                    + ", destination " + ir.getDestination());
            ev.result = "boarded";
            ev.who(p.getId(), e.getId(), e.getFloor());
        } catch (OverloadException ex) {
            Trace.exception(ex, "Building.boardPassenger", "The car was too full. The passenger goes back in the queue and asks again in 2 ticks.");
            ev.result = "overload, waits again";
            p.setElevatorId(-1);
            // Eligible again in 2 ticks, so the full elevator can leave first.
            pending.add(new ExternalRequest(p.getId(), p.getSource(), clock + 2, p.getDirection()));
            note("board(P" + p.getId() + ") -> overload, passenger waits again");
        } finally {
            Trace.out();
        }
    }

    public boolean isAllDone() {
        if (!pending.isEmpty()) return false;
        for (ArrayList<Passenger> list : waiting) {
            if (!list.isEmpty()) return false;
        }
        for (Elevator e : elevators) {
            if (!e.getPassengers().isEmpty()) return false;
        }
        return true;
    }

    // Statistics for the report and the comparison table.
    public int getCompletedCount() { return finished.size(); }
    public int getUnfinishedCount() { return nextId - 1 - finished.size(); }

    public double getAverageWait() {
        if (finished.isEmpty()) return 0;
        int sum = 0;
        for (Passenger p : finished) sum += p.getWaitTime();
        return (double) sum / finished.size();
    }

    public int getMaxWait() {
        int max = 0;
        for (Passenger p : finished) max = Math.max(max, p.getWaitTime());
        return max;
    }

    public double getAverageTrip() {
        if (finished.isEmpty()) return 0;
        int sum = 0;
        for (Passenger p : finished) sum += p.getTripTime();
        return (double) sum / finished.size();
    }

    // Utilisation = busy ticks / total ticks, as a percentage.
    public double getUtilisation(Elevator e) {
        if (clock == 0) return 0;
        return 100.0 * e.getBusyTicks() / clock;
    }
}
