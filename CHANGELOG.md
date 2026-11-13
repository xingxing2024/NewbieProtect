# 1.1.2 (local audit fixes, 2026-11-13)

- Resolve all 17 confirmed audit groups; see FIX-VALIDATION-1.1.2.md for exact mapping and limitations.
- Corrupt/invalid data cannot clear the in-memory cache or be overwritten; failed initial load disables the plugin before registering listeners.
- Attribute indirect damage through DamageSource and entity ownership fallbacks; preserve Boss combat timeout over reconnects.
- Centralize remaining/counting/immunity/status in ProtectionState; enforce automatic-start and clear semantics without UI/query bypasses.
- Reject time arithmetic overflow before mutation, apply limits only to adding/setting, reset warning thresholds on new allowances, and preserve bonus time in manager API.
- Clean up PAPI registration and metrics on shutdown; reload bStats opt-in without restart.
- Complete inline-comment defaults, localized timing/status/console templates, and non-destructive legacy-language fallback.
- Preserve YAML schema, defaults and existing documented gameplay choices. No live server deployment or GitHub publication.

# 1.1.1 (local optimization build, 2026-11-13)

- Attribute arrows and tridents to their shooters for outgoing PvP restrictions and Boss combat.
- Apply world and Residence settings only where matched; pause/protection denial wins when overlapping.
- Cache Boss type lists on reload and use the native Bukkit Boss interface.
- Coalesce routine saves on an owned background writer. Copy synchronized entries, retain later revisions, replace YAML through a temporary file and flush on shutdown.
- Declare optional dependencies for PlaceholderAPI and Residence.
- Preserve default PvP behavior, player commands, YAML fields, and existing eligibility semantics.
- Verified by regression tests and Maven packaging. Actual server integration (including Folia and Residence) requires staging verification.
