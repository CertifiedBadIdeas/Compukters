---
name: compukters-visual-assets
description: Iterate on Compukters textures, block models and other subjective visual assets with an isolated client preview and user review before integration. Use for visual asset work, not Guest graphics or ordinary IDE layout changes.
---

# Compukters Visual Assets

Keep candidates in the task worktree until the user has seen the final in-client result and explicitly
approved integration. This overrides the standing automatic integration policy for visual changes;
explicit user instructions can waive the review. Apply production-workflow and verification-workflow
for checkout setup, scoped commits and completion. Use imagegen only for raster work that benefits from
it; model geometry, UVs and existing texture references usually belong in native asset files.

## Preview loop

Read [the visual review guide](../../../docs/contributors/development.md#reviewing-textures-and-block-models).
The repository `./visual-preview` helper currently targets standalone addon builds with `runClient`,
`src/main/resources` and `build/resources/main` (Create is the default). For base-mod assets or a different
layout, use its owning version-specific client and source sets rather than forcing this helper's layout.

1. Prepare the parent worktree with the required addon initialized at the pinned revision. Run doctor;
   stageAddonDevelopmentDependencies is required when the addon SDK is absent. Define a bounded scenario:
   disposable world, orientations/states, comparison original, item view, lighting and expected outcome.
2. Build and run focused checks before launching. Start from the worktree:
   `./visual-preview --addon create run --scenario 'Compare both rings, flow states and pipe ends'`.
   Create a disposable world in this client's own game directory. On subsequent launches, use
   `--world 'Pump Preview'` to open that existing isolated world directly. Never copy or open a user save.
3. For resource-only edits, run `./visual-preview reload`, then press F3+T **in that client**. This copies
   resources but does not press keys or prove that reload completed. Check the client log and actual
   appearance. Deleted/renamed resources require a stopped client and rebuilding outputs. A running managed
   client holds the workspace Gradle slot; do not queue verification behind your own live client.
4. Take F2 screenshots in the preview client. Archive each candidate with
   `./visual-preview capture --label pipe-ends --scenario 'Describe the visible comparison'`.
   Use `--image <PNG>` for a specific screenshot. The capture records revisions, resource fingerprint,
   scenario and screenshot hash; it does not certify appearance or prove a screenshot is current.
5. Show the screenshot or live client to the user. Correct the requested details in the same worktree;
   no speculative merging while review is pending. An approval covers the visible candidate only.

## Review and completion

Record actual user approval with `./visual-preview approve --review <capture.json> --note '<user reply>'`.
Do not invent approval, treat silence as agreement, or run this command before an explicit reply. The helper
rejects a capture after resource/revision changes or screenshot tampering. If only a commit/history cleanup
changed the identity, compare the resource fingerprint and preserve both reviewed and final revisions in
an evidence note; get a fresh review if appearance changed.

Stop the client normally using its menu before final Gradle checks or cleanup. Window destruction through
xdotool can leave GLFW/JVM shutdown hanging; prefer the game's own Quit action. If shutdown hangs, inspect
and terminate only the identified task-owned preview process. Never stop unrelated builds or clients.

After approval, run focused checks against final sources, commit and integrate under repository policy.
Retain screenshots, approval and verification evidence outside the task worktree with worktree-task finish.
Report the observed result and actual verification scope. A passing model test, build, client launch or
capture file alone does not establish visual correctness or user approval.
