# Export fix 3.8.1

Reported configuration: 1920×1080, 30 fps; one main clip, one overlay, no music; Samsung SM-S908U, Android 16. Failure: `audio encoder didn't finish` at Exporter.drainAudio (3.8 line 814).

The problem was output-completion state, independent of which audio segments are mixed. `audioEos` recorded that input EOS was queued, but no state recorded whether the output EOS had already been dequeued by a previous nonblocking drain. The video finish loop also polls audio, making that ordering possible.

The fix adds sticky `audioOutputEos` and `videoOutputEos` flags. Completed drains return immediately, final payloads still go through write/release before completion is recorded, and genuine missing EOS still times out. Progress resets stall counters; cancellation is checked during drains; successful PCM input resets the input-stall counter.

The media format, audio bitrate, user project format and audio automation behavior are unchanged by this patch. No extra Android permission is introduced. Preview package and signing key match 3.8; versionCode increments from 36 to 37.
