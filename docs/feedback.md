# Feedback log

What real people said or observed, newest first. Keep it raw: who, when, on what setup, what
they said. Interpretation and plans go in [roadmap.md](roadmap.md). Research findings (papers,
competitors) are listed separately at the bottom because they aren't from our own users.

Who's who: **owner** (Avi, sighted, building and testing in NYC); **collaborator** (India, runs
the same kit; first hands-on test in about a week). No blind or low-vision tester yet.

## Log

### Nov 1 2026, collaborator (relayed by the owner, hasn't used the device yet)

- The final product can't have a cable to the phone, so it needs **Bluetooth**. A phone hotspot
  drains the phone too much.
- Needs its own **battery**.
- Form: **clip-on, or 3D-printed glasses**.
- Wants more software features beyond describe and ask:
  - **obstacle prevention**;
  - **non-voice output**, e.g. beeps;
  - **find mode**: type what you're looking for, the device scans live and guides you to it in a
    house.

Owner's view: agrees for the product, but software, AI and UI can keep being tested on today's
cable/hotspot rig; hardware UX is a separate, slower track.

### Oct 31 2026, owner, ESP32-CAM on a USB cable to a Pixel 9a, Bapick glasses as speaker

- Cable demo works after a firmware/app fix: 6/6 taps, ~2.2 s tap to first word.
- "Too dark to see." said twice for a pale ceiling/wall corner that wasn't dark. A wrong answer, not a camera problem.
- Accidentally pressed the adapter's IO0 button while swapping cables, which can stop the
  camera. **The bare board is hard to handle; it needs a case.**
- Didn't want the spoken "ready" at launch; a visual cue is enough for him for now (sighted
  developer; revisit for blind users).
- Home screen: wants the photo on the left, question and answer streaming on the right like a
  chat, and the Describe button never changing size.
- Hold-to-ask over the cable worked ("Yes, a black water bottle, ahead of you.").

### Oct 30–31 2026, owner, Bapick M08T camera glasses (own Bluetooth code)

- **Camera image "amazing, far better than mine"** (vs the ESP32-CAM's OV2640), especially in
  dim rooms.
- **Too slow:** 5–8 s to describe, 7.5–9 s to answer a question. Not usable for walking.
- **Speakers are loud: people nearby can hear them** (unlike Meta Ray-Bans). Still liked the
  glasses as a frame and speaker for demos.
- In a 15-minute demo, button presses stopped arriving after the first description (glasses
  asleep or out of range, cause unknown).

### Oct 26–30 2026, owner, early walks with the ESP32-CAM on the hotspot

- Describe took ~1.5 s from tap to first word. Sessions and failure cases are in `sessions/` and
  were mined into the eval set ([evals.md](evals.md)).
- Compared with commercial devices (Torchit Jyoti, SHG), "ours is pretty bad" on image quality.
- Hardware wishes: a much better camera, wireless, open-ear audio like Meta's, and a way to
  choose when the device talks (not always-on audio).

## From research, not our users

Summarised from the Oct 28–29 literature and competitor review. Use these to choose what to
test, not as findings about our device.

- **Reading text** is the most-requested task (signs, labels, medicine, forms, bus numbers).
- **Obstacles above the waist** (branches, signboards, AC units) are the best-proven need in
  India, since the cane misses them. SmartCane and Torchit's Saarthi (sonar, clips on the cane,
  vibration, a 3-position range switch with Braille) were built for this.
- **Low-vision users outnumber blind users** in India; a clip-on keeps their own lenses usable.
- **Ears free:** users reject earphones on noisy streets; vibration or open-ear audio is preferred.
- **Physical controls beat menus:** a switch you can feel. The collaborator saw someone struggle
  with menu modes on commercial AI glasses.
- **Proactive speech must be rare and right**, or users turn it off; keep the button as the
  reliable path.
- **Price and channel:** many users live on ₹300–400 a day; Saarthi sells mostly through NGOs,
  schools and government, with blind trainers doing onboarding.
- **Faces:** recognising known people is valued; strangers raise consent issues (DPDP Act 2023).
