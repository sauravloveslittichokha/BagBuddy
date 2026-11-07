# Android media-session detection

Phase 3 reads metadata and playback state that other Android apps expose through active media sessions. The implementation is optional: the local mock player remains usable when notification access is denied or no session is active.

## Architecture

- `BagBuddyNotificationListenerService` is declared in the manifest with Android's `BIND_NOTIFICATION_LISTENER_SERVICE` permission. Android requires the user to grant its special access manually.
- `NotificationAccessHelper` checks the enabled notification-listener component and creates an intent for the system notification-listener settings screen. It does not grant access.
- `MusicSessionManager` uses Android `MediaSessionManager` to query active `MediaController`s, registers an active-session listener, and registers a callback only for the selected controller. Activity start/stop registers and unregisters these callbacks.
- Metadata and playback-state callbacks update a `StateFlow<MediaSessionState>`. While playback is reported as playing, a 500 ms ticker projects position using the media session's source position, update time, and playback speed. It is cancelled when paused, stopped, disconnected, or when the activity stops. Active-session discovery itself is callback-driven, not polled.
- `PlaybackSourceSelector` selects live data only when notification access is granted and an active session exists. Otherwise the UI uses the mock player's independent data. Mock controls are disabled while a live session is selected.
- `LyricsSyncEngine` receives an empty lyric track for a live session until a lyrics source is separately connected. It does not display mock lyrics over live music.

## Permission setup

On the Bag Buddy screen, tap **Open notification access settings**, enable Bag Buddy, then return to the app. The activity refreshes session access on resume. This is Android special access, not a runtime permission dialog. If it is denied or revoked, Bag Buddy reports the state and continues with mock playback.

## Metadata and limitations

Title, artist, album, artwork, duration, playback state, and position are optional. Missing strings remain unavailable and are displayed as such; absent artwork uses the simulator placeholder. Position is only shown when the media session supplies one, and is projected while playing using the reported speed. A supplied duration bounds the projected value.

Android exposes media-session metadata and state only when the source app publishes it. The API does not provide synchronized lyrics. Apple Music Android may expose some, all, or none of the listed fields depending on version and playback state; no Apple Music behavior has been verified on a physical device as part of this implementation. No private app data is read, and no DRM or permission controls are bypassed.

## Manual device test

1. Build and install the debug app on an Android phone.
2. Open Bag Buddy and grant its notification-listener access in system settings.
3. Start playback in Apple Music or another app.
4. Return to Bag Buddy and check whether it reports a live session and which metadata fields are available.
5. Pause, resume, skip tracks, and stop playback; verify the reported source, state, and progress update.
6. Revoke access or close the music app; verify Bag Buddy falls back to **MOCK PLAYER**.

Unit tests cover state mapping and source selection using local models/fakes. They do not prove that Apple Music publishes a session on a particular Android device.
