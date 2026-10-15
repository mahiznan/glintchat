# Failure drills: results register

Plan: `docs/superpowers/plans/2026-10-15-failure-drills.md`. One row per drill. Verdicts: **PASS** (design holds in the slice), **GAP** (design right, slice lacks it), **DESIGN-BUG** (the design itself needs fixing), **UNKNOWN** (retest). For each GAP or DESIGN-BUG the user decides **FIX NOW**, **DEFER** (backlog, with a reason) or **WON'T FIX** (accepted, with a reason). After a fix the drill is run again: **FIXED** or **STILL OPEN**. The Observed and Verdict columns always describe the unfixed run and are never overwritten; the retest goes into the last column.

The *Prediction* column is the user's, written **before** the run. Claude's own expectations are in Appendix A of the plan and must not be read before predicting.

| ID | Drill | Prediction (user) | Observed | Verdict | Gap or design change; decision and design reference | Fix applied and retest result |
|---|---|---|---|---|---|---|
| A1 | Customer closes the tab (clean close) | (none written before the run) | Before: pod-1 sockets 2, users 2; pod-2 2/2; 4 lsof lines on 18081 and on 8081; pod queues 1 consumer, ADDED 10, ACKED 10. After close: pod-1 sockets 1, users 1; pod-2 unchanged; 2 lsof lines on each port (one whole chain gone: browser, Toxiproxy and gateway sides); queues unchanged. Reopening the tab registered a new socket and messages flowed. threads stayed 32 (31 at rest, 32 with 1 or 2 sockets). | **PASS** | No gap. Notes: `threads` is a coarse indicator (Tomcat keeps pooled workers), so A5 is the real leak test; `Sleep 23` DB connections vs 20 expected is unexplained (look again in E4). | n/a |
| A2 | Customer network disappears without a close (half-open) | | | | | |
| A3 | Page refresh during a burst of incoming messages | | | | | |
| A4 | Same user on three tabs and two gateways | | | | | |
| A5 | Connection churn (500 quick open/close) | | | | | |
| A6 | Slow client that stops reading | | | | | |
| A7 | Bad input on the socket (garbage, large frames) | | | | | |
| A8 | A client floods | | | | | |
| A9 | Anyone can be anyone (identity stub) | | | | | |
| A10 | Stranger, and the same message sent twice | | | | | |
| B1 | Gateway pod killed (crash) | | | | | |
| B2 | Gateway pod terminated gracefully (SIGTERM) | | | | | |
| B3 | Replacement pod with the same name while the old connection is a zombie | | | | | |
| B4 | Rolling deployment of the gateways | | | | | |
| B5 | Gateway loses the broker while sockets stay open | | | | | |
| B6 | Gateway cannot reach chat-service | | | | | |
| B7 | Two gateways with the same pod id | | | | | |
| B8 | Orphan queue: provisioned but never attached | | | | | |
| C1 | Response lost after the commit | | | | | |
| C2 | chat-service dies after commit, before publish | | | | | |
| C3 | chat-service dies in the middle of a relay batch | | | | | |
| C4 | One of two chat-service instances dies | | | | | |
| C5 | All chat-service instances dead | | | | | |
| C6 | SIGTERM while a request is in flight | | | | | |
| C7 | A crashed session holds the conversation lock | | | | | |
| C8 | Two relays and ordering | | | | | |
| C9 | Log behaviour during a long failure | | | | | |
| D1 | Broker stopped for two minutes, then started | | | | | |
| D2 | Broker restart under traffic | | | | | |
| D3 | Network cut between chat-service and the broker | | | | | |
| D4 | A stuck consumer and queue growth | | | | | |
| D5 | Broker connections used by the relay | | | | | |
| E1 | MariaDB stopped for one minute | | | | | |
| E2 | MariaDB unreachable but connections open (blackhole) | | | | | |
| E3 | Locks and deadlocks under mixed load | | | | | |
| E4 | Connection leak check after the drills | | | | | |
| F1 | Start-up order | | | | | |
| F2 | Everything dies and comes back | | | | | |
| F3 | Reconnect storm | | | | | |
