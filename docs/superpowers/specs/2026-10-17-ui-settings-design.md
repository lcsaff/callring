# Settings and picker UI cleanup

Approved in chat on 2026-10-17.

The work schedule settings contain the only timezone display and selector. Use a read-only dropdown with valid Java timezone IDs, prioritizing common choices and preserving valid existing IDs. Other pages still interpret timestamps in the business timezone, without rendering zone labels.

Loading or saving existing data must not add a progress row above content. Remove linear progress bars from shared feedback, appearance and widget configuration. Preserve initial empty-state feedback, retry, errors and duplicate-submit protection.

Presets are shortcuts for a custom RGB seed. Picking one immediately updates the custom HEX field, RGB sliders, preview and applied theme through the existing serialized appearance writer. Color mode stays unchanged. Existing persisted preset settings remain readable; opening the screen alone does not rewrite storage. Highlight a preset whenever its RGB matches the effective seed.

Use consistent date/time selection fields with separate labels and values. Keep calendar and keyboard date entry, clock and keyboard time entry, cancellation, saveable drafts and UTC date-picker conversion. Center time controls, keep actions visible, and scroll picker content within the available window when needed. Date range titles and selected-date summaries should fit compact windows.

Verify color synchronization, timezone selection/reset, unchanged home/appearance content positions during refresh/save, date conversion, DST and draft restoration with the existing Compose test suites. Build and lint both UI and the production app. Device visual verification depends on an online, unlocked test device.
