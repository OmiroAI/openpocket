# Backend transcription worker

Android receives a durable delivery receipt when the backend accepts the audio.
A separate worker on the server would read that audio, check the configured speech
service, split long recordings to its input limits and checkpoint each completed
chunk. During a service outage, queued work stays pending with a retry time.
An ambiguous submission needs reconciliation before replay.

Publish one immutable transcript revision after verifying full recording coverage.
Include the recording ID, original time, normalized time, duration, language,
segment offsets, model and revision. Record partial coverage, unusable speech and
quality failures explicitly. Agents should be able to retrieve the transcript
through authenticated access and acknowledge processing independently.

The backend worker uses authenticated job discovery, reads immutable source audio,
and publishes revision JSON in a private volume shared with Example Agent. The worker holds
a single-writer filesystem lock. Durable per-chunk submission markers require review
if completion is uncertain. Segments have source offsets in 16 kHz sample frames.
Revision 1 preserves original metadata. Older imported transcripts retain their
original text and results. The notes monitor selects queued jobs with a published
revision. Agent completion still uses a lease and the existing receipt/job API.
The private shared volume is the initial transcript delivery mechanism.

A hold.json marks a job for operator review. Inspect the reason and chunk
checkpoints privately before deciding whether resubmission is safe. Published
v1.json indicates completed transcription, while the job API records the agent's
later notes outcome. The supplied transcript exchange is local to the deployment.
