// Base class for all requests: who asked, which floor, and when.
abstract class Request {
    private int id;
    private int floor;
    private int time;

    public Request(int id, int floor, int time) {
        this.id = id;
        this.floor = floor;
        this.time = time;
        Trace.rec("REQUEST", "Request", "Request", "id=" + id + ", floor=" + floor + ", time=" + time, "base fields stored",
                "Superclass constructor. It runs first whenever an ExternalRequest or InternalRequest is created (inheritance).").who(id, -1, floor);
    }

    public int getId() { return id; }
    public int getFloor() { return floor; }
    public int getTime() { return time; }
}

// Hall call: a passenger presses UP or DOWN on a floor.
class ExternalRequest extends Request {
    private Direction direction;

    public ExternalRequest(int id, int floor, int time, Direction direction) {
        super(id, floor, time);
        this.direction = direction;
        Trace.rec("REQUEST", "ExternalRequest", "ExternalRequest", "id=" + id + ", floor=" + floor + ", direction=" + direction,
                "hall call object", "A hall call: the passenger pressed " + direction + " on floor " + floor + ".").who(id, -1, floor);
    }

    public Direction getDirection() { return direction; }
}

// Cabin request: created when a passenger boards and presses a destination button.
class InternalRequest extends Request {
    private int destination;

    public InternalRequest(int id, int floor, int time, int destination) {
        super(id, floor, time);
        this.destination = destination;
        Trace.rec("REQUEST", "InternalRequest", "InternalRequest", "id=" + id + ", floor=" + floor + ", destination=" + destination,
                "cabin button object", "A cabin request: the boarded passenger pressed floor " + destination + ".").who(id, -1, destination);
    }

    public int getDestination() { return destination; }
}
