# Thumper

A podcast player and feed reader for Android, with iOS-style liquid glass, in the same minimal style as [Seekur](https://github.com/everettpenne/DuneDdit) and [Spice](https://github.com/everettpenne/Spice).

- **Listen:** Continue Listening, your shows, and the newest episodes across them. Pull to refresh.
- **Read:** your blogs and news feeds as one list, newest first, with unread dots, filter by feed, and a clean reader with links, lists and pictures.
- **Search:** find podcasts in [Apple's directory](https://performance-partners.apple.com/search-api) (no sign-up) or in [Podcast Index](https://podcastindex.org/) (open and independent; needs a free key and secret), or paste any feed's address, or a blog's home page: Thumper finds its feed.
- **Player:** background listening with a notification, skip back and forward (you choose the seconds), speed from 0.8× to 3×, a sleep timer (minutes or end of episode), and it remembers where you were in every episode.
- **Yours:** subscriptions live on the phone. Import and export them as OPML, which every other podcast app and feed reader understands.

Not yet: downloads for offline listening, episode queue, chapters, background refresh with notifications, CarPlay/Auto.

## Where your data goes

Nowhere, except where you point it. There is no account, no analytics and no server of ours. Searching sends your search words to the directory you chose (Apple or Podcast Index). Subscribing and refreshing fetch each show's or site's own feed directly from its publisher, and playing streams the episode from the publisher's host. Subscriptions, listening positions and read marks are plain JSON files in the app's private storage; the Podcast Index key and secret are kept encrypted (Android Keystore).

## Build

```bash
./gradlew :app:assembleDebug      # JDK 21, Android SDK (compileSdk 36); minSdk 31
./gradlew :app:testDebugUnitTest  # JVM tests: the feed parser, HTML reader, fetcher, directories, OPML, library
```

Release signing comes from the environment (`THUMPER_RELEASE_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`, `_KEY_PASSWORD`); without all four the release build is left unsigned.

## Trying it without the internet

`tools/fake-feeds/server.py` is a stand-in for the outside world (needs ffmpeg and Pillow once): Apple-shaped search and charts, a podcast feed with a real MP3, an Atom blog, and a web page that advertises its feed.

```bash
python3 tools/fake-feeds/server.py      # the emulator reaches it at http://10.0.2.2:8097
```

`FlowAgainstFakeFeedsTest` (instrumented) drives it: find a show, subscribe, listen, background playback and resuming, adding a blog by its web address, reading it, and Settings. `RealNetworkTest` does the same against Apple's real directory and a real show, and needs the emulator to have internet.

## Credits

Liquid glass by [Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) (Apache-2.0); type is [Inter](https://github.com/rsms/inter) (SIL OFL 1.1, `docs/licenses/Inter-OFL.txt`) set to Apple's HIG scale; playback by AndroidX Media3; images by Coil. MIT licensed, see `LICENSE`.
