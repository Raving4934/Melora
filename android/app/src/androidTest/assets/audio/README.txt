Generated silent test audio, no music recordings or third-party samples.
ffmpeg -f lavfi -i anullsrc=r=48000:cl=mono -t 1 -c:a flac -sample_fmt s16 -bits_per_raw_sample 16 fixture-16.flac
ffmpeg -f lavfi -i anullsrc=r=48000:cl=mono -t 1 -c:a flac -sample_fmt s32 -bits_per_raw_sample 24 fixture-24.flac
ffmpeg -f lavfi -i anullsrc=r=44100:cl=mono -t 1 -c:a libmp3lame -b:a 128k fixture-128.mp3
All files include metadata: title=Fixture, artist=Test Artist, album=Fixture Album.
Used only in the instrumentation APK for actual format/deduplication/publication tests.

fixture-320.mp3: 1 second of generated stereo silence, MP3 CBR 320kbps.
Generated locally with ffmpeg lavfi anullsrc and libmp3lame; no third-party audio.
