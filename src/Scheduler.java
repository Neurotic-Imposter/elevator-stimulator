import java.util.List;

// A scheduler picks which elevator answers a hall call. Returns null if none can.
interface Scheduler {
    Elevator selectElevator(ExternalRequest r, List<Elevator> list);

    String getReason();
}

// Nearest-Car: smallest distance wins. Tie: the car already moving in the call direction. Tie: lowest id.
class NearestCarScheduler implements Scheduler {
    private String reason = "";

    public Elevator selectElevator(ExternalRequest r, List<Elevator> list) {
        Elevator best = null;
        for (Elevator e : list) {
            if (e.getState() == State.FAULT) continue;
            if (best == null || isBetter(e, best, r)) best = e;
        }
        if (best == null) {
            reason = "no working elevator";
        } else {
            reason = "E" + best.getId() + " nearest, distance " + distance(best, r);
        }
        return best;
    }

    private int distance(Elevator e, ExternalRequest r) {
        return Math.abs(e.getFloor() - r.getFloor());
    }

    // Lists are scanned in id order, so on a full tie the first (lowest id) car stays.
    private boolean isBetter(Elevator e, Elevator best, ExternalRequest r) {
        if (distance(e, r) != distance(best, r)) {
            return distance(e, r) < distance(best, r);
        }
        boolean eSameWay = e.getDirection() == r.getDirection();
        boolean bestSameWay = best.getDirection() == r.getDirection();
        return eSameWay && !bestSameWay;
    }

    public String getReason() { return reason; }
}

// SCAN/LOOK: prefer a moving car whose sweep will reach the floor, else the nearest idle car.
class ScanScheduler implements Scheduler {
    private String reason = "";

    public Elevator selectElevator(ExternalRequest r, List<Elevator> list) {
        Elevator best = null;
        for (Elevator e : list) {
            if (isSweeping(e, r) && (best == null || distance(e, r) < distance(best, r))) best = e;
        }
        if (best != null) {
            reason = "E" + best.getId() + " sweep covers floor " + r.getFloor() + " going " + r.getDirection();
            return best;
        }
        for (Elevator e : list) {
            if (e.getState() == State.IDLE && (best == null || distance(e, r) < distance(best, r))) best = e;
        }
        if (best != null) {
            reason = "E" + best.getId() + " idle, distance " + distance(best, r);
        } else {
            reason = "no sweeping or idle elevator";
        }
        return best;
    }

    private int distance(Elevator e, ExternalRequest r) {
        return Math.abs(e.getFloor() - r.getFloor());
    }

    // Moving the same way as the call, with the call floor strictly ahead.
    private boolean isSweeping(Elevator e, ExternalRequest r) {
        if (e.getState() != State.MOVING || e.getDirection() != r.getDirection()) return false;
        if (e.getDirection() == Direction.UP) return r.getFloor() > e.getFloor();
        return r.getFloor() < e.getFloor();
    }

    public String getReason() { return reason; }
}
