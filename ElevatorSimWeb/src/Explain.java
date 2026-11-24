import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.*;

// Plain-English explanations. The code facts (fields, methods, relationships, source text) are read
// from the real classes and the real .java files; only the sentences below are written by hand.
class Explain {
    static final String[] CLASSES = { "Main", "WebServer", "Building", "Elevator", "Passenger", "Request",
            "ExternalRequest", "InternalRequest", "Scheduler", "NearestCarScheduler", "ScanScheduler",
            "InvalidFloorException", "OverloadException", "ElevatorFaultException", "TraceEvent", "Trace", "Explain",
            "Direction", "State" };

    private static final Map<String, String> ROLE = new LinkedHashMap<String, String>();
    private static final Map<String, String> OOP = new LinkedHashMap<String, String>();
    static {
        ROLE.put("Main", "Starts the program: reads config.txt, owns the one Building, writes the log and report, starts the web server.");
        ROLE.put("WebServer", "The bridge to the browser. Turns HTTP requests into calls on Building and sends the answers back as JSON.");
        ROLE.put("Building", "The model of the whole building. Owns the elevators, waiting queues and pending requests, and runs one tick of time.");
        ROLE.put("Elevator", "One car. Holds floor, direction, state, load and stops, and changes only its own data.");
        ROLE.put("Passenger", "One person: weight, source, destination and the times needed for wait and trip statistics.");
        ROLE.put("Request", "Abstract base class for all requests: who asked, which floor, when.");
        ROLE.put("ExternalRequest", "A hall call: a passenger pressed UP or DOWN on a floor.");
        ROLE.put("InternalRequest", "A cabin request: a boarded passenger pressed a destination button.");
        ROLE.put("Scheduler", "Interface: any algorithm that picks an elevator for a hall call and can explain its choice.");
        ROLE.put("NearestCarScheduler", "Picks the working elevator with the smallest distance to the call.");
        ROLE.put("ScanScheduler", "Prefers a car already sweeping toward the floor in the call direction, else the nearest idle car.");
        ROLE.put("InvalidFloorException", "Checked exception: a floor is outside the building, or source equals destination.");
        ROLE.put("OverloadException", "Checked exception: a passenger is too heavy for the elevator.");
        ROLE.put("ElevatorFaultException", "Unchecked exception: a command was sent to a faulty elevator.");
        ROLE.put("TraceEvent", "Data holder for one real thing that happened in the engine (class, method, arguments, result, explanation).");
        ROLE.put("Trace", "The recorder. Engine methods call it while they run, so the browser sees real events, not a script.");
        ROLE.put("Explain", "Reads real classes and source files to give the browser class info, source snippets and config help.");
        ROLE.put("Direction", "Enum: UP, DOWN or NONE.");
        ROLE.put("State", "Enum of elevator states: IDLE, MOVING, DOORS_OPEN, FAULT.");

        OOP.put("Encapsulation", "Fields such as Elevator.floor and Elevator.stops are private. Other classes must use methods like addStop() and move(), so Elevator can check for faults first.|Elevator,Passenger,Building");
        OOP.put("Abstraction", "Building asks a Scheduler to pick a car and never needs to know how. Request is abstract: you can only create ExternalRequest or InternalRequest.|Scheduler,Request");
        OOP.put("Inheritance", "ExternalRequest and InternalRequest extend Request, so id, floor and time are written once. The three exceptions extend Exception or RuntimeException.|Request,ExternalRequest,InternalRequest");
        OOP.put("Polymorphism", "Building holds a Scheduler variable. At run time it is a NearestCarScheduler or a ScanScheduler, and the same call selectElevator() behaves differently.|Scheduler,NearestCarScheduler,ScanScheduler");
        OOP.put("Interface", "Scheduler is an interface: a contract with selectElevator(), getReason() and explain(). Each algorithm implements it.|Scheduler");
        OOP.put("Composition", "A Building HAS elevators, waiting passengers and requests. An Elevator HAS passengers and stops. They live and die with their owner.|Building,Elevator");
        OOP.put("Exception handling", "InvalidFloorException and OverloadException are checked, so callers must handle them. ElevatorFaultException is unchecked. WebServer catches them and the browser shows the real throw and catch locations.|InvalidFloorException,OverloadException,ElevatorFaultException");
        OOP.put("Threading", "This engine is single-threaded. WebServer uses a one-thread executor, so requests run one after another and no locks are needed. Elevators do NOT move in parallel: Building.tick() visits them in order.|WebServer,Building");
    }

