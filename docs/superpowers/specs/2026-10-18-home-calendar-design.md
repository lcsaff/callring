# Simplified home and work calendar

Approved in chat on 2026-10-18; user accepts any convenient reliable yearly holiday source.

- Retain Home, Callers and Settings tabs. Home contains date, calendar day type, current working/nonworking status, holiday name during holiday periods, and leave management. Remove recent calls/preview, widget pinning and every settings shortcut/switch from Home.
- Move position-list management into Callers; Settings retains a separate personal-position choice. Group settings into incoming-call behavior, work/scoring, personal information and appearance/system support.
- Work settings expose one shared pair of morning/afternoon windows: start, lunch start, lunch end, finish. Normal weekdays and official makeup workdays use these same windows. Keep timezone selection exclusively in work settings. Preserve legacy schedule snapshots and call facts; new UI saves normalized weekdays and makeup windows through the existing API.
- Calendar day type (working day, rest day, holiday) is distinct from instantaneous work status. Personal leave, lunch and after hours are nonworking without changing the calendar day type. Official makeup days display as ordinary workdays.
- Delete manual date override and local calendar-import UI. Keep legacy persisted facts/API for historical compatibility. Calendar settings show annual source/update state and retry sync, with no manual day editing.
- Fetch annual holiday-cn JSON derived from State Council notices over HTTPS, validate bounded complete responses, and atomically import into existing persistence. Cache successful years, refresh automatically in the background with bounded timeouts/throttling and retry when appropriate. Failed/invalid downloads must preserve cached data and never block incoming-call processing. Add Internet permission; do not send call/profile data.
- Verify date-type versus time-of-day behavior, holiday names, uniform schedule saves, home/settings navigation, source parsing/failure preservation, caching and existing regression suites. Build production and isolated test APKs; no real-device claims without device evidence.
