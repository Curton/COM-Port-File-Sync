package com.filesync.lab.scenario;

import java.util.ArrayList;
import java.util.List;

/**
 * A regression scenario: a JSON list of steps executed against a {@link
 * com.filesync.lab.peer.RemotePeer}. Unknown fields are ignored, so scenarios stay readable and
 * forward compatible.
 *
 * <p>Example:
 *
 * <pre>
 * {
 *   "name": "slow-link happy path",
 *   "steps": [
 *     {"op": "fault", "baud": 9600, "lossPct": 0.5},
 *     {"op": "push", "path": "notes.txt"},
 *     {"op": "waitFile", "path": "notes.txt", "timeoutMs": 120000},
 *     {"op": "sync", "timeoutMs": 300000},
 *     {"op": "expectThroughput", "channel": "inbound", "minBps": 600, "maxBps": 961}
 *   ]
 * }
 * </pre>
 */
public final class Scenario {

    public String name = "unnamed";
    public List<Step> steps = new ArrayList<>();

    /** One scenario step. Fields not used by the selected {@code op} are ignored. */
    public static final class Step {
        public String op = "";
        public long ms;
        public long timeoutMs = 60_000;
        public String path;
        public String message;
        public String direction = "to-app";
        public String frame;
        public String text;
        public String action = "drop";
        public String command = "";
        public int occurrence = 1;
        public String channel = "inbound";
        public double lossPct;
        public double corruptPct;
        public int baud;
        public long latencyMs;
        public long jitterMs;
        public int noisePeriodBytes;
        public int noiseBurstBytes;
        public Boolean syncing;
        public Boolean sender;
        public Boolean connected;
        public Boolean roleNegotiated;
        public double minBps;
        public double maxBps;
        public long minDropped;
    }
}
