# Proposal: true Direct Play of MKV on Android TV (not applied)

**Status:** proposal only. It changes the media engine and one Next.js route, which Phase 5 was
asked to leave untouched, so nothing here has been applied.

## Why

The engine's `PlaybackDecider` was written for browsers. It allows Direct Play only when:

1. the container is MP4 or WebM, and
2. the selected audio track is the file's first audio track,

because a `<video>` element can't play Matroska and can't switch audio tracks. ExoPlayer can do
both. Today a typical Real-Debrid MKV therefore goes to **Remux**: the engine stream-copies video
and audio into HLS fMP4. Nothing is re-encoded, and the TV still decodes the original HEVC/DV/HDR10
video with its own hardware. But the engine (FFmpeg on the Windows PC) is still in the path.
Measured on 2026-10-25 with *The Matrix* 2160p DV MKV and Shield-class capabilities: `mode=Remux`
for exactly these two reasons ("MATROSKA container is not playable directly in browsers"; the
preferred English track was the second audio track).

## Change (backward compatible: browsers send neither field, so nothing changes for them)

1. `services/media-engine/src/Notflix.MediaEngine/Playback/Models.cs` → `ClientCapabilities`:

   ```csharp
   /// <summary>Containers the client plays from the original file (browsers: MP4/WebM).</summary>
   public List<string> DirectContainers { get; set; } = ["mp4", "webm"];
   /// <summary>The client picks any audio track inside the file itself (native players).</summary>
   public bool SelectsAudioTrack { get; set; }
   ```

2. `PlaybackDecider.Decide`:

   ```csharp
   var directContainer = client.DirectContainers.Contains(container);
   var firstAudio = client.SelectsAudioTrack || audio is null || probe.Audio.Count == 0 || probe.Audio[0].Index == audio.Index;
   ```

   (and the "not playable directly in browsers" reason text can say "on this device").

3. `src/app/api/playback/start/route.ts` → `sanitizeCaps`: pass both fields through, validated:

   ```ts
   directContainers: list(r.directContainers, ["mp4", "webm"]),
   selectsAudioTrack: r.selectsAudioTrack === true,
   ```

   (plus the two fields on `ClientCapabilities` in `src/lib/playback-orchestrator.ts`).

4. Android app (`DeviceCapabilities.toJson()`): send
   `directContainers = ["mp4", "webm", "matroska", "mpegts"]` and `selectsAudioTrack = true`, and in
   `PlayerViewModel.load()` select the engine's `tracks.selectedAudio` by stream index (it already
   prefers the engine's chosen language).

5. Tests: one `PlaybackDecider` unit test per new branch (MKV + native client → DirectPlay; MKV +
   browser defaults → Remux, unchanged).

The engine already serves Direct Play sessions over authenticated range requests
(`/api/playback/{id}/stream`), so the Real-Debrid link stays on the server either way.
