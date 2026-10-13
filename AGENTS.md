# Local release housekeeping

After successfully publishing and verifying every GitHub release, run:

`powershell -NoProfile -ExecutionPolicy Bypass -File native/cleanup-artifacts.ps1 -Apply`

Keep only the three newest versioned release directories in `outputs/native`.
Remove disposable local builds and isolated test fixtures using the cleanup script's explicit allowlist.
Preserve source code, test sources, dependencies/toolchains, user data, publishing Git checkout and GitHub releases.
Run the script without `-Apply` first when its scope has changed; inspect the deletion plan.
Add new disposable test/build naming conventions to the allowlist when creating them.
Never recursively delete the workspace root or clean outside this project.

