# obsi-core

The part that ObsiTube, ObsiRead and Obsi Bible share. Each app is still its own app, with its
own package, key and updates. Each one builds this code as its `:core` module.

## What is here

- `Brand`: which app is running, its name, and words like "3 videos" or "3 articles".
- `link/Links`: sends a shared link to the app that reads it. A YouTube link goes to ObsiTube,
  a Bible link to Obsi Bible, any other web page to ObsiRead. So it does not matter which app
  you pick in the share menu.
- `link/Sources`: how two links are found to be the same video or page.
- `share/SharedHighlights`: each app hands its highlights to the other two. Only an app signed with
  one of the three Obsi keys can read them (`res/values/obsi_certs.xml`, Android 12 and up).
- `ui/ObsiApps`: the "Your Obsi apps" switcher, and the "From ObsiTube" style highlight rows.
- Shared screens and parts: `ui/Components`, `ui/Omni` (the quick switcher), `ui/SettingsParts`,
  `ui/Motion`, the vault index, templates, Templater, the note sender, the installers.

Obsi Bible keeps its own screens, search and reader. It uses the core for the parts above that fit it.

## How the apps get it

Each app repo has this repo as a git submodule in `core/`. GitHub builds use that pinned copy.
On the PC, the `obsi-core` folder next to the app repo is used instead, so one edit reaches all
three apps at once (see each app's `settings.gradle.kts`).

After a change here:

1. Build and test all three apps.
2. Commit and push this repo.
3. In each app repo: `git -C core pull origin main`, commit the new pin, and release.

## Tools

- `scripts/adopt.py` moves a file from one app into the core and fixes the app's imports.
- `scripts/patch.py` swaps exact text in a file and keeps its line endings.
