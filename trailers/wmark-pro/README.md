<!-- Copyright 2026 EchoJustus. All rights reserved. Part of clogem-support. -->
<!-- Not covered by the MIT license: see "License" below and the repository's README. -->
# Wmark Pro: the Store trailer

A 54-second trailer for **Wmark Pro: Video Watermarker** in the Microsoft
Store, with closed captions in the eighteen languages of its Store
listing. It shows the app at work on open films: a batch of three
videos, the logo flipping in 3D, warning text, the canary frames, the
render queue and the results. It ends on what watermarks can't do.

| File | What it is |
|---|---|
| `wmark-pro-trailer.mp4` | the trailer: 1920×1080, 30 fps, H.264 High with AAC-LC stereo at 48 kHz, faststart, no edit lists; about 4 Mbps, so git can keep it |
| `wmark-pro-trailer-thumbnail.png` | its thumbnail, 1920×1080: the title card |
| `captions/wmark-pro-trailer.<lang>.vtt` | closed captions (WebVTT), one per listing language: `en fr zh-Hans zh-Hant ru ja ko ar ta sv ms fi hi es pt de id it` |
| `recording.edn`, `edit.edn`, `words.edn`, `footage.edn` | how it was made: the take's script, the cut, every word in every language, the open footage |
| `sources/` | what the cut is made from: the take (`take.mkv` and its marks), the three films the app watermarked in it, the app's icon, and the fonts |

## Uploading it to Partner Center

The Store's rules ("App screenshots, images, and trailers", Microsoft
Learn, updated 2026-08-24): MP4 or MOV, 1920×1080, under 2 GB; a
1920×1080 PNG thumbnail; a title of up to 255 characters; captions as
WebVTT under 50 MB, an audio description as MP3 under 500 MB; no age
ratings in the video. A trailer shows at the top of the listing only
with a **16:9 Super hero art** image (1920×1080, no text, no app UI);
without one it is listed with the screenshots.

1. Make the upload copy at the recipe's 50 Mbps (it stays out of git):

   ```
   bb trailer-footage          # once: the open footage, checked against its SHA-256
   bb trailer --quality store  # -> wmark-pro-trailer-store.mp4 (about 340 MB)
   ```

   `wmark-pro-trailer.mp4` itself passes every hard rule too, at a lower
   bitrate.
2. Partner Center > the app > the submission > **Store listings** > a
   language > **Trailers** > **Upload**: the video, then
   `wmark-pro-trailer-thumbnail.png`, the title *Wmark Pro: Video
   Watermarker*, and under **Closed captions** that language's
   `captions/wmark-pro-trailer.<lang>.vtt` (`zh-Hans` for Chinese
   (Simplified), `pt` for Portuguese...).
3. In every other language: **Choose from existing trailers**, then that
   language's caption file.
4. The listing's `.csv` (`bb store-package` in the app's repository)
   leaves trailer cells empty, and an empty trailer cell **deletes** the
   trailer on import. After uploading, export the listings and import
   from that export, as its README says.

No audio description is included: the trailer has no narration, its
on-screen words are captioned, and the Store marks the audio description
optional. One could be made later as an MP3 reading out the cards.

## How it was made

1. **Footage.** Clips from the trailers of two Blender Foundation open
   films, *Sintel* and *Big Buck Bunny*, released under Creative Commons
   Attribution 3.0 (`footage.edn`, `bb trailer-footage`). The cuts leave
   out every title card, logo and credit, since the films' logos and
   trademarks aren't covered by the licence. Only animation appears, no
   people.
2. **The take.** The app's Linux build ran on a virtual 1920×1080 display
   (Xvfb). `scripts/trailer/record.clj` drove it the way a person would:
   the pointer glides, clicks land, words are typed a key at a time,
   through X11's XTest from Clojure with the JDK's foreign function
   interface. FFmpeg's `x11grab` recorded it (`recording.edn`, `clojure
   -M:record`). Pro was unlocked for the take, so the canary frames are
   real. The app watermarked the three clips with its "Studio release"
   profile, and the results are in `sources/`.
3. **The cut.** `scripts/trailer/edit.clj` cuts the take on its marks,
   speeds up the waits, holds on a canary frame, crossfades the segments
   and lays the cards over them (`edit.edn`, `words.edn`). The title card
   uses the app's icon and Open Sans.
4. **Sound.** `scripts/trailer/music.clj` synthesizes the music with
   FFmpeg (chords, bass and an arpeggio, made for this trailer) and brings
   it to −16 LUFS, the level the Store recommends.
5. **Captions.** `scripts/trailer/captions.clj` writes one WebVTT file per
   language from `words.edn`: the cards' words and the music in brackets,
   at the top of the picture unless a card is there. No line passes 50
   characters.
6. **Checks.** `scripts/trailer/check.clj` reads the result back
   (ffprobe and the MP4's boxes) against the Store's recipe: size, codec
   and profile, B-frames, closed GOPs, audio, faststart, no edit lists,
   the thumbnail and the captions.

To change a word, edit `words.edn` and run `bb trailer --only captions`
(or `bb trailer` for the cards on screen). `bb test` checks that the
committed captions match the words, that every language has every word,
and that the committed video meets the Store's rules (with FFmpeg on
`PATH`).

The translations, like the Store listing's, haven't been reviewed by
native speakers yet. Nothing in the trailer says "subliminal": the mode
is called canary. It doesn't promise that removal is impossible.

## Credits

- *Sintel*: © copyright Blender Foundation, durian.blender.org, CC BY 3.0.
- *Big Buck Bunny*: (c) copyright 2008, Blender Foundation,
  www.bigbuckbunny.org, CC BY 3.0.
- Both watermarked for this demonstration. Not affiliated with or endorsed
  by the Blender Foundation.
- Open Sans: Copyright 2020 The Open Sans Project Authors, SIL Open Font
  License 1.1 (`sources/fonts/OFL.txt`).
- Music: made for this trailer.

## License

- **The trailer** (the video, its thumbnail, captions, words, cut, take,
  the app's icon and its UI): © 2026 EchoJustus. All rights reserved.
- **The films' footage** in it and in `sources/` stays © Blender
  Foundation under CC BY 3.0 (credits above). Our watermarks over it are
  ours.
- **The fonts** in `sources/fonts/`: SIL Open Font License 1.1.
- **The toolchain** that made it (`scripts/trailer/`,
  `test/trailer_test.clj`, `deps.edn`): MIT, like the rest of the code
  here.