    // ---------- config ----------
    // key -> { meaning, reader, method, effect }
    static final String[][] CONFIG = {
        { "floors", "Number of floors, numbered 0 to floors-1.", "Main", "loadConfig", "Building", "Building",
          "More floors make a taller building and longer trips. Minimum 2. Changing it resets the simulation." },
        { "elevators", "Number of elevator cars (shafts).", "Main", "loadConfig", "Building", "Building",
          "More cars shorten waits but need more scheduling. Minimum 1. Changing it resets the simulation." },
        { "capacity", "Maximum load of one car in kg.", "Main", "loadConfig", "Elevator", "board",
          "Lower capacity causes overload sooner (OverloadException). Changing it resets the simulation." },
        { "tickMillis", "Browser pause between two ticks while Run is on. The Java engine never sleeps.", "Main", "loadConfig", "WebServer", "stateJson",
          "Smaller is faster animation. It does not change simulation results." },
        { "scheduler", "Starting algorithm: NEAREST or SCAN.", "Main", "newBuilding", "Main", "newBuilding",
          "Selects NearestCarScheduler or ScanScheduler for new simulations. You can also switch live in the header." },
        { "seed", "Seed of the random generator for random passengers.", "Main", "reset", "Main", "addRandom",
          "Same seed gives the same random passengers, so algorithms can be compared fairly." },
        { "port", "Port of the local web server.", "Main", "main", "WebServer", "start",
          "Needs a restart of the Java program. Must be 1024 to 65535." }
    };

    // ---------- source files ----------

    static File srcDir() { return new File("src"); }

    static List<String> readLines(File f) throws java.io.IOException {
        return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
    }

    // Finds which .java file declares a class, interface or enum.
    static String fileOf(String cls) {
        File[] files = srcDir().listFiles();
        if (files == null) return cls + ".java";
        Pattern p = Pattern.compile("^\\s*(?:public\\s+|abstract\\s+|final\\s+)*(?:class|interface|enum)\\s+" + Pattern.quote(cls) + "\\b");
        for (File f : files) {
            if (!f.getName().endsWith(".java")) continue;
            try {
                for (String line : readLines(f)) if (p.matcher(line).find()) return f.getName();
            } catch (java.io.IOException e) { /* try next file */ }
        }
        return cls + ".java";
    }

    private static int[] classRange(List<String> lines, String cls) {
        Pattern p = Pattern.compile("^\\s*(?:public\\s+|abstract\\s+|final\\s+)*(?:class|interface|enum)\\s+" + Pattern.quote(cls) + "\\b");
        for (int i = 0; i < lines.size(); i++) {
            if (p.matcher(lines.get(i)).find()) return new int[] { i, braceEnd(lines, i) };
        }
        return null;
    }

