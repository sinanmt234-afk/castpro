# CastPro
Cast video and audio from your Android phone (Windows sender coming next) to a CastPro TV app.

- `tv/`    – receiver app for Android TV / TV sticks (Media3 ExoPlayer, remote keys: OK = play/pause, ◀ ▶ = ±10s, FF/RW = ±30s, ▲ = next audio track, ▼ = next subtitle, Back = stop)
- `phone/` – sender app: pick file → choose audio + subtitle track → cast; live controls on phone
- `core/`  – shared protocol constants and the CastPro palette

Build: push to GitHub, the Actions workflow builds both APKs (or `gradle :tv:assembleDebug :phone:assembleDebug`).
Phone and TV must be on the same WiFi.
