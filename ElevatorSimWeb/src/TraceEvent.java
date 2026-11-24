import java.util.ArrayList;
import java.util.List;

// One thing that really happened inside the Java engine (a method ran, an exception was thrown, ...).
class TraceEvent {
    int id, action, tick, depth;
    String category, cls, method;
    String args = "", result = "", explain = "", change = "";
    int pid = -1, eid = -1, floor = -1;          // passenger, elevator, floor involved (-1 = none)
    String exType, thrownAt, caughtIn;            // only for EXCEPTION events
    String exCls, exMethod;
    int exLine;
    String candidates;                            // JSON from Scheduler.explain(), only for SCHEDULER events
    int chosen = -1;                              // elevator id the scheduler chose

    TraceEvent change(String c) { change = c; return this; }
    TraceEvent who(int p, int e, int f) { pid = p; eid = e; floor = f; return this; }
}

// The recorder. Engine methods call Trace.rec(...) when they run, so the list is a true log,
// not a script. Single-threaded on purpose: WebServer runs every request on one thread.
class Trace {
    static final String THREW = "threw an exception";
    static final int MAX = 4000;
    static int action = 0;
    static int tick = 0;
    private static boolean on = true;
    private static int nextId = 1;
    private static int depth = 0;
    private static final ArrayList<TraceEvent> events = new ArrayList<TraceEvent>();

    static void setEnabled(boolean b) { on = b; }
    static int lastId() { return nextId - 1; }
    static void clear() { events.clear(); depth = 0; }

    // Every browser request starts a new action; its events share the action number.
    static void beginAction() { action++; depth = 0; }

    // A leaf call: the method does its job without calling other traced methods.
    static TraceEvent rec(String category, String cls, String method, String args, String result, String explain) {
        TraceEvent e = new TraceEvent();
        e.category = category; e.cls = cls; e.method = method;
        e.args = args; e.result = result; e.explain = explain;
        if (!on) return e; // detached event: callers can still set fields safely
        e.id = nextId++;
        e.action = action; e.tick = tick; e.depth = depth;
        events.add(e);
        if (events.size() > MAX) events.remove(0);
        return e;
    }

    // A call that runs other traced methods inside it. Result stays "threw" until the method sets it.
    static TraceEvent in(String category, String cls, String method, String args, String explain) {
        TraceEvent e = rec(category, cls, method, args, THREW, explain);
        if (on) depth++;
        return e;
    }

    static void out() { if (on && depth > 0) depth--; }

    // Records a caught exception using the exception's own real stack trace.
    static TraceEvent exception(Throwable ex, String caughtIn, String explain) {
        StackTraceElement f = ex.getStackTrace().length > 0 ? ex.getStackTrace()[0] : null;
        String type = ex.getClass().getSimpleName();
        TraceEvent e = rec("EXCEPTION", type, "catch", "", String.valueOf(ex.getMessage()), explain);
        e.exType = type;
        e.caughtIn = caughtIn;
        if (f != null) {
            String m = f.getMethodName().equals("<init>") ? simple(f.getClassName()) : f.getMethodName();
            e.exCls = simple(f.getClassName());
            e.exMethod = m;
            e.exLine = f.getLineNumber();
            e.thrownAt = e.exCls + "." + m + "(" + f.getFileName() + ":" + f.getLineNumber() + ")";
        }
        return e;
    }

    private static String simple(String n) { return n.substring(n.lastIndexOf('.') + 1); }

    static List<TraceEvent> after(int id) {
        ArrayList<TraceEvent> out = new ArrayList<TraceEvent>();
        for (TraceEvent e : events) if (e.id > id) out.add(e);
        return out;
    }
}