    // Index of the line holding the closing brace that matches the first "{" at or after "from".
    private static int braceEnd(List<String> lines, int from) {
        int depth = 0;
        boolean seen = false;
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i).replaceAll("//.*", "").replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
            for (char c : l.toCharArray()) {
                if (c == '{') { depth++; seen = true; }
                else if (c == '}') { depth--; if (seen && depth == 0) return i; }
            }
            if (!seen && l.contains(";")) return i; // declaration without a body
        }
        return lines.size() - 1;
    }

    // Result: { file, startLine(1-based), endLine, code } or null. method==null gives the whole class.
    static String[] snippet(String cls, String method) {
        try {
            String file = fileOf(cls);
            List<String> lines = readLines(new File(srcDir(), file));
            int[] range = classRange(lines, cls);
            if (range == null) return null;
            int start = range[0], end = range[1];
            if (method != null && !method.isEmpty()) {
                Pattern decl = Pattern.compile("^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized)\\s+)*(?:[\\w<>\\[\\],.?]+\\s+)?"
                        + Pattern.quote(method) + "\\s*\\(");
                int found = -1;
                for (int i = range[0] + 1; i <= range[1]; i++) {
                    String l = lines.get(i);
                    if (l.trim().matches("^(return|else|if|for|while|new|throw|case)\\b.*")) continue;
                    if (decl.matcher(l).find() && !l.contains("=")) { found = i; break; }
                }
                if (found < 0) return null;
                start = found;
                end = braceEnd(lines, found);
                while (start > 0 && lines.get(start - 1).trim().startsWith("//")) start--; // keep the comment above
            }
            StringBuilder sb = new StringBuilder();
            for (int i = start; i <= end; i++) sb.append(lines.get(i)).append("\n");
            return new String[] { file, "" + (start + 1), "" + (end + 1), sb.toString() };
        } catch (java.io.IOException e) {
            return null;
        }
    }

    // ---------- reflection ----------

    private static String typeName(Type t) {
        return t.getTypeName().replace("java.util.", "").replace("java.lang.", "");
    }

    private static Set<String> known() { return new LinkedHashSet<String>(Arrays.asList(CLASSES)); }

    private static Set<String> mentioned(String text, String self) {
        Set<String> out = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("\\b[A-Z]\\w*").matcher(text);
        while (m.find()) if (known().contains(m.group()) && !m.group().equals(self)) out.add(m.group());
        return out;
    }

    // JSON array text describing every known class, built with real reflection.
    static String classesJson() {
        Map<String, Set<String>> usedBy = new LinkedHashMap<String, Set<String>>();
        for (String c : CLASSES) usedBy.put(c, new LinkedHashSet<String>());
        StringBuilder sb = new StringBuilder("[");
        for (String name : CLASSES) {
            try {
                Class<?> c = Class.forName(name);
                Set<String> has = new LinkedHashSet<String>();
                Set<String> uses = new LinkedHashSet<String>();
                StringBuilder fields = new StringBuilder(), methods = new StringBuilder();
                for (Field f : c.getDeclaredFields()) {
                    if (f.isSynthetic()) continue;
                    has.addAll(mentioned(typeName(f.getGenericType()), name));
                    if (fields.length() > 0) fields.append(",");
                    fields.append("{\"name\":").append(WebServer.q(f.getName())).append(",\"type\":").append(WebServer.q(typeName(f.getGenericType())))
                            .append(",\"mod\":").append(WebServer.q(Modifier.toString(f.getModifiers()))).append("}");
                }
                List<Executable> all = new ArrayList<Executable>();
                all.addAll(Arrays.asList(c.getDeclaredConstructors()));
                all.addAll(Arrays.asList(c.getDeclaredMethods()));
                for (Executable x : all) {
                    if (x.isSynthetic() || x.getName().startsWith("lambda$")) continue;
                    String sig = (x instanceof Constructor ? c.getSimpleName() : x.getName()) + "(" + paramList(x) + ")";
                    String ret = x instanceof Method ? typeName(((Method) x).getGenericReturnType()) : "";
                    for (Type p : x.getGenericParameterTypes()) uses.addAll(mentioned(typeName(p), name));
                    if (methods.length() > 0) methods.append(",");
                    methods.append("{\"sig\":").append(WebServer.q(sig)).append(",\"ret\":").append(WebServer.q(ret))
                            .append(",\"mod\":").append(WebServer.q(Modifier.toString(x.getModifiers()))).append("}");
                }
                String file = fileOf(name);
                String[] snip = snippet(name, null);
                Set<String> creates = new LinkedHashSet<String>();
                if (snip != null) {
                    Matcher m = Pattern.compile("\\bnew\\s+(\\w+)\\s*[(<]").matcher(snip[3]);
                    while (m.find()) if (known().contains(m.group(1)) && !m.group(1).equals(name)) creates.add(m.group(1));
                    uses.addAll(mentioned(snip[3], name)); // any known class named in the source text
                }
                String kind = c.isInterface() ? "interface" : c.isEnum() ? "enum" : Modifier.isAbstract(c.getModifiers()) ? "abstract class" : "class";
                String ext = c.getSuperclass() == null || c.getSuperclass() == Object.class || c.isEnum() ? "" : c.getSuperclass().getSimpleName();
                StringBuilder ifs = new StringBuilder();
                for (Class<?> i : c.getInterfaces()) ifs.append(ifs.length() > 0 ? "," : "").append(WebServer.q(i.getSimpleName()));
                for (String h : has) usedBy.get(h).add(name);
                for (String u : uses) usedBy.get(u).add(name);
                for (String cr : creates) usedBy.get(cr).add(name);
                if (!ext.isEmpty() && usedBy.containsKey(ext)) usedBy.get(ext).add(name);
                if (sb.length() > 1) sb.append(",");
                sb.append("{\"name\":").append(WebServer.q(name)).append(",\"kind\":").append(WebServer.q(kind))
                        .append(",\"file\":").append(WebServer.q(file)).append(",\"extends\":").append(WebServer.q(ext))
                        .append(",\"implements\":[").append(ifs).append("],\"has\":").append(WebServer.arr(has))
                        .append(",\"creates\":").append(WebServer.arr(creates)).append(",\"uses\":").append(WebServer.arr(uses))
                        .append(",\"role\":").append(WebServer.q(ROLE.get(name))).append(",\"fields\":[").append(fields)
                        .append("],\"methods\":[").append(methods).append("],\"usedBy\":@").append(name).append("@}");
            } catch (ClassNotFoundException e) { /* class not compiled: skip */ }
        }
        String out = sb.append("]").toString();
        for (String name : CLASSES) out = out.replace("@" + name + "@", WebServer.arr(usedBy.get(name)));
        return out;
    }

    private static String paramList(Executable x) {
        StringBuilder sb = new StringBuilder();
        for (Type t : x.getGenericParameterTypes()) sb.append(sb.length() > 0 ? ", " : "").append(typeName(t));
        return sb.toString();
    }

    static String oopJson() {
        StringBuilder sb = new StringBuilder("[");
        for (Map.Entry<String, String> e : OOP.entrySet()) {
            String[] parts = e.getValue().split("\\|");
            if (sb.length() > 1) sb.append(",");
            sb.append("{\"concept\":").append(WebServer.q(e.getKey())).append(",\"text\":").append(WebServer.q(parts[0]))
                    .append(",\"classes\":").append(WebServer.arr(Arrays.asList(parts[1].split(",")))).append("}");
        }
        return sb.append("]").toString();
    }
}
