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

    // Every command to a faulty elevator is refused.
    private void checkFault() {
        if (state == State.FAULT) {
            throw new ElevatorFaultException("Elevator " + id + " is faulty");
        }
    }

    public void addStop(int f) {
        checkFault();
        if (!stops.contains(f)) {
            stops.add(f);
            Collections.sort(stops);
        }
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
    }

    public void move() {
        checkFault();
        if (direction == Direction.UP) floor++;
        else if (direction == Direction.DOWN) floor--;
    }

    public void openDoors() {
        checkFault();
        stops.remove(Integer.valueOf(floor)); // the stop here is now served
        state = State.DOORS_OPEN;
    }

    public void closeDoors() {
        checkFault();
        stops.remove(Integer.valueOf(floor)); // a stop added while the doors were open
        if (stops.isEmpty()) {
            state = State.IDLE;
            direction = Direction.NONE;
        } else {
            startMoving();
        }
    }

    public void board(Passenger p) throws OverloadException {
        checkFault();
        if (load + p.getWeight() > capacity) {
            throw new OverloadException("Passenger " + p.getId() + " (" + p.getWeight() + " kg) does not fit in E"
                    + id + ", load " + load + "/" + capacity);
        }
        passengers.add(p);
        load += p.getWeight();
    }

    public void exit(Passenger p) {
        checkFault();
        passengers.remove(p);
        load -= p.getWeight();
    }

    // Passengers inside stay inside, so their destinations stay as stops.
    public void breakDown() {
        checkFault();
        stops.clear();
        for (Passenger p : passengers) {
            addStop(p.getDestination());
        }
        state = State.FAULT;
        direction = Direction.NONE;
    }

    public void repair() {
        if (state != State.FAULT) {
            throw new ElevatorFaultException("Elevator " + id + " is not faulty");
        }
        state = State.IDLE;
        direction = Direction.NONE;
    }
}
