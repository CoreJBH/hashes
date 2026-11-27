# Privacy

Hashes keeps everything on the phone.

- **No network in this phase.** The app has no `INTERNET` permission. Phase 1
  mines a fixed block header locally; nothing is sent anywhere.
- **No wallet key, no seed, no address.** The engine works on a block header and
  a target only. There is nothing to steal that could spend bitcoin.
- **No analytics, no crash reporting, no advertising SDK.** The only
  dependencies are AndroidX and Compose.
- **No storage.** The engine keeps its counters in memory. When the app closes,
  the session's numbers are gone.
- **Nothing runs in the background.** The engine starts when the screen is open
  and stops when the app is paused or closed.
