# Corner Finish Perimeter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the post-sweep perimeter stage break exactly three upper rows and one bottom row per wall, then turn west before resuming breaks.

**Architecture:** Keep the existing lane sweeps and perimeter call order. Change the perimeter row clearer from a continuously held use key to one removal-confirmed click per allowed row, and add a synchronous input/rotation boundary between the southward step and west turn.

**Tech Stack:** Java 25, Fabric Loom, Minecraft key mappings, Gradle

## Global Constraints

- Leave both existing lane sweeps unchanged.
- Break exactly three upper rows, then one bottom row on each perimeter wall.
- Release use and movement before rotating west.
- Add no dependencies or unrelated refactors.

---

### Task 1: Make perimeter breaks discrete and direction-safe

**Files:**
- Modify: `src/main/java/dev/aether/modules/farming/BedrockPlotMaker.java:621-679`
- Modify: `src/main/java/dev/aether/modules/farming/BedrockPlotMaker.java:743-759`

**Interfaces:**
- Consumes: removal chat messages counted by `onChatMessage(String)` and existing rotation/input helpers.
- Produces: `clearRowFacing(Minecraft, float, float, int)` with at most one ruler activation per confirmed removal and an explicit release before the west turn.

- [x] **Step 1: Run the failing structural regression check**

```powershell
$source = Get-Content -Raw src/main/java/dev/aether/modules/farming/BedrockPlotMaker.java
if ($source -notmatch 'pulseUseKey\(client, yaw, pitch\)') { throw 'Perimeter clearing does not use a synchronous use-key pulse' }
if ($source -notmatch '(?s)stepForwardOneBlockSouth\(client\).*clearRotationLock\(\).*releaseHeldKeysSync\(client\).*breakWallFacing\(client, FINISH_WEST_YAW\)') { throw 'West turn lacks a synchronous input boundary' }
```

Expected before implementation: FAIL with `Perimeter clearing does not use a synchronous use-key pulse`.

- [x] **Step 2: Replace the held use input with discrete confirmed activations**

In `clearRowFacing`, loop up to `maxRemovals`. Before each iteration, verify the crosshair has a non-bedrock block and restore the requested rotation if needed. Reset `removedMessages`, enable `countingRemovals`, run one synchronous 100 ms use-key pulse, and wait until one removal message arrives or the existing timeout expires. Disable counting after every activation and stop without firing again when no removal is confirmed.

The method must never call `setKeyMappingState(client.options.keyUse, true)`.

- [x] **Step 3: Add the west-turn input boundary**

Immediately after `stepForwardOneBlockSouth(client)` succeeds, call:

```java
clearRotationLock();
releaseHeldKeysSync(client);
```

Only then call `breakWallFacing(client, FINISH_WEST_YAW)`.

- [x] **Step 4: Run the structural regression check again**

Run the Step 1 PowerShell check.

Expected: no output and exit code 0.

- [x] **Step 5: Compile and package the mod**

```powershell
.\gradlew.bat build
```

Expected: `BUILD SUCCESSFUL`.

- [x] **Step 6: Review the focused diff**

```powershell
git diff --check
git diff -- src/main/java/dev/aether/modules/farming/BedrockPlotMaker.java
```

Expected: no whitespace errors; only the perimeter click limiter and west-turn boundary differ in `BedrockPlotMaker.java`.
