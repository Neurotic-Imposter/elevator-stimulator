// One passenger and the times needed for the statistics.
class Passenger {
    private int id;
    private int weight;
    private int source;
    private int destination;
    private int arrivalTime;
    private int boardTime = -1;
    private int finishTime = -1;
    private int elevatorId = -1; // elevator that will pick this passenger up, -1 = none yet

    public Passenger(int id, int weight, int source, int destination, int arrivalTime) {
        this.id = id;
        this.weight = weight;
        this.source = source;
        this.destination = destination;
        this.arrivalTime = arrivalTime;
    }

    public int getId() { return id; }
    public int getWeight() { return weight; }
    public int getSource() { return source; }
    public int getDestination() { return destination; }
    public int getElevatorId() { return elevatorId; }
    public void setElevatorId(int elevatorId) { this.elevatorId = elevatorId; }
    public void setBoardTime(int time) { boardTime = time; }
    public void setFinishTime(int time) { finishTime = time; }

    public Direction getDirection() {
        return destination > source ? Direction.UP : Direction.DOWN;
    }

    // Waiting Time = Board Time - Arrival Time
    public int getWaitTime() { return boardTime - arrivalTime; }

    // Trip Time = Finish Time - Board Time
    public int getTripTime() { return finishTime - boardTime; }
}
