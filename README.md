# PhoneLink

Remote control of YOUR OWN Android phone from a browser.
Phone app streams screen (MediaProjection) + executes taps/swipes/keys/text (AccessibilityService).
Relay server pairs phone and browser by 8-char code.

## 1. Deploy relay (server/)
Any Node host (Render, Railway, Fly). Root dir `server`, build `npm install`, start `npm start`.
Use the `wss://` URL it gives you.
Local test: `cd server && npm install && npm start` -> ws://YOUR_PC_IP:8080

## 2. Build APK with GitHub
Push this folder to a GitHub repo. Actions -> "Build APK" -> download artifact `phonelink-debug-apk`.
Install `app-debug.apk` on phone.

## 3. Use
1. Open app, enter server URL (`wss://...`).
2. Tap "Enable accessibility service" -> PhoneLink -> ON.
   Android 13+ sideload: App info -> top-right menu -> "Allow restricted settings" first.
3. Tap "Start sharing + control" -> accept screen capture prompt.
4. Open server URL in browser, enter pairing code, Connect.

## Limits
- Portrait only (no rotation handling).
- Banking/password/DRM screens show black (FLAG_SECURE). Normal Android behavior.
- Screen frames sent only when screen changes.
- Persistent notification required by Android while sharing. Tap Stop to end.
