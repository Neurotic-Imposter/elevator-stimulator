# Elevator Simulator — Web Edition

A browser view of the real Java elevator simulator. Java is the **only** simulation engine; the browser only sends
commands and draws the state and the events that Java records. The original `ElevatorSim/` project is untouched.

## Run (Windows)
1. Install a **JDK 8 or newer** (https://adoptium.net). A JRE alone is not enough: you need `javac`.
2. Double-click **`run.bat`**. It checks Java, compiles `src\*.java` into `out\`, starts the server and opens
   `http://localhost:8080/`. Close the window to stop.

Manual run (any OS): `javac -d out src/*.java && java -cp out Main`, then open http://localhost:8080/

## What you see
| Tab | Shows |
|---|---|
| Simulation | Glass building (default 10 floors x 3 shafts), controls, live Java method calls, event timeline, elevator table, simulation.log |
| Execution Flow | Every browser action and the real method calls it caused. Click a step for file, input, result, explanation, state change, Java source |
| Configuration | Every property in `config.txt`, which class reads it, effect; validated editing |
| Algorithms | Last real scheduler decision, what-if for both schedulers, same-seed comparison, scheduler source |
| Class Architecture | Diagram built by Java reflection; fields, methods, relationships, OOP concepts |
| Reports | Real statistics, per-elevator table, `report.txt` download |

Quick try: **Add passenger** (or *Demo: floor 2 -> 8*), then **Run**. Watch the cabin move, the doors open, the passenger board, and the
right panel list `Building.addPassenger -> Passenger -> ExternalRequest -> Scheduler.selectElevator -> Elevator.addStop ...`.
*Test invalid floor* and *Test overload* show real exceptions with the real thrown/caught lines.

## Architecture
```
Browser (web/index.html, style.css, app.js)  --HTTP/JSON-->  WebServer  -->  Building -> Elevator / Scheduler / Passenger / Request
                                                                  |             |
                                                              Explain        Trace (records TraceEvent while the methods run)
```
* `Main` reads `config.txt`, owns the one `Building`, writes `simulation.log` / `report.txt`, starts `WebServer`.
* `Trace` / `TraceEvent`: engine methods call `Trace.rec(...)` as they execute. The timeline and Execution Flow are this real log.
  Nothing is scripted. Exceptions use the exception's own stack trace for the thrown line.
* `Explain`: reflection data, source snippets (reads `src/*.java`), config help text.
* `Scheduler` has `explain()`; both algorithms report why each car was or was not chosen. No scheduling is done in JavaScript.
* **Threading:** the engine is single-threaded. `WebServer` uses a one-thread executor, so requests run one at a time; no locks.
  Elevators are updated one after another in `Building.tick()`, not in parallel. No thread events exist, so there is no thread filter.
* The ASCII terminal animation and menu were removed. The `animation` config key no longer exists.

## API (all under `/api/`, params in the query string)
| Method + path | Purpose |
|---|---|
| GET `state` | Full state + all events |  GET `ping` | cheap heartbeat |
| POST `passenger?source&dest&weight` | `Building.addPassenger` |
| POST `step?n` | `Building.tick` n times (max 100) |
| POST `fault?id&action=break\|repair` | break / repair an elevator |
| POST `stop?id&floor` | manual stop command (refused by a faulty car) |
| POST `scheduler?name=NEAREST\|SCAN` | switch algorithm live |
| POST `random?count` | seeded random passengers |
| POST `reset` | new Building from current config |
| GET / POST `config` | read / validate+save+reset |
| GET `whatif?floor&dir`, `compare?count` | scheduler views (do not touch the live simulation) |
| GET `classes`, `source?cls&method` | reflection, source snippet |
| GET `report`, `report.txt`, `log.txt` | statistics and downloads |
| POST `shutdown` | stop the server |

Errors: invalid input gives HTTP 400 `{ok:false,error}`. Engine refusals (invalid floor, overload, faulty car) give `ok:false` with the new state and an EXCEPTION event.

## Configuration (`config.txt`)
`floors` (2-30) · `elevators` (1-6) · `capacity` kg · `tickMillis` (browser delay between ticks) · `scheduler` NEAREST|SCAN · `seed` · `port` (restart needed).
Floors are numbered from 0. Elevators start at floor 0.

## Troubleshooting
* *"javac not found"*: install a JDK, not only a JRE.
* *Port in use*: change `port` in `config.txt`.
* *Red banner "Cannot reach the Java server"*: `run.bat` was closed; start it again, the page reconnects.
* Browser did not open: open the address printed in the console.

## Known limitations
* One simulation shared by all open tabs. Ports below 1024 refused. Max 4000 trace events kept.
* Scheduler switched live is not written to `config.txt` (only the Apply button writes it).
* Tested with Chromium (Playwright) on Linux with JDK 21 compiled using `--release 8`. Not run on Windows or Java 8 itself.
