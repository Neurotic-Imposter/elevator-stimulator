// Custom exceptions. Only Main is public, so these share one file.

// Checked: a floor number is outside the building (or source == destination).
class InvalidFloorException extends Exception {
    public InvalidFloorException(String message) { super(message); }
}

// Checked: a passenger is too heavy for the elevator.
class OverloadException extends Exception {
    public OverloadException(String message) { super(message); }
}

// Unchecked: a command was sent to a faulty elevator (or a repair to a working one).
class ElevatorFaultException extends RuntimeException {
    public ElevatorFaultException(String message) { super(message); }
}
