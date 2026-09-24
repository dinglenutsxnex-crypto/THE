# AGENTS.md

## Build

Set up a local toolchain under `/tmp/tools`, then build:

```
export JAVA_HOME=/tmp/tools/jdk-17.0.11+9
export ANDROID_HOME=/tmp/tools/sdk
export GRADLE_USER_HOME=/tmp/tools/gradle-home
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

`app/src/main/res/raw/battles.enc` is generated and gitignored.

## Battle Hijack

Replays an event battle by battle id: `activate_ascension` -> `event_battle_start_fight`
-> `event_battle_finish_fight`, one packet each, each gated on the server's reply to the
previous one. The cycle repeats until the toggle is turned off; a rejected or timed-out
cycle is reported and retried rather than ending the run. The battle id is kept in
`ConnectionViewModel.battleHijackId` so the field survives closing/reopening the overlay.
A `HijackTally` (accepts/fails) rides along with each status update; the UI shows
"x accept" always and "y fail" only once something has failed.

### Run lifecycle

The loop runs in the VPN service and outlives the overlay, so the toggle is a view of
service state, not UI state. Two rules keep it stable:

- `HijackRunGate` serialises runs. A cancelled coroutine still runs its `finally`, so a
  replaced run's closing "STOPPED" would otherwise arrive after the next run started and
  switch the toggle back off — the feature looked bricked. Only the newest run's statuses
  reach the UI; `cancelBattleHijack` invalidates before cancelling.
- `setupOverlay` restores the toggle and input row from `isBattleHijackRunning()`. Without
  it, reopening the overlay showed "off" while packets were still being sent, leaving no
  way to stop them.

A failed step is reported non-terminal and only increments the fail counter; it must never
end the run or leave it unable to restart. Only a real stop or an error is terminal.

Switches in `layout_overlay.xml` are styled from code, not XML — every one must be passed
to `OverlayService.styleSwitch` or it renders with the default platform colours instead of
the translucent app theme. Buttons use `@drawable/btn_primary_bg`, fields `@drawable/input_bg`.

### Panel height

The overlay window uses gravity TOP at `savedY`, so its height must be derived from the
space below `y`, not a flat fraction of the screen — a fixed cap overflows the bottom edge
whenever `y` is large, leaving the last rows unreachable. `fitOverlayHeight` sizes the
window to `min(natural content height, screen - savedY - margin)`, shrink-wrapping short
content and scrolling tall content. It re-runs on content layout changes and after a drag,
since either can change how much room is left. Any new row added to the panel needs no
special handling, but do not reintroduce a fixed window height.

### Duel modes
Three toggles in the user-mode panel drive the same brawler loop: Duel Hijack wins every
duel, Duel Hijack Loss loses every duel, and Infinite Coin alternates win/loss to hold the
win/loss ratio level. All three run their rounds through `runOneDuelRound`; the per-mode
loops only choose the outcome and the wording, so the packet sequence cannot drift between
them. The Infinite Coin ordering lives in `DuelAlternation` rather than as a modulo in the
loop, because an off-by-one there still "works" but no longer holds the ratio.

Each toggle is triplicated: a listener in `setupOverlay`, a `updateXUi` restore path, and a
`setXStatus` colour rule. `updateXUi` exists because a run lives in the VPN service and
outlives the overlay, so reopening the panel has to restore the switch from the waiting
flag. `onDestroy` cancels whichever run the flags say is live.

### Infinite Coin speed
The 1x/2x button below Infinite Coin toggles `coinRoundDelay` (1000ms vs 0) on the next run.
It does **not** open a second concurrent duel, because the server does not allow one: a
captured 2x run shows a strictly alternating `start, finish, start, finish` stream with at
most one duel open at a time, and the single rejected packet in that capture (`Brawler
already started`) is exactly where two starts landed back to back. A round may only begin
once the previous finish is on the wire. The speed comes from removing the idle gap between
rounds, not from parallelism — firing two starts then two finishes reproduces the rejection.
The capture also confirms the reply to packet *i* is packet *i* and echoes its counter, so
pipelining finishes is safe but pipelining starts is not.

### Force close
The menu's FORCE CLOSE kills the HAMMERSCALE process from the overlay. It stops the VPN
first so the tun interface, notification and overlay windows are released cleanly, then
kills the process after a short delay so those shutdown paths get to run — `stopSelf()`
alone would leave the process (and its VPN) alive.

### Wire format

Envelopes are `[0x01][len][payload]` (plain) and `[0x03][len][deflate]`. Payload is
protobuf: `1 = counter`, `2 = command name`, `3 = params message`.

Finish params: `1 = battleId`, `4 = result code (1 win / 3 loss)`, `5 = total rounds`
(win only), `6 = timestamp`, `7 = player id`, `13 = round blob` where `f2` is the total
round count and `f4` is `0x00` on a win.

### Findings from captured traffic (battle 1029011)

Accepted fights always have an `activate_ascension` (params `[1] = battleId`) immediately
before them; every fight injected without it got `[hz1] [java.lang.IllegalStateException]
Out of attempts`. Both logs agree. The start and finish packets themselves are accepted
byte-for-byte modulo counter/timestamp, so the packet contents are not what the rejection
is about.

Counter is a single shared sequence: every outbound message consumes one `nextInjectCounter`,
and the server examples show the reply reuses the request's counter, so the server is
tolerant of the small skew that injected packets introduce.

`nextInjectCounter` uses `max(internal, observed)` where `observed` is the max counter seen
on the wire from any connection, and the internal value is only bumped by injection. Injected
packets are not counted as observed, so once traffic crosses the injector's internal counter,
the comparison stalls and counters start colliding (two messages sharing one counter).
Ordinary gameplay hides this because every non-injected packet advances `observed`.
