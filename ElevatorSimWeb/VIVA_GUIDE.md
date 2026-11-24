# Viva Guide — Elevator Simulator (Web Edition)

## 1. One-minute explanation
"The Java program is the simulator. A small built-in web server lets the browser press buttons. Each button calls a Java method.
Java also records every method it runs (TraceEvent), and the browser draws that record. The browser never decides anything."

## 2. Classes and responsibilities
| Class | Job |
|---|---|
| Main | Reads config.txt, owns the Building, writes log/report, starts the server |
| Building | Whole model: elevators, waiting queues, pending requests, `tick()` |
| Elevator | One car: floor, direction, state, load, stops; refuses commands when faulty |
| Passenger | Weight, source, destination, times for statistics |
| Request (abstract) / ExternalRequest / InternalRequest | Hall call (UP/DOWN button) and cabin button |
| Scheduler (interface), NearestCarScheduler, ScanScheduler | Choose a car; `explain()` gives the reasons |
| InvalidFloorException, OverloadException (checked), ElevatorFaultException (unchecked) | Error cases |
| Trace, TraceEvent | Record real method calls |
| Explain | Reflection, source snippets, config help |
| WebServer | HTTP bridge, JSON |

## 3. OOP concepts (with where)
* **Encapsulation**: private fields of Elevator; use `addStop()`, `move()` so the fault check always runs (`Elevator.checkFault`).
* **Abstraction**: Building talks to `Scheduler`, not to a specific algorithm. `Request` is abstract.
* **Inheritance**: ExternalRequest, InternalRequest extend Request (watch `Request.Request` run first in the trace). Exceptions extend Exception / RuntimeException.
* **Polymorphism**: `scheduler.selectElevator(...)` behaves differently for NearestCar and SCAN. Switch in the header and repeat.
* **Interface**: `Scheduler`. **Composition**: Building has Elevators, Passengers, Requests.
* **Exception handling**: checked (must handle) vs unchecked. `WebServer.act()` catches them; the browser shows the thrown and caught lines.

## 4. Flow of "Add passenger 2 -> 8" (real order, see Execution Flow tab)
`WebServer.handlePassenger` -> `Building.addPassenger` -> `validateFloor` x2 -> `Passenger` ctor -> `Request` ctor -> `ExternalRequest` ctor.
Next tick: `Building.tick` -> `NearestCarScheduler.selectElevator` -> `Elevator.addStop` -> `Elevator.startMoving`; later `moveElevator` -> `Elevator.move` -> `openDoors`;
then `serveDoors` -> `boardPassenger` -> `Elevator.board` -> `InternalRequest` ctor -> `addStop(8)` -> `closeDoors`.
One tick = one simulated second; the doors are open for exactly one tick.

## 5. Schedulers
* **Nearest-Car**: skip faulty cars; smallest `|car floor - call floor|`; tie -> car already heading the call direction; tie -> lowest id.
* **SCAN/LOOK**: prefer a moving car in the call direction with the floor still ahead ("sweeping"); else the nearest idle car. Cars keep direction while stops remain ahead, then reverse (`Elevator.chooseDirection`).
* Compare tab: both run on the same seeded passengers, so the comparison is fair.

## 6. TraceEvent design
Fields: id, action (one per browser request), tick, depth (nesting), category, class, method, args, result, explanation, state change, passenger/elevator/floor ids,
exception type + thrown-at + caught-in, scheduler candidates. `Trace.in()` / `Trace.out()` raise and lower depth; the result defaults to "threw an exception" and is overwritten on success, so a throw is recorded honestly.

## 7. Threading model (from the source)
Single-threaded engine. `HttpServer` runs on a one-thread executor, so two requests never run together; no `synchronized` is needed.
The original project had sequential/multithreaded variants; this one is sequential. Elevators are not simultaneous: `tick()` loops over them.

## 8. Likely questions
* *Why unchecked ElevatorFaultException?* A programming/state error, not something every caller can recover from.
* *Why interface for Scheduler?* New algorithm = new class, Building unchanged.
* *Is the animation fake?* No. Cabin position = `Elevator.floor` from JSON; doors open only when state is DOORS_OPEN or a real board/exit event arrived.
* *Where is wait time computed?* `Passenger.getWaitTime()` = board time - arrival time; trip = finish - board.
