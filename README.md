# Elevator Simulator (Java OOP)

## Objective

A terminal simulation of a building with several elevators. Passengers call an elevator, a scheduler chooses a car, and the cars move, open doors, board and drop passengers. The project demonstrates object-oriented design in Java: classes, inheritance, interfaces, polymorphism, encapsulation and exceptions.

## Features

- Several elevators, configurable floors, elevators and capacity.
- Passengers added by hand (source, destination, weight) or randomly with a seed.
- UP and DOWN hall calls. Each boarding creates a cabin request for the destination.
- Two schedulers: Nearest-Car and SCAN/LOOK, chosen in `config.txt`.
- Weight capacity: a passenger heavier than the capacity is rejected. If a passenger does not fit at boarding, the passenger keeps waiting and asks again.
- Elevator fault and repair. Passengers inside stay inside, waiting passengers are reassigned.
- ASCII building display with optional animation, plus trace lines such as `move(E1) -> floor 3 UP`.
- `simulation.log` (all events) and `report.txt` (written when you exit).
- A comparison mode that runs both schedulers on the same seeded passengers.

## Compile and run

Needs JDK 8 or newer. Run from the `ElevatorSim` folder.

```
cd ElevatorSim
javac --release 8 -d out src/*.java
java -cp out Main
```

(`javac -d out src/*.java` also works on any JDK 8+.)

## Configuration (`config.txt`)

| Key | Meaning | Default |
|-----|---------|---------|
| floors | number of floors, numbered 0 to floors-1 (0 = ground) | 10 |
| elevators | number of elevators, E0, E1, ... | 3 |
| capacity | weight limit of each elevator in kg | 600 |
| tickMillis | pause per tick in milliseconds (animation only) | 300 |
| scheduler | `NEAREST` or `SCAN` | NEAREST |
| animation | `on` redraws the screen every tick, `off` prints only trace lines | on |
| seed | seed for random passengers | 42 |

If `config.txt` is missing it is created with these defaults. Values that are not numbers fall back to the defaults. Very small values are raised to the smallest valid value (at least 2 floors, 1 elevator).

## Menu

1 add a passenger, 2 break or repair an elevator, 3 run 10 ticks, 4 run until all done (at most 500 ticks), 5 add N random passengers, 6 compare Nearest-Car and SCAN (same seed, fresh building each time), 0 exit and write the report.

Display symbols: `[0^]` up, `[0v]` down, `[0-]` idle, `[0O]` doors open, `[0X]` fault.

## Files and class responsibilities

| File | Classes | Job |
|------|---------|-----|
| Main | Main | Menu, input, config, ASCII display and animation, prints trace lines, writes `simulation.log` and `report.txt` |
| Building | Building | Elevators, waiting lists, pending requests, clock, `tick()`, request assignment, statistics. No printing and no file I/O |
| Elevator | Elevator, Direction, State | One car: floor, direction, state, load, passengers, sorted stop list, move, doors, board, exit, break, repair |
| Passenger | Passenger | Weight, source, destination, arrival, board and finish times |
| Request | Request, ExternalRequest, InternalRequest | Abstract base, hall call (has a direction), cabin request (has a destination) |
| Scheduler | Scheduler, NearestCarScheduler, ScanScheduler | Chooses the elevator for a hall call |
| Exceptions | InvalidFloorException, OverloadException, ElevatorFaultException | Error cases |

## How a tick works (1 tick = 1 simulated second)

1. Each pending request is given to an elevator by the active scheduler. If the scheduler returns null, the request stays pending and is retried next tick.
2. Each elevator is updated by its state. FAULT does nothing. IDLE with stops chooses a direction. MOVING moves one floor and opens the doors if there is a stop. DOORS_OPEN lasts one tick: passengers exit, assigned passengers board (capacity checked), the doors close.
3. Busy ticks are counted (MOVING and DOORS_OPEN).
4. The clock goes up by one.

Statistics: waiting time = board time - arrival time. Trip time = finish time - board time. Utilisation = busy ticks / total ticks.

## Scheduling algorithms

- **Nearest-Car**: ignores faulty elevators and picks the smallest distance to the call floor. Tie: the car moving in the call direction. Tie: lowest id. If no elevator works, the request waits.
- **SCAN/LOOK**: candidates are MOVING elevators going the same way as the call with the call floor strictly ahead; the nearest wins (tie: lowest id). Otherwise the nearest IDLE elevator. Otherwise the request waits. An elevator keeps its direction while stops remain ahead and reverses when none remain.

Example: 10 floors, call at floor 5 UP, E0 at floor 4 moving DOWN, E1 at floor 2 moving UP. Nearest-Car picks E0 (distance 1). SCAN picks E1 (E0 is moving away).

## OOP concepts demonstrated

- Encapsulation: private fields with getters.
- Abstraction and inheritance: abstract `Request` with `ExternalRequest` and `InternalRequest`.
- Interface and polymorphism: `Scheduler` with two interchangeable implementations.
- Composition: a `Building` has elevators, an `Elevator` has passengers.
- Enums: `Direction`, `State`.
- Exceptions: two checked, one unchecked, handled with try/catch.
- File handling: `Properties` for the config, `PrintWriter` for the log and report, try-with-resources.

## Simplifications compared with the synopsis

- No threads. The synopsis plans a clock thread, one thread per elevator and a passenger generator thread. This version uses one sequential `tick()` loop, which is deterministic and easy to test. `Thread.sleep` is used only to slow down the animation.
- ArrayList instead of TreeSet. The stop list is kept sorted and without duplicates by hand.
- Fewer classes. The synopsis lists separate classes for floors, config loading, logging and report writing. Here floors are waiting lists inside `Building`, and config, logging and reports live in `Main`. Several small classes share one file because only `Main` is public.
- Random passengers arrive at the moment you ask for them, not at random future times. Passengers and faults are added between runs, because there are no threads.
- Overload at boarding: the passenger asks again after 2 ticks so the full elevator can leave first. Arrival time is not changed.
- No capacity value is given in the synopsis, so capacity stays configurable (default 600 kg). Floors are numbered from 0.
- Clearing the screen uses an ANSI escape code. Use a terminal that supports it (VS Code terminal, Linux, macOS, Windows 10+ terminal) or set `animation=off`.
