# Pinned HLS EOF adapter host regressions

Runs the production compatibility source against exactly the admitted Media3 1.11.1 jars, using minimal Android shims that are never app source sets. The 77 frozen candidate tests plus four distinct independent-review tests make 81 unique cases; duplicate reruns in the review fixture were removed here. No source fork or dependency was added.

Run `app/src/testSupport/hls-eof-compat/run.sh`. Output defaults under ignored `build/hls-eof-compat-host`.

The synthetic transport/slice-prefix fixtures are not entropy-valid, and do not establish Android HlsMediaSource/Transformer lifecycle or MP4 decoder acceptance. The separate Android native acceptance gate remains NOT_RUN.
