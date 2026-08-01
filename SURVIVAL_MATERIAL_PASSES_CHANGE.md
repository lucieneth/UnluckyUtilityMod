# Survival material-pass change and rollback

## Purpose

This change makes Survival printer restocking follow one fixed rule for each
selected layer band:

1. Read the remaining materials from the schematic and freeze their ranking,
   most numerous first.
2. Honour support dependencies first. A material that cannot be placed before
   its schematic support has had its pass waits for that support.
3. If every remaining eligible material fits in the usable inventory, make one
   final exact-load pass containing all of them.
4. Otherwise make a pass containing **only** the highest-ranked eligible
   material. Refill that material alone until its pass finishes, then repeat
   the decision.

Creative mode does not use this code path and keeps its existing routing.

## What changes

- Replaces the greedy group selection that previously took the first several
  materials that happened to fit in 32 slots. That behaviour could combine
  light-gray and orange carpet even though the whole carpet remainder did not
  fit.
- Makes the transition to a newly selected pass a mandatory stash boundary:
  surplus material from the completed pass is returned, then the next pass is
  loaded before the printer continues its route.
- Lets that one required transition launch immediately even when an earlier
  low-yield run left the stash cooldown active. The bypass is consumed once;
  failed supply attempts still retain their normal cooldown protection.
- Keeps support ordering separate from count ranking, so a support block can
  legitimately run before a more numerous dependent block.
- A support pass that receives no stock remains blocked instead of being marked
  complete. Dependent blocks therefore cannot be routed or placed on air.
- When a supply miss finds no remembered matching chest, the active trip opens
  every remaining marked chest once to refresh the stash facts before declaring
  that material unavailable.

## Expected examples

- 3,394 cobblestone (more than the bag): every refill contains cobblestone
  only.
- After cobblestone, 11 carpet colours totalling 23 slots: one exact final
  carpet load, then no further restock in that band.
- If those carpets total 33 slots: light-gray only, then re-evaluate. It does
  not take light-gray plus orange just because those two fit.

## Verification

Build with:

```powershell
./gradlew compileClientJava -q --console=plain
```

In a Survival test, the printer report should show either one material under
`building now`, or every remaining eligible material when their combined
whole-stack slot cost is within the bag capacity. A group transition should
show a stash trip before route movement resumes.

## Reverting safely

The implementation is confined to:

- `src/client/java/unlucky/utility/client/module/modules/world/Printer.java`
- `src/client/java/unlucky/utility/client/util/ChestStash.java`

To revert only this behaviour after it is committed, restore the commit that
adds this document, or use `git revert <commit>`. Do not use `git reset --hard`
on a shared worktree. Before a commit exists, remove the named changes from
those two files and delete this document; the prior logic is the greedy
first-materials-that-fit grouping plus normal stash cooldown handling.
