# Privacy

Thumper keeps its libraries on the phone. There is no account of ours, no analytics and no server of ours. The only network traffic is
the traffic you ask for.

## Podcasts and reading

Subscribing or refreshing fetches each show's or site's feed from its publisher. Searching sends your search words to Apple's
directory, or to Podcast Index if you chose it and entered a key. Playing streams an episode from its publisher, or from a downloaded
file. Subscriptions, listening positions, read marks, saved articles and Up Next are plain JSON files in the app's private storage. The
Podcast Index key and secret are encrypted with a key in Android's Keystore.

## Music

The Music tab reads audio from Android's media index (with the `READ_MEDIA_AUDIO` permission) or from a folder you choose with the
system picker. It reads the files' tags and embedded covers, and stores an index, small cover JPEGs, and the loudness measurements you
ask for in the app's private storage. Playlists, favourites and the library check live in the same private storage; a check only
reads the files and covers already on the phone. Local music never touches the network.

## Jellyfin

Jellyfin is optional and only talks to the server address you enter. Thumper fetches that server's public information, posts your
username and password once to `Users/AuthenticateByName`, then uses the returned access token. The address, username, token and a random
device id are kept encrypted with Android's Keystore (the device id in ordinary preferences). The token is sent in the `Authorization`
header only to that server's host, never in a stream or image URL. Albums, artwork, streams and downloads come from that server. A home
server is often plain http, and the sign-in screen warns that the password and what you play can be seen on that network. Signing out
removes the token; it does not delete downloads, which you can delete yourself.

## Downloads

Podcast downloads and Jellyfin downloads live in the app's own storage and go when you delete them or uninstall Thumper. Podcast
downloads may use Android's download service; Jellyfin downloads go through Thumper's authenticated HTTP client. Downloaded Jellyfin
songs play from the file with the server off.

## Permissions

- `INTERNET` — feeds, directories, Jellyfin and updates.
- `READ_MEDIA_AUDIO` — the phone's music, if you choose that source.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — background audio and its notification.
- `POST_NOTIFICATIONS` / `POST_PROMOTED_NOTIFICATIONS` — the media notification and download Live Update.
- `WAKE_LOCK` — keeping audio going with the screen off.
- `DOWNLOAD_WITHOUT_NOTIFICATION` — podcast downloads shown through Thumper's own Live Update.

Nothing is sent to us, and there is no telemetry.
