// Base class for all requests: who asked, which floor, and when.
abstract class Request {
    private int id;
    private int floor;
    private int time;

    public Request(int id, int floor, int time) {
        this.id = id;
        this.floor = floor;
        this.time = time;
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
    }

    public Direction getDirection() { return direction; }
}

// Cabin request: created when a passenger boards and presses a destination button.
class InternalRequest extends Request {
    private int destination;

    public InternalRequest(int id, int floor, int time, int destination) {
        super(id, floor, time);
        this.destination = destination;
    }

    public int getDestination() { return destination; }
}
