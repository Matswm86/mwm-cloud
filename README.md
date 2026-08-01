# MWM Cloud

An Android app that quietly backs up a phone's photos, music, video and documents
to a storage box you own, and then tells you honestly whether it worked.

It targets [Hetzner Storage Box](https://www.hetzner.com/storage/storage-box/), which
is roughly EUR 3.20/month for 1 TB with unlimited traffic and speaks open protocols
(SFTP, WebDAV) instead of a proprietary client. Nothing in the app is tied to that
vendor beyond one `Transport` implementation.

**Status: early but usable.** You can connect it to a storage box and back up
photos, music and video today. Documents and household invite codes are not built
yet. See [Roadmap](#roadmap).

## Download

**[Get the APK](https://github.com/Matswm86/mwm-cloud/releases/download/latest/mwm-cloud-c618726.apk)**
&nbsp;·&nbsp; [all builds](https://github.com/Matswm86/mwm-cloud/releases)

Open the link on your phone, tap the file, and allow "install from this source" when
it asks.

The filename carries the build id on purpose, so your phone can never serve you a
stale cached APK. If the link 404s, a newer build has landed: grab the newest
`mwm-cloud-*.apk` from the releases page.

## Why this exists

Off-the-shelf sync clients can already move files to a WebDAV server. They are built
for people who enjoy configuring sync clients. This one is built for the person who
just wants their photos to still exist in ten years, and who has been paying a
monthly subscription for storage they never chose and cannot audit.

Two consequences shape every decision here:

- **The app never deletes anything from your phone.** There is no delete-local code
  path, in any version. Freeing space is a manual decision you make after you have
  seen a restore work.
- **Counts are verified against the server, not asserted.** "1204 of 1204 backed up"
  comes from listing the remote and comparing, not from counting successful uploads.
  An upload that succeeded and a file that is actually there are different claims.

## How it works

```
  Android app                         Provisioning service        Storage box
  ------------------------            --------------------        -----------
  MediaStore -> upload ledger
                    |                  POST /invite/redeem
               WorkManager  -------->  creates a scoped     ---->  /home/<user>
               (unmetered,             sub-account
                battery-aware)
                    |
               Transport  --- HTTPS PUT / MKCOL / PROPFIND ----->  files
```

Each person gets their own sub-account with its own home directory, so members of a
household share one box without seeing each other's files. The phone holds only its
own credentials; the provisioning service is the only thing that can create accounts.

You can also skip the service entirely and point the app straight at a storage box
with a username and password.

## Build

Requires JDK 17. Everything else comes from the Gradle wrapper.

```bash
git clone https://github.com/Matswm86/mwm-cloud.git
cd mwm-cloud
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

CI builds every push and publishes a signed APK to
[Releases](https://github.com/Matswm86/mwm-cloud/releases). Set the repository secret
`ANDROID_DEBUG_KEYSTORE_BASE64` (base64 of a `debug.keystore`) so the signature stays
stable across builds and updates install over the previous version. Without it, CI
generates a throwaway key per build and you will have to uninstall before updating.

### Pointing a build at your own backend

The backend URL is never committed. Add to `local.properties` (untracked):

```properties
mwmcloud.backendUrl=https://your-host.example.com
```

Builds without it default to `https://cloud.example.com`, which does not resolve.

## Setting up a storage box

1. Order a Storage Box. The 1 TB tier is enough for most phones.
2. Enable, in the provider console:
   - **external reachability** — required, or phones cannot connect from outside the provider's network
   - **WebDAV** — the app's transport
   - **SSH** — optional, for your own `rsync`/`rclone` access
   - leave **Samba** off
3. Turn on automatic snapshots. They are the second line of defence if a client ever
   deletes something it should not have.

Known limits worth planning around: a box allows up to 100 sub-accounts and 10
simultaneous connections per account. WebDAV cannot report free space, so quota
readings come over SFTP or from the provisioning service.

## Design

Design tokens live in `app/src/main/java/no/mwmai/mwmcloud/ui/theme/Theme.kt` and are
the only place colour literals appear. Reference assets are in `design/`.

| Token | Value | Use |
|---|---|---|
| Action | `#1F6FC4` | buttons, progress, active tabs |
| Safe | `#1F9E6E` | everything is backed up |
| Attention | `#E4A11B` | needs the user to act (never errors) |
| Text | `#14212B` | |
| Background | `#FBF8F3` | |
| Border | `#EFE8DD` | |
| Action tint | `#DCEBFA` | surfaces behind action-coloured content |
| Muted | `#8A9299` | secondary text, never below 15 sp |

Headings are Bricolage Grotesque 600/700, body is Figtree 400/500/600. Buttons are
68 dp tall and no text renders below 15 sp. Those are legibility requirements rather
than preferences: the intended user is often someone in their seventies holding the
phone at arm's length.

Interface language is Norwegian first, English second. The Norwegian strings are the
original and the English are the translation, not the other way round.

## Roadmap

- [x] Project scaffold, design tokens, CI
- [x] WebDAV transport and encrypted credential store
- [x] Media enumeration, upload ledger, background upload
- [x] Welcome, connection setup, folder picker, home screen
- [ ] Documents, via a folder picker (MediaStore has no document category)
- [ ] Reconcile against the server, so the counts are checked and not just asserted
- [ ] Resumable uploads over SFTP, which is what would lift the 5 GB per-file limit
- [ ] Provisioning service and invite codes for households
- [ ] In-app file browser

Current limits worth knowing: single files above 5 GB are skipped and reported,
because WebDAV `PUT` cannot resume a broken upload. Categories are all-or-nothing;
there is no per-file picker.

## Licence

Not yet chosen. Until one is added, no permission to reuse is granted.
