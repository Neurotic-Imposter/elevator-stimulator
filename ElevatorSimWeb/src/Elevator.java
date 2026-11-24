import java.util.ArrayList;
import java.util.Collections;

enum Direction { UP, DOWN, NONE }

enum State { IDLE, MOVING, DOORS_OPEN, FAULT }

// One elevator car. It only changes its own data; Building decides what happens when.
class Elevator {
    private int id;
    private int floor = 0;
    private Direction direction = Direction.NONE;
    private State state = State.IDLE;
    private int capacity;
    private int load = 0;
    private int busyTicks = 0;
    private int floorsTravelled = 0;
    private int served = 0;
    private ArrayList<Passenger> passengers = new ArrayList<Passenger>();
    private ArrayList<Integer> stops = new ArrayList<Integer>(); // sorted, no duplicates

    public Elevator(int id, int capacity) {
        this.id = id;
        this.capacity = capacity;
    }

    public int getId() { return id; }
    public int getFloor() { return floor; }
    public Direction getDirection() { return direction; }
    public State getState() { return state; }
    public int getCapacity() { return capacity; }
    public int getLoad() { return load; }
    public int getBusyTicks() { return busyTicks; }
    public ArrayList<Passenger> getPassengers() { return passengers; }
    public ArrayList<Integer> getStops() { return stops; }
    public boolean hasStop(int f) { return stops.contains(f); }
    public void addBusyTick() { busyTicks++; }
    public int getFloorsTravelled() { return floorsTravelled; }
    public int getServed() { return served; }

    // Every command to a faulty elevator is refused.
    private void checkFault() {
        if (state == State.FAULT) {
            Trace.rec("FAULT", "Elevator", "checkFault", "E" + id, "refused", "E" + id + " is faulty, so it refuses every command.").who(-1, id, floor);
            throw new ElevatorFaultException("Elevator " + id + " is faulty");
        }
    }

    public void addStop(int f) {
        checkFault();
        boolean added = !stops.contains(f);
        if (added) {
            stops.add(f);
            Collections.sort(stops);
        }
        Trace.rec("REQUEST", "Elevator", "addStop", "floor=" + f, added ? "added" : "already in list",
                added ? "E" + id + " remembers floor " + f + " as a stop. Stops now " + stops + "." : "Floor " + f + " was already a stop of E" + id + ", so nothing changed.")
                .who(-1, id, f).change("E" + id + ".stops = " + stops);
    }

    public boolean hasStopAbove() {
        for (int s : stops) {
            if (s > floor) return true;
        }
        return false;
    }

    public boolean hasStopBelow() {
        for (int s : stops) {
            if (s < floor) return true;
        }
        return false;
    }

    // Keep going while stops remain ahead, otherwise reverse (LOOK behaviour).
    public void chooseDirection() {
        if (direction == Direction.UP && hasStopAbove()) return;
        if (direction == Direction.DOWN && hasStopBelow()) return;
        if (hasStopAbove()) direction = Direction.UP;
        else if (hasStopBelow()) direction = Direction.DOWN;
        else direction = Direction.NONE;
    }

    public void startMoving() {
        checkFault();
        chooseDirection();
        state = State.MOVING;
        Trace.rec("MOVEMENT", "Elevator", "startMoving", "", "state MOVING, direction " + direction,
                "E" + id + " has stops, so it chooses a direction and starts moving.").who(-1, id, floor).change("E" + id + ".state IDLE -> MOVING");
    }

    public void move() {
        checkFault();
        int from = floor;
        if (direction == Direction.UP) floor++;
        else if (direction == Direction.DOWN) floor--;
        floorsTravelled += Math.abs(floor - from);
        Trace.rec("MOVEMENT", "Elevator", "move", "direction=" + direction, "floor " + floor,
                "E" + id + " moves one floor " + direction + ".").who(-1, id, floor).change("E" + id + ".floor " + from + " -> " + floor);
    }

    public void openDoors() {
        checkFault();
        stops.remove(Integer.valueOf(floor)); // the stop here is now served
        state = State.DOORS_OPEN;
        Trace.rec("DOORS", "Elevator", "openDoors", "", "doors open at floor " + floor,
                "E" + id + " reached a stop. The stop is removed from the list and the doors open.").who(-1, id, floor).change("E" + id + ".state -> DOORS_OPEN");
    }

    public void closeDoors() {
        checkFault();
        TraceEvent ev = Trace.in("DOORS", "Elevator", "closeDoors", "", "Doors close. If stops remain the car moves on, otherwise it becomes idle.");
        try {
            stops.remove(Integer.valueOf(floor)); // a stop added while the doors were open
            if (stops.isEmpty()) {
                state = State.IDLE;
                direction = Direction.NONE;
            } else {
                startMoving();
            }
            ev.result = "state " + state;
            ev.who(-1, id, floor).change("E" + id + ".state -> " + state);
        } finally {
            Trace.out();
        }
    }

    public void board(Passenger p) throws OverloadException {
        checkFault();
        TraceEvent ev = Trace.rec("PASSENGER", "Elevator", "board", "P" + p.getId() + " (" + p.getWeight() + " kg)", Trace.THREW,
                "Checks load + weight against capacity. Too heavy throws OverloadException, otherwise the passenger is added.").who(p.getId(), id, floor);
        if (load + p.getWeight() > capacity) {
            throw new OverloadException("Passenger " + p.getId() + " (" + p.getWeight() + " kg) does not fit in E"
                    + id + ", load " + load + "/" + capacity);
        }
        passengers.add(p);
        load += p.getWeight();
        ev.result = "boarded, load " + load + "/" + capacity;
        ev.change("E" + id + ".load " + (load - p.getWeight()) + " -> " + load);
    }

    public void exit(Passenger p) {
        checkFault();
        passengers.remove(p);
        load -= p.getWeight();
        served++;
        Trace.rec("PASSENGER", "Elevator", "exit", "P" + p.getId(), "left, load " + load + "/" + capacity,
                "P" + p.getId() + " reached the destination floor and leaves the car.").who(p.getId(), id, floor).change("E" + id + ".load " + (load + p.getWeight()) + " -> " + load);
    }

    // Passengers inside stay inside, so their destinations stay as stops.
    public void breakDown() {
        checkFault();
        TraceEvent ev = Trace.in("FAULT", "Elevator", "breakDown", "", "The car stops. Its stop list is rebuilt from the passengers still inside.");
        try {
            stops.clear();
            for (Passenger p : passengers) {
                addStop(p.getDestination());
            }
            state = State.FAULT;
            direction = Direction.NONE;
            ev.result = "state FAULT";
            ev.who(-1, id, floor).change("E" + id + ".state -> FAULT");
        } finally {
            Trace.out();
        }
    }

    public void repair() {
        if (state != State.FAULT) {
            throw new ElevatorFaultException("Elevator " + id + " is not faulty");
        }
        state = State.IDLE;
        direction = Direction.NONE;
        Trace.rec("FAULT", "Elevator", "repair", "", "state IDLE", "E" + id + " is repaired and can take requests again.")
                .who(-1, id, floor).change("E" + id + ".state FAULT -> IDLE");
    }
}
